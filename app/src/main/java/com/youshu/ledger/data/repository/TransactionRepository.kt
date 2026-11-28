package com.youshu.ledger.data.repository

import com.youshu.ledger.data.local.dao.TransactionDao
import com.youshu.ledger.data.local.entity.AttachmentEntity
import com.youshu.ledger.data.local.entity.RefundRecordEntity
import com.youshu.ledger.data.local.entity.TransactionEntity
import com.youshu.ledger.data.local.entity.TransactionWithDetails
import com.youshu.ledger.data.local.entity.TxnType
import com.youshu.ledger.data.settings.SettingsStore
import com.youshu.ledger.domain.balance.BalanceCalculator
import kotlinx.coroutines.flow.Flow

/**
 * 流水仓库：写入 / 编辑 / 删除流水及其退款、附件，
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

    suspend fun getTransaction(id: Long) = dao.getById(id)

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

    /** 删除单条退款记录 */
    suspend fun deleteRefund(record: RefundRecordEntity) {
        dao.deleteRefund(record)
        settings.bumpDataVersion()
    }

    // ---------- 条目组（v1.7） ----------

    suspend fun getGroupTransactions(gid: Long) = dao.getGroupTransactions(gid)

    /** 合并已存在的组：所有成员迁移到当前组号。 */
    suspend fun mergeGroups(oldGid: Long, newGid: Long) {
        if (oldGid != newGid) {
            dao.mergeGroups(oldGid, newGid)
            settings.bumpDataVersion()
        }
    }

    /** 候选统一为当前账本未在本组的流水，包含已有组员以支持合并小组。 */
    suspend fun getGroupCandidates(bookId: Long, excludeId: Long, gid: Long) =
        dao.getByBook(bookId).filter { it.transaction.id != excludeId && it.transaction.groupId != gid }

    /** 把流水的组号置为自身 id（成为组头），返回组号 */
    suspend fun createGroup(txn: TransactionEntity): Long {
        dao.update(txn.copy(groupId = txn.id))
        settings.bumpDataVersion()
        return txn.id
    }

    /** 把一条流水挂入组 / 移出组 */
    suspend fun setTxnGroup(txnId: Long, gid: Long?) {
        dao.getById(txnId)?.let { current ->
            val oldGid = current.groupId
            if (gid != null && oldGid != null && oldGid != gid) dao.mergeGroups(oldGid, gid)
            val it = dao.getById(txnId) ?: current
            dao.update(it.copy(groupId = gid))
            settings.bumpDataVersion()
        }
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
