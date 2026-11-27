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
import com.youshu.ledger.data.local.entity.AdvanceRecordEntity
import com.youshu.ledger.data.local.entity.AttachmentEntity
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.CategoryType
import com.youshu.ledger.data.local.entity.ReimburseRecordEntity
import com.youshu.ledger.data.local.entity.RefundRecordEntity
import com.youshu.ledger.data.local.entity.TransactionEntity
import com.youshu.ledger.data.local.entity.TxnType
import com.youshu.ledger.domain.util.currentMinuteOfDay
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
    /** 当天分钟数（0~1439），-1 = 未记录时间 */
    val timeOfDay: Int = -1,
    val project: String = "",
    val note: String = "",
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val feeFen: Long = 0,
    val recoveredFen: Long = 0,
    val refunds: List<RefundRecordEntity> = emptyList(),
    val reimbursements: List<ReimburseRecordEntity> = emptyList(),
    val advances: List<AdvanceRecordEntity> = emptyList(),
    val attachments: List<AttachmentEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    /** 条目组：当前流水的组号（null = 未入组；组号 = 组内首条支出流水 id） */
    val groupId: Long? = null,
    /** 组内其他支出条（展示用；任一组员上可见可编辑） */
    val groupExpenses: List<TransactionEntity> = emptyList(),
    /** 可加入本组的支出流水候选（打开选择对话框时加载） */
    val linkCandidates: List<TransactionEntity> = emptyList(),
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

    /** 按关键字过滤分类树（搜索用）：命中根或子分类即保留 */
    fun filteredCategoryTree(keyword: String): List<Pair<CategoryEntity, List<CategoryEntity>>> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return categoryTree
        return categoryTree.mapNotNull { (root, children) ->
            val matchedChildren = children.filter { it.name.contains(kw, ignoreCase = true) }
            when {
                root.name.contains(kw, ignoreCase = true) -> root to children
                matchedChildren.isNotEmpty() -> root to matchedChildren
                else -> null
            }
        }
    }

    val refundSum: Long get() = refunds.sumOf { it.amount }
    val reimburseSum: Long get() = reimbursements.sumOf { it.amount }
    val advanceTotal: Long get() = advances.sumOf { it.amount ?: 0L }
    val receivable: Long get() = (advanceTotal - recoveredFen).coerceAtLeast(0)

    fun validate(amountFen: Long?): String? = when {
        amountFen == null || amountFen == 0L -> "请输入金额"
        type == TxnType.TRANSFER && accountId == null -> "请选择转出账户"
        type == TxnType.TRANSFER && toAccountId == null -> "请选择转入账户"
        type == TxnType.TRANSFER && accountId == toAccountId -> "转出与转入账户不能相同"
        type != TxnType.TRANSFER && accountId == null -> "请选择账户"
        type != TxnType.TRANSFER && categoryId == null -> "请选择分类"
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
    val timeOfDay: Int = -1,
    val project: String = "",
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
            val categories = categoryRepo.getAll()
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
                        timeOfDay = detail.transaction.timeOfDay,
                        project = detail.transaction.project,
                        note = detail.transaction.note,
                        categoryId = detail.transaction.categoryId,
                        accountId = detail.transaction.accountId,
                        toAccountId = detail.transaction.toAccountId,
                        feeFen = detail.transaction.fee,
                        recoveredFen = detail.transaction.recoveredAmount,
                        refunds = detail.refunds,
                        reimbursements = detail.reimbursements,
                        advances = detail.advances,
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
                    timeOfDay = if (draft.timeOfDay >= 0) draft.timeOfDay else currentMinuteOfDay(),
                    project = draft.project,
                    note = draft.note,
                    categoryId = draft.categoryId
                        ?.takeIf { id -> categories.any { it.id == id } },
                    accountId = draft.accountId
                        ?.takeIf { id -> accounts.any { it.id == id } }
                        ?: state.accountId,
                    toAccountId = draft.toAccountId
                        ?.takeIf { id -> accounts.any { it.id == id } },
                    feeFen = draft.feeFen,
                )
                draft.amountText.forEach { amountInput.append(it) }
            } else {
                // 全新记账：默认时间为此刻
                state = state.copy(timeOfDay = currentMinuteOfDay())
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
                timeOfDay = s.timeOfDay,
                project = s.project,
                note = s.note,
                categoryId = s.categoryId,
                accountId = s.accountId,
                toAccountId = s.toAccountId,
                feeFen = s.feeFen,
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

    /** 在记一笔页直接新增分类（根或某根分类下的子分类），创建后自动选中 */
    fun createCategory(name: String, parentId: Long?, type: CategoryType, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            if (name.isBlank()) {
                onResult("请输入分类名称")
                return@launch
            }
            val bookId = _ui.value.bookId
            val all = categoryRepo.getAll()
            val ofType = all.filter { it.type == type }
            // 只支持两级：父分类必须是根分类（防止任何入口造出第三级）
            if (parentId != null) {
                val parent = ofType.firstOrNull { it.id == parentId }
                if (parent == null) {
                    onResult("父分类不存在")
                    return@launch
                }
                if (parent.parentId != null) {
                    onResult("分类最多支持两级（一级分类 · 二级分类）")
                    return@launch
                }
            }
            val sortOrder = (ofType.maxOfOrNull { it.sortOrder } ?: -1) + 1
            val trimmed = name.trim()
            val id = categoryRepo.upsert(
                CategoryEntity(
                    bookId = bookId,
                    type = type,
                    parentId = parentId?.takeIf { pid -> ofType.any { it.id == pid } },
                    name = trimmed,
                    sortOrder = sortOrder,
                    color = com.youshu.ledger.ui.common.accountIconColor(trimmed),
                    iconText = trimmed.take(2),
                ),
            )
            _ui.value = _ui.value.copy(
                categories = categoryRepo.getAll(),
                categoryId = id,
            )
            persistDraft()
            onResult(null)
        }
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
            timeOfDay = s.timeOfDay,
            createdAt = base?.createdAt ?: now,
            note = s.note.trim(),
            project = s.project.trim(),
            categoryId = if (s.type == TxnType.TRANSFER) null else s.categoryId,
            accountId = s.accountId ?: return "请选择账户",
            toAccountId = if (s.type == TxnType.TRANSFER) s.toAccountId else null,
            fee = if (s.type == TxnType.TRANSFER) s.feeFen else 0,
            advanceAmount = if (s.type != TxnType.TRANSFER) s.advanceTotal else 0,
            recoveredAmount = base?.recoveredAmount ?: 0,
            groupId = base?.groupId,
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

    fun addRefund(
        amount: Long,
        refundAccountId: Long,
        timeMillis: Long,
        note: String,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val id = ensureSaved() ?: run { onResult("请先完成必填项并保存"); return@launch }
            txnRepo.addRefund(id, amount, refundAccountId, timeMillis, note)
            reloadDetails()
            onResult(null)
        }
    }

    fun addReimburse(
        amount: Long,
        reimburseAccountId: Long,
        timeMillis: Long,
        note: String,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val id = ensureSaved() ?: run { onResult("请先完成必填项并保存"); return@launch }
            // 报销条是组内共享条目：首次添加时自动建组
            val gid = ensureGroup() ?: run { onResult("请先完成必填项并保存"); return@launch }
            txnRepo.addReimburse(id, amount, reimburseAccountId, timeMillis, note, groupId = gid)
            reloadDetails()
            onResult(null)
        }
    }

    /** 添加代付人（谁 必填；金额/时间/账户/备注可选） */
    fun addAdvance(
        person: String,
        amount: Long?,
        accountId: Long?,
        timeMillis: Long?,
        note: String,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val id = ensureSaved() ?: run { onResult("请先完成必填项并保存"); return@launch }
            if (person.isBlank()) {
                onResult("请填写代付对象（谁）")
                return@launch
            }
            // A钱条是组内共享条目：首次添加时自动建组
            val gid = ensureGroup() ?: run { onResult("请先完成必填项并保存"); return@launch }
            runCatching {
                txnRepo.addAdvance(
                    AdvanceRecordEntity(
                        transactionId = id,
                        person = person.trim(),
                        amount = amount,
                        accountId = accountId,
                        timeMillis = timeMillis,
                        note = note.trim(),
                        groupId = gid,
                    ),
                )
            }.onSuccess { reloadDetails(); onResult(null) }
                .onFailure { onResult(it.message) }
        }
    }

    /** 更新代付记录（组内任一流水上修改同一条记录） */
    fun updateAdvance(record: AdvanceRecordEntity, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            // 旧数据（未入组）编辑后自动纳入当前组，保证组内所有条目等价可见
            val gid = currentGroupOrSelf()
            val fixed = if (record.groupId == null && gid != null) record.copy(groupId = gid) else record
            runCatching { txnRepo.updateAdvance(fixed) }
                .onSuccess { reloadDetails(); onResult(null) }
                .onFailure { onResult(it.message) }
        }
    }

    fun deleteAdvance(record: AdvanceRecordEntity) {
        viewModelScope.launch {
            txnRepo.deleteAdvance(record)
            reloadDetails()
        }
    }

    /** 更新报销记录（组内任一流水上行内修改；旧数据自动纳入当前组） */
    fun updateReimburse(record: ReimburseRecordEntity, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val gid = currentGroupOrSelf()
            val fixed = if (record.groupId == null && gid != null) record.copy(groupId = gid) else record
            runCatching { txnRepo.updateReimburse(fixed) }
                .onSuccess { reloadDetails(); onResult(null) }
                .onFailure { onResult(it.message) }
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

    /** 当前组号：已入组直接返回；未入组返回 null（不自动建组） */
    private fun currentGroupOrSelf(): Long? {
        val txn = loadedTxn ?: return null
        return txn.groupId ?: _ui.value.editingId
    }

    /** 确保当前流水已入组（组号 = 自身 id，成为组头），返回组号；流水未保存返回 null */
    private suspend fun ensureGroup(): Long? {
        val txn = loadedTxn ?: return null
        val existing = txn.groupId
        if (existing != null) return existing
        val gid = txnRepo.createGroup(txn)
        loadedTxn = txn.copy(groupId = gid)
        _ui.value = _ui.value.copy(groupId = gid)
        return gid
    }

    /** 加载可加入本组的流水候选（打开选择对话框时调用；会先自动保存新建流水并建组） */
    fun loadLinkCandidates() {
        viewModelScope.launch {
            val id = ensureSaved() ?: return@launch
            val s = _ui.value
            val gid = ensureGroup() ?: return@launch
            _ui.value = _ui.value.copy(
                linkCandidates = txnRepo.getLinkCandidates(s.bookId, id, gid, s.type),
            )
        }
    }

    /** 把一条已有支出流水挂入本组（组内条目等价可见） */
    fun linkExpenseToGroup(txnId: Long, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val gid = ensureGroup() ?: run { onResult("请先保存流水"); return@launch }
            txnRepo.setTxnGroup(txnId, gid)
            reloadDetails()
            onResult(null)
        }
    }

    /** 把一条支出流水移出本组（其组内 A钱/报销条仍留在组中） */
    fun unlinkExpenseFromGroup(txnId: Long) {
        viewModelScope.launch {
            txnRepo.setTxnGroup(txnId, null)
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
            // 组号 = 已入组的 groupId，未入组时以自身 id 为虚组号（旧数据行为不变）
            val gid = detail.transaction.groupId ?: detail.transaction.id
            _ui.value = _ui.value.copy(
                refunds = detail.refunds,
                // A钱条 / 报销条组内共享：组号命中或仅挂在本流水
                reimbursements = txnRepo.getReimbursesForGroup(gid, id),
                advances = txnRepo.getAdvancesForGroup(gid, id),
                groupExpenses = txnRepo.getGroupExpenses(gid, id),
                groupId = detail.transaction.groupId,
                attachments = detail.attachments,
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
