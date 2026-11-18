package com.youshu.ledger.ui.record

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.youshu.ledger.data.AppContainer
import com.youshu.ledger.data.local.entity.AccountEntity
import com.youshu.ledger.data.local.entity.AttachmentEntity
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.CategoryType
import com.youshu.ledger.data.local.entity.ReimburseRecordEntity
import com.youshu.ledger.data.local.entity.RefundRecordEntity
import com.youshu.ledger.data.local.entity.TransactionEntity
import com.youshu.ledger.data.local.entity.TxnType
import com.youshu.ledger.domain.util.todayMillis
import com.youshu.ledger.ui.common.AmountInputState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

data class RecordUiState(
    val loading: Boolean = true,
    val editingId: Long? = null,
    val bookId: Long = 0,
    val type: TxnType = TxnType.EXPENSE,
    val businessDate: Long = todayMillis(),
    val note: String = "",
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val feeFen: Long = 0,
    val advanceFen: Long = 0,
    val recoveredFen: Long = 0,
    val refunds: List<RefundRecordEntity> = emptyList(),
    val reimbursements: List<ReimburseRecordEntity> = emptyList(),
    val attachments: List<AttachmentEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val saving: Boolean = false,
    val message: String? = null,
) {
    val editing: Boolean get() = editingId != null

    val visibleAccounts: List<AccountEntity> get() = accounts.filter { !it.archived }

    /** 分类两层树（当前类型） */
    val categoryTree: List<Pair<CategoryEntity, List<CategoryEntity>>>
        get() {
            val type = when (type) {
                TxnType.EXPENSE -> CategoryType.EXPENSE
                TxnType.INCOME -> CategoryType.INCOME
                TxnType.TRANSFER -> return emptyList()
            }
            val ofType = categories.filter { it.type == type }.sortedBy { it.sortOrder }
            return ofType.filter { it.parentId == null }
                .map { root -> root to ofType.filter { it.parentId == root.id } }
        }

    val refundSum: Long get() = refunds.sumOf { it.amount }
    val reimburseSum: Long get() = reimbursements.sumOf { it.amount }
    val receivable: Long get() = (advanceFen - recoveredFen).coerceAtLeast(0)

    fun validate(amountFen: Long?): String? = when {
        amountFen == null || amountFen == 0L -> "请输入金额"
        type == TxnType.TRANSFER && accountId == null -> "请选择转出账户"
        type == TxnType.TRANSFER && toAccountId == null -> "请选择转入账户"
        type == TxnType.TRANSFER && accountId == toAccountId -> "转出与转入账户不能相同"
        type != TxnType.TRANSFER && accountId == null -> "请选择账户"
        type != TxnType.TRANSFER && categoryId == null -> "请选择分类"
        type == TxnType.EXPENSE && advanceFen < 0 -> "代付金额不能为负"
        type == TxnType.EXPENSE && advanceFen > (amountFen ?: 0) -> "代付金额不能超过支出金额"
        else -> null
    }
}

/**
 * 未保存流水的草稿：写入 SavedStateHandle，进程被系统杀死后重建也能恢复。
 * 仅在「新建流水」时生效；编辑已有流水不写草稿。
 */
@Serializable
data class RecordDraft(
    val type: String = "EXPENSE",
    val businessDate: Long = 0,
    val note: String = "",
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val feeFen: Long = 0,
    val advanceFen: Long = 0,
    val amountText: String = "",
)

/**
 * 记一笔 / 编辑流水 ViewModel。
 * 退款、报销、收回「发生即计入余额」（见 BalanceCalculator）；
 * 因此这些操作要求流水已保存——未保存时先自动保存再追加记录。
 *
 * 草稿持久化：新建模式下的任何修改都会写入 SavedStateHandle，
 * 与应用切后台 / 系统回收进程后回来，表单原样恢复；保存成功后清除。
 */
