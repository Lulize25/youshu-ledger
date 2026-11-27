package com.youshu.ledger.data.repository

import com.youshu.ledger.data.local.dao.TransactionDao
import com.youshu.ledger.data.local.entity.AdvanceRecordEntity
import com.youshu.ledger.data.local.entity.AttachmentEntity
import com.youshu.ledger.data.local.entity.ReimburseRecordEntity
import com.youshu.ledger.data.local.entity.RefundRecordEntity
import com.youshu.ledger.data.local.entity.TransactionEntity
import com.youshu.ledger.data.local.entity.TransactionWithDetails
import com.youshu.ledger.data.local.entity.TxnType
import com.youshu.ledger.data.settings.SettingsStore
import com.youshu.ledger.domain.balance.BalanceCalculator
import kotlinx.coroutines.flow.Flow

/**
 * 流水仓库：写入 / 编辑 / 删除流水及其退款、报销、附件，
 * 并提供「账户当前余额」与统计所需的聚合查询。
 *
 * 余额为派生值（见 BalanceCalculator），因此：
 * - 修改 / 删除启用日之后的流水 → 正常回滚余额；
 * - 修改 / 删除补录流水 → 不影响余额（流水影响被启用日期过滤）。
 */
class TransactionRepository(
    private val dao: TransactionDao,
    private val settings: SettingsStore,
) {

    // ---------- 查询 ----------

    fun observeByBook(bookId: Long): Flow<List<TransactionWithDetails>> =
        dao.observeByBook(bookId)

    fun observeFiltered(
        bookId: Long,
        accountId: Long?,
        categoryId: Long?,
        type: TxnType?,
        keyword: String?,
        startDate: Long?,
        endDate: Long?,
    ): Flow<List<TransactionWithDetails>> =
        dao.observeFiltered(bookId, accountId, categoryId, type, keyword, startDate, endDate)

    fun observeByAccount(accountId: Long): Flow<List<TransactionWithDetails>> =
        dao.observeByAccount(accountId)

    suspend fun getWithDetails(id: Long): TransactionWithDetails? = dao.getWithDetails(id)

    /** 账户余额（含补录流水上的退款/报销/收回，见 BalanceCalculator 注释）。
     *  @param asOfMillis 截至时刻，默认 [Long.MAX_VALUE] 即当前余额 */
    suspend fun getAccountBalance(
        account: com.youshu.ledger.data.local.entity.AccountEntity,
        asOfMillis: Long = Long.MAX_VALUE,
    ): Long {
        val details = dao.getAllTouchingAccount(account.id)
            .mapNotNull { dao.getWithDetails(it.id) }
        return BalanceCalculator.computeBalance(account, details, asOfMillis)
    }

    // ---------- 写入 ----------

    /** 新建流水（支出/收入/转账），返回流水 id */
    suspend fun createTransaction(txn: TransactionEntity): Long {
        val id = dao.upsert(txn)
        settings.bumpDataVersion()
        return id
    }

    /** 整笔更新流水（记一笔编辑页提交），子表由调用方按需增删 */
    suspend fun updateTransaction(txn: TransactionEntity) {
        dao.update(txn)
        settings.bumpDataVersion()
    }

    /** 删除流水（退款/报销/附件随外键级联删除），余额自动回滚 */
    suspend fun deleteTransaction(txn: TransactionEntity) {
        dao.delete(txn)
        settings.bumpDataVersion()
    }

    /** 追加一条退款记录（可部分退款、可多次）；退款计入 refundAccountId 账户余额 */
    suspend fun addRefund(
        transactionId: Long,
        amount: Long,
        refundAccountId: Long,
        timeMillis: Long,
        note: String,
    ) {
        dao.upsertRefund(
            RefundRecordEntity(
                transactionId = transactionId,
                amount = amount,
                accountId = refundAccountId,
                timeMillis = timeMillis,
                note = note,
                createdAt = System.currentTimeMillis(),
            ),
        )
        settings.bumpDataVersion()
    }

    /** 追加一条报销记录（规则与退款一致）；[groupId] 非空时组内共享 */
    suspend fun addReimburse(
        transactionId: Long,
        amount: Long,
        reimburseAccountId: Long,
        timeMillis: Long,
        note: String,
        groupId: Long? = null,
    ) {
        dao.upsertReimburse(
            ReimburseRecordEntity(
                transactionId = transactionId,
                amount = amount,
                accountId = reimburseAccountId,
                timeMillis = timeMillis,
                note = note,
                createdAt = System.currentTimeMillis(),
                groupId = groupId,
            ),
        )
        settings.bumpDataVersion()
    }

    /** 删除单条退款 / 报销记录 */
    suspend fun deleteRefund(record: RefundRecordEntity) {
        dao.deleteRefund(record)
        settings.bumpDataVersion()
    }

    suspend fun deleteReimburse(record: ReimburseRecordEntity) {
        dao.deleteReimburse(record)
        settings.bumpDataVersion()
    }

    // ---------- 代付人明细 ----------

    /** 添加一条代付记录（谁 必填），并同步流水的 advanceAmount 总额缓存 */
    suspend fun addAdvance(record: AdvanceRecordEntity) {
        require(record.person.isNotBlank()) { "请填写代付对象（谁）" }
        dao.upsertAdvance(record)
        syncAdvanceTotal(record.transactionId)
        settings.bumpDataVersion()
    }

    /** 更新代付记录（谁 必填），并同步 advanceAmount 总额缓存 */
    suspend fun updateAdvance(record: AdvanceRecordEntity) {
        require(record.person.isNotBlank()) { "请填写代付对象（谁）" }
        dao.upsertAdvance(record)
        syncAdvanceTotal(record.transactionId)
        settings.bumpDataVersion()
    }

    /** 删除代付记录并同步 advanceAmount */
    suspend fun deleteAdvance(record: AdvanceRecordEntity) {
        dao.deleteAdvance(record)
        syncAdvanceTotal(record.transactionId)
        settings.bumpDataVersion()
    }

    /** advanceAmount = Σ 代付记录金额（null 视为 0），供统计与收回上限使用 */
    private suspend fun syncAdvanceTotal(transactionId: Long) {
        val detail = dao.getWithDetails(transactionId) ?: return
        val total = detail.advances.sumOf { it.amount ?: 0L }
        dao.update(detail.transaction.copy(advanceAmount = total))
    }

    // ---------- 条目组（v1.7） ----------

    /** 组内全部 A钱条（gid = 组号，通常为组内首条支出流水 id） */
    suspend fun getAdvancesForGroup(gid: Long, txnId: Long) = dao.getAdvancesForGroup(gid, txnId)

    /** 组内全部报销条 */
    suspend fun getReimbursesForGroup(gid: Long, txnId: Long) = dao.getReimbursesForGroup(gid, txnId)

    /** 组内其他支出条 */
    suspend fun getGroupExpenses(gid: Long, excludeId: Long) = dao.getGroupExpenses(gid, excludeId)

    /** 可加入本组的支出流水候选 */
    suspend fun getExpenseLinkCandidates(bookId: Long, excludeId: Long, gid: Long) =
        dao.getExpenseLinkCandidates(bookId, excludeId, gid)

    /** 把流水的组号置为自身 id（成为组头），返回组号 */
    suspend fun createGroup(txn: TransactionEntity): Long {
        dao.update(txn.copy(groupId = txn.id))
        settings.bumpDataVersion()
        return txn.id
    }

    /** 把一条支出流水挂入组 / 移出组 */
    suspend fun setTxnGroup(txnId: Long, gid: Long?) {
        dao.getById(txnId)?.let {
            dao.update(it.copy(groupId = gid))
            settings.bumpDataVersion()
        }
    }

    /**
     * 登记「收回代付」：增加流水的已收回金额（发生即给账户 +金额，计入余额）。
     * @param amount 本次收回金额（分），调用方需校验 已收回+amount ≤ 代付金额
     */
    suspend fun addRecovery(txn: TransactionEntity, amount: Long) {
        require(amount > 0) { "收回金额必须为正数" }
        require(txn.recoveredAmount + amount <= txn.advanceAmount) {
            "收回金额累计不能超过代付金额"
        }
        dao.update(txn.copy(recoveredAmount = txn.recoveredAmount + amount))
        settings.bumpDataVersion()
    }

    // ---------- 附件 ----------

    /** 记录附件相对路径（文件本身由 UI 层写入 filesDir/attachments/） */
    suspend fun addAttachment(transactionId: Long, relativePath: String) {
        dao.upsertAttachment(AttachmentEntity(transactionId = transactionId, relativePath = relativePath))
    }

    suspend fun deleteAttachment(attachment: AttachmentEntity) {
        dao.deleteAttachment(attachment)
    }
}