class RecordViewModel(
    private val container: AppContainer,
    private val appContext: Context,
    private val txnId: Long?,
    private val handle: SavedStateHandle,
) : ViewModel() {

    private val bookRepo = container.bookRepository
    private val txnRepo = container.transactionRepository
    private val accountRepo = container.accountRepository
    private val categoryRepo = container.categoryRepository

    private val _ui = MutableStateFlow(RecordUiState())
    val ui: StateFlow<RecordUiState> = _ui

    /** 金额输入（分） */
    val amountInput = AmountInputState()

    /** 冷启动恢复的草稿金额文本（Compose 层取一次作为初始值） */
    val restoredAmountText: String? = draftOrNull()?.amountText?.takeIf { it.isNotEmpty() }

    private var loadedTxn: TransactionEntity? = null

    private fun draftOrNull(): RecordDraft? =
        if (txnId != null) null
        else handle.get<String>(KEY_DRAFT)?.let {
            runCatching { Json.decodeFromString<RecordDraft>(it) }.getOrNull()
        }

    init {
        viewModelScope.launch {
            val book = bookRepo.currentBook.filterNotNull().first()
            val accounts = accountRepo.getByBook(book.id)
            val categories = categoryRepo.getByBook(book.id)
            val draft = draftOrNull()
            var state = _ui.value.copy(
                loading = false,
                bookId = book.id,
                accounts = accounts,
                categories = categories,
                accountId = accounts.firstOrNull { !it.archived }?.id,
            )
            if (txnId != null) {
                txnRepo.getWithDetails(txnId)?.let { detail ->
                    loadedTxn = detail.transaction
                    amountInput.clear()
                    // 初始金额回填到键盘输入
                    val initialAmount = if (detail.transaction.amount % 100 == 0L) {
                        "${detail.transaction.amount / 100}"
                    } else {
                        String.format("%.2f", detail.transaction.amount / 100.0)
                    }
                    initialAmount.forEach { amountInput.append(it) }
                    state = state.copy(
                        editingId = txnId,
                        type = detail.transaction.type,
                        businessDate = detail.transaction.businessDate,
                        note = detail.transaction.note,
                        categoryId = detail.transaction.categoryId,
                        accountId = detail.transaction.accountId,
                        toAccountId = detail.transaction.toAccountId,
                        feeFen = detail.transaction.fee,
                        advanceFen = detail.transaction.advanceAmount,
                        recoveredFen = detail.transaction.recoveredAmount,
                        refunds = detail.refunds,
                        reimbursements = detail.reimbursements,
                        attachments = detail.attachments,
                    )
                }
            } else if (draft != null) {
                // 恢复草稿：账户/分类若已不存在则回退默认，避免悬空 id
                val restoredType = draft.type.let { name ->
                    TxnType.entries.firstOrNull { it.name == name } ?: TxnType.EXPENSE
                }
                state = state.copy(
                    type = restoredType,
                    businessDate = if (draft.businessDate > 0) draft.businessDate else todayMillis(),
                    note = draft.note,
                    categoryId = draft.categoryId
                        ?.takeIf { id -> categories.any { it.id == id } },
                    accountId = draft.accountId
                        ?.takeIf { id -> accounts.any { it.id == id } }
                        ?: state.accountId,
                    toAccountId = draft.toAccountId
                        ?.takeIf { id -> accounts.any { it.id == id } },
                    feeFen = draft.feeFen,
                    advanceFen = draft.advanceFen,
                )
                draft.amountText.forEach { amountInput.append(it) }
            }
            _ui.value = state
        }
    }

    /** 把当前表单写入 SavedStateHandle 草稿（新建模式） */
    fun persistDraft() {
        if (txnId != null) return
        val s = _ui.value
        if (s.loading) return
        handle[KEY_DRAFT] = Json.encodeToString(
            RecordDraft(
                type = s.type.name,
                businessDate = s.businessDate,
                note = s.note,
                categoryId = s.categoryId,
                accountId = s.accountId,
                toAccountId = s.toAccountId,
                feeFen = s.feeFen,
                advanceFen = s.advanceFen,
                amountText = amountInput.text,
            ),
        )
    }

    private fun clearDraft() {
        if (txnId == null) handle.remove<String>(KEY_DRAFT)
    }

    fun update(transform: (RecordUiState) -> RecordUiState) {
        _ui.value = transform(_ui.value)
        persistDraft()
    }

    /** 切换类型时清空分类（收入/支出分类树不同） */
    fun setType(type: TxnType) {
        if (type == _ui.value.type) return
        _ui.value = _ui.value.copy(type = type, categoryId = null, toAccountId = null, feeFen = 0)
        persistDraft()
    }

    /** 保存（新建或更新）。返回错误信息，null 表示成功。 */
    suspend fun save(): String? {
        val s = _ui.value
        val err = s.validate(amountInput.toFen())
        if (err != null) return err
        val amount = amountInput.toFen() ?: return "请输入金额"
        val now = System.currentTimeMillis()
        val base = loadedTxn
        val entity = TransactionEntity(
            id = base?.id ?: 0,
            bookId = s.bookId,
            type = s.type,
            amount = amount,
            businessDate = s.businessDate,
            createdAt = base?.createdAt ?: now,
            note = s.note.trim(),
            categoryId = if (s.type == TxnType.TRANSFER) null else s.categoryId,
            accountId = s.accountId ?: return "请选择账户",
            toAccountId = if (s.type == TxnType.TRANSFER) s.toAccountId else null,
            fee = if (s.type == TxnType.TRANSFER) s.feeFen else 0,
            advanceAmount = if (s.type == TxnType.EXPENSE) s.advanceFen else 0,
            recoveredAmount = base?.recoveredAmount ?: 0,
        )
        _ui.value = s.copy(saving = true)
        return try {
            if (base == null) {
                val id = txnRepo.createTransaction(entity)
                loadedTxn = entity.copy(id = id)
                _ui.value = _ui.value.copy(editingId = id, saving = false)
                clearDraft()
            } else {
                txnRepo.updateTransaction(entity)
                loadedTxn = entity
                _ui.value = _ui.value.copy(saving = false)
            }
            null
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(saving = false)
            "保存失败：${e.message}"
        }
    }

    /** 确保流水已保存（添加退款/报销/附件前调用），返回流水 id 或 null（校验失败） */
    private suspend fun ensureSaved(): Long? {
        _ui.value.validate(amountInput.toFen())?.let { return null }
        val id = _ui.value.editingId
        if (id != null) return id
        return if (save() == null) _ui.value.editingId else null
    }

    fun addRefund(amount: Long, note: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val id = ensureSaved() ?: run { onResult("请先完成必填项并保存"); return@launch }
            txnRepo.addRefund(id, amount, note)
            reloadDetails()
            onResult(null)
        }
    }

    fun addReimburse(amount: Long, note: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val id = ensureSaved() ?: run { onResult("请先完成必填项并保存"); return@launch }
            txnRepo.addReimburse(id, amount, note)
            reloadDetails()
            onResult(null)
        }
    }

    fun deleteRefund(record: RefundRecordEntity) {
        viewModelScope.launch {
            txnRepo.deleteRefund(record)
            reloadDetails()
        }
    }

    fun deleteReimburse(record: ReimburseRecordEntity) {
        viewModelScope.launch {
            txnRepo.deleteReimburse(record)
            reloadDetails()
        }
    }

    /** 登记收回代付（发生即 +余额） */
    fun addRecovery(amount: Long, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val txn = loadedTxn ?: run { onResult("请先保存流水"); return@launch }
            try {
                txnRepo.addRecovery(txn, amount)
                reloadDetails()
                onResult(null)
            } catch (e: IllegalArgumentException) {
                onResult(e.message)
            }
        }
    }

    /** 从相册/相机复制图片到 filesDir/attachments/{txnId}/ 并登记附件 */
    fun addAttachments(uris: List<Uri>, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val id = ensureSaved() ?: run { onResult("请先完成必填项并保存"); return@launch }
            runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(container.filesDir, "attachments/$id")
                    dir.mkdirs()
                    uris.forEach { uri ->
                        val target = File(dir, "${UUID.randomUUID()}.jpg")
                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("无法读取所选图片")
                        val rel = "attachments/$id/${target.name}"
                        txnRepo.addAttachment(id, rel)
                    }
                }
            }.onSuccess { reloadDetails(); onResult(null) }
                .onFailure { onResult("附件保存失败：${it.message}") }
        }
    }

    fun addAttachmentFile(file: File, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val id = ensureSaved() ?: run { onResult("请先完成必填项并保存"); return@launch }
            runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(container.filesDir, "attachments/$id")
                    dir.mkdirs()
                    val target = File(dir, "${UUID.randomUUID()}.jpg")
                    file.copyTo(target, overwrite = true)
                    txnRepo.addAttachment(id, "attachments/$id/${target.name}")
                }
            }.onSuccess { reloadDetails(); onResult(null) }
                .onFailure { onResult("附件保存失败：${it.message}") }
        }
    }

    fun deleteAttachment(attachment: AttachmentEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                File(container.filesDir, attachment.relativePath).delete()
            }
            txnRepo.deleteAttachment(attachment)
            reloadDetails()
        }
    }

    fun deleteTxn(onDone: () -> Unit) {
        viewModelScope.launch {
            loadedTxn?.let { txnRepo.deleteTransaction(it) }
            clearDraft()
            onDone()
        }
    }

    private suspend fun reloadDetails() {
        val id = _ui.value.editingId ?: return
        txnRepo.getWithDetails(id)?.let { detail ->
            loadedTxn = detail.transaction
            _ui.value = _ui.value.copy(
                refunds = detail.refunds,
                reimbursements = detail.reimbursements,
                attachments = detail.attachments,
                advanceFen = detail.transaction.advanceAmount,
                recoveredFen = detail.transaction.recoveredAmount,
            )
        }
    }

    companion object {
        private const val KEY_DRAFT = "record_draft"

        fun factory(container: AppContainer, context: Context, txnId: Long?) = viewModelFactory {
            initializer {
                RecordViewModel(
                    container, context.applicationContext, txnId,
                    createSavedStateHandle(),
                )
            }
        }
    }
}
