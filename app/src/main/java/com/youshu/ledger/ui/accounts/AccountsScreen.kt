package com.youshu.ledger.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.youshu.ledger.data.AppContainer
import com.youshu.ledger.data.local.entity.AccountEntity
import com.youshu.ledger.domain.util.currentMinuteOfDay
import com.youshu.ledger.domain.util.formatDay
import com.youshu.ledger.domain.util.formatEnableDay
import com.youshu.ledger.domain.util.formatMinuteOfDay
import com.youshu.ledger.domain.util.formatTime
import com.youshu.ledger.domain.util.toEpochMillis
import com.youshu.ledger.domain.util.toLocalDate
import com.youshu.ledger.domain.util.toYuan
import com.youshu.ledger.ui.common.AccountIcon
import com.youshu.ledger.ui.common.ConfirmDialog
import com.youshu.ledger.ui.common.DayPickerDialog
import com.youshu.ledger.ui.common.LedgerTopBar
import com.youshu.ledger.ui.common.MoneyInputDialog
import com.youshu.ledger.ui.common.moneyTextToFen
import com.youshu.ledger.ui.common.normalizeIconText
import com.youshu.ledger.ui.theme.expense
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountWithBalance(val account: AccountEntity, val balance: Long)

/** 新建账户对话框的草稿：写入 SavedStateHandle，进程被杀死后重建可恢复 */
@kotlinx.serialization.Serializable
data class CreateAccountDraft(
    val name: String = "",
    val iconKey: String = "",
    val balanceText: String = "",
    val enableDate: Long = 0,
    val enableTimeOfDay: Int = -1,
)

data class AccountsUiState(
    val loading: Boolean = true,
    val bookId: Long = 0,
    val bookName: String = "",
    val active: List<AccountWithBalance> = emptyList(),
    val archived: List<AccountWithBalance> = emptyList(),
    /** 余额截至时刻：null = 当前时间（各账户余额与合计均按此计算） */
    val asOfMillis: Long? = null,
    /** 实际用于计算的有效截至时刻（asOfMillis 为 null 时取收集瞬间的当前时间） */
    val effectiveAsOf: Long = 0,
) {
    /** 合计（仅统计未归档账户，即日常在用的净资产） */
    val totalBalance: Long get() = active.sumOf { it.balance }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModel(
    container: AppContainer,
    private val handle: SavedStateHandle,
) : ViewModel() {

    private val bookRepo = container.bookRepository
    private val accountRepo = container.accountRepository
    private val txnRepo = container.transactionRepository
    private val settings = container.settingsStore

    /** 余额截至时刻：null = 当前时间 */
    private val asOf = MutableStateFlow<Long?>(null)

    fun setAsOf(millis: Long?) {
        asOf.value = millis
    }

    val uiState: StateFlow<AccountsUiState> =
        combine(
            bookRepo.currentBook,
            settings.dataVersion,
        ) { book, _ -> book }
            .flatMapLatest { book ->
                if (book == null) flow { emit(AccountsUiState()) }
                else combine(
                    accountRepo.observeByBook(book.id),
                    settings.dataVersion,
                    asOf,
                ) { accounts, _, asOfMillis ->
                    val cutoff = asOfMillis ?: System.currentTimeMillis()
                    val withBalance = accounts.map { acc ->
                        AccountWithBalance(acc, txnRepo.getAccountBalance(acc, cutoff))
                    }
                    AccountsUiState(
                        loading = false,
                        bookId = book.id,
                        bookName = book.name,
                        active = withBalance.filter { !it.account.archived },
                        archived = withBalance.filter { it.account.archived },
                        asOfMillis = asOfMillis,
                        effectiveAsOf = cutoff,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountsUiState())

    fun saveAccount(account: AccountEntity) {
        viewModelScope.launch { accountRepo.upsert(account) }
    }

    fun archive(account: AccountEntity, archived: Boolean) {
        viewModelScope.launch { accountRepo.setArchived(account, archived) }
    }

    fun delete(account: AccountEntity, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val ok = accountRepo.delete(account)
            onResult(ok, if (ok) null else "该账户存在流水，不能删除，可改为归档")
        }
    }

    /** 读取新建账户草稿（进程重建后恢复对话框内容） */
    fun createDraftOrNull(): CreateAccountDraft? =
        handle.get<String>(KEY_CREATE_DRAFT)?.let {
            runCatching {
                kotlinx.serialization.json.Json.decodeFromString(CreateAccountDraft.serializer(), it)
            }.getOrNull()
        }

    /** 草稿变化即写入（字段每次修改都会调用） */
    fun saveCreateDraft(draft: CreateAccountDraft) {
        handle[KEY_CREATE_DRAFT] = kotlinx.serialization.json.Json.encodeToString(
            CreateAccountDraft.serializer(), draft,
        )
    }

    fun clearCreateDraft() {
        handle.remove<String>(KEY_CREATE_DRAFT)
    }

    companion object {
        private const val KEY_CREATE_DRAFT = "create_account_draft"

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { AccountsViewModel(container, createSavedStateHandle()) }
        }
    }
}

/**
 * 账户页：账户列表（图标、名称、余额，负余额红色）、新建/编辑（期初余额与启用日期）、
 * 归档/删除、点入查看单账户流水。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(container: AppContainer, onOpen: (Long) -> Unit) {
    val vm: AccountsViewModel = viewModel(factory = AccountsViewModel.factory(container))
    val state by vm.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<AccountEntity?>(null) }
    // 有未保存草稿（如进程被杀后重建）时直接恢复新建对话框
    var showCreate by remember { mutableStateOf(vm.createDraftOrNull() != null) }
    var pendingDelete by remember { mutableStateOf<AccountEntity?>(null) }
    // 余额截至时间选择（日期时间一体）
    var showAsOfTimePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { LedgerTopBar(title = "账户") },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "新建账户")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // 账本切换栏：顶部切换当前账本
            item(key = "book_bar") {
                com.youshu.ledger.ui.common.BookSwitcherBar(container)
            }
            // 截至时间选择：各账户余额与合计均按所选时刻计算（默认当前时间；日期时间一体选择）
            item(key = "as_of") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (state.asOfMillis == null) {
                            "余额截至：现在"
                        } else {
                            "余额截至：${state.effectiveAsOf.formatTime()}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.asOfMillis == null) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(onClick = { showAsOfTimePicker = true }) { Text("选择时间") }
                    if (state.asOfMillis != null) {
                        TextButton(onClick = { vm.setAsOf(null) }) { Text("回到现在") }
                    }
                }
            }
            // 合计栏：所有未归档账户余额之和（净资产）
            item(key = "total") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "合计（${state.active.size} 个账户）",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            if (state.archived.isNotEmpty()) {
                                Text(
                                    "不含 ${state.archived.size} 个已归档",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                )
                            }
                        }
                        Text(
                            text = state.totalBalance.toYuan() + " 元",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (state.totalBalance < 0) MaterialTheme.colorScheme.expense
                            else MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            items(state.active, key = { it.account.id }) { item ->
                AccountRow(
                    item = item,
                    onClick = { onOpen(item.account.id) },
                    onEdit = { editing = item.account },
                    onArchive = { vm.archive(item.account, true) },
                    onDelete = { pendingDelete = item.account },
                )
            }
            if (state.archived.isNotEmpty()) {
                item(key = "archived_header") {
                    Text(
                        "已归档",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                items(state.archived, key = { it.account.id }) { item ->
                    AccountRow(
                        item = item,
                        onClick = { onOpen(item.account.id) },
                        onEdit = { editing = item.account },
                        onArchive = { vm.archive(item.account, false) },
                        onDelete = { pendingDelete = item.account },
                    )
                }
            }
            item { Spacer(modifier = Modifier.padding(24.dp)) }
        }
    }

    if (showAsOfTimePicker) {
        com.youshu.ledger.ui.common.DateTimePickerDialog(
            initialMillis = state.effectiveAsOf,
            title = "余额截至时间",
            onConfirm = { millis ->
                vm.setAsOf(millis)
                showAsOfTimePicker = false
            },
            onDismiss = { showAsOfTimePicker = false },
        )
    }

    if (showCreate) {
        AccountEditDialog(
            account = null,
            draft = vm.createDraftOrNull(),
            onDraftChange = { vm.saveCreateDraft(it) },
            onSave = { acc ->
                vm.saveAccount(acc.copy(bookId = state.bookId))
                vm.clearCreateDraft()
                showCreate = false
            },
            onDismiss = {
                vm.clearCreateDraft()
                showCreate = false
            },
        )
    }
    editing?.let { acc ->
        AccountEditDialog(
            account = acc,
            onSave = { updated ->
                vm.saveAccount(updated)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    pendingDelete?.let { acc ->
        ConfirmDialog(
            title = "删除账户",
            text = "删除账户「${acc.name}」？该操作不可恢复。",
            confirmText = "删除",
            onConfirm = {
                vm.delete(acc) { ok, err ->
                    scope.launch { snackbar.showSnackbar(err ?: "已删除账户") }
                }
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun AccountRow(
    item: AccountWithBalance,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountIcon(item.account.iconKey, 40.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.account.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${formatEnableDay(item.account.enableDate, item.account.enableTimeOfDay)}启用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                text = item.balance.toYuan() + " 元",
                color = if (item.balance < 0) MaterialTheme.colorScheme.expense else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (item.account.archived) "取消归档" else "归档") },
                        leadingIcon = {
                            Icon(if (item.account.archived) Icons.Default.Unarchive else Icons.Default.Archive, null)
                        },
                        onClick = { menu = false; onArchive() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.expense) },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

/** 新建/编辑账户对话框：名称、图标、期初余额、启用日期与启用时间（精确到分钟）。
 *  新建模式支持草稿（draft + onDraftChange）：任何修改即持久化，
 *  进程被杀死后重建对话框内容不丢；保存或取消时清除。 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AccountEditDialog(
    account: AccountEntity?,
    draft: CreateAccountDraft? = null,
    onDraftChange: (CreateAccountDraft) -> Unit = {},
    onSave: (AccountEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(account?.name ?: draft?.name ?: "") }
    // 图标文字（1~2 个字），持久化在 iconKey 字段
    var iconText by remember {
        mutableStateOf(account?.iconKey ?: draft?.iconKey?.takeIf { it.isNotBlank() } ?: "")
    }
    var balanceText by remember {
        mutableStateOf(account?.let { (it.initialBalance / 100.0).let { v -> if (it.initialBalance % 100 == 0L) "${it.initialBalance / 100}" else "%.2f".format(v) } } ?: draft?.balanceText ?: "")
    }
    var enableDate by remember {
        mutableStateOf(account?.enableDate ?: draft?.enableDate?.takeIf { it > 0 } ?: com.youshu.ledger.domain.util.todayMillis())
    }
    // 启用时间：-1 = 未设置（当天 00:00 起算）；新建默认当前时刻
    var enableTimeOfDay by remember {
        mutableStateOf(
            account?.enableTimeOfDay
                ?: draft?.enableTimeOfDay?.takeIf { it >= 0 }
                ?: currentMinuteOfDay(),
        )
    }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showBalanceDialog by remember { mutableStateOf(false) }

    /** 新建模式下把当前输入写入草稿 */
    fun persistDraft() {
        if (account == null) {
            onDraftChange(CreateAccountDraft(name, iconText, balanceText, enableDate, enableTimeOfDay))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (account == null) "新建账户" else "编辑账户") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; persistDraft() },
                    label = { Text("账户名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 图标：自定义 1~2 个文字（留空则取名称首字）
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccountIcon(iconText.ifBlank { name }, 44.dp)
                    OutlinedTextField(
                        value = iconText,
                        onValueChange = { t -> iconText = t.take(2); persistDraft() },
                        label = { Text("图标文字（1~2 个字）") },
                        placeholder = { Text("留空取名称首字") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 期初余额：走统一金额对话框（带校验）
                OutlinedButton(
                    onClick = { showBalanceDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("期初余额：${balanceText.ifBlank { "0" }} 元（点击设置，可为负）")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showDatePicker = true }) {
                        Text("启用日期：${enableDate.formatDay()}")
                    }
                    TextButton(onClick = { showTimePicker = true }) {
                        Text(
                            "启用时间：${formatMinuteOfDay(enableTimeOfDay).ifEmpty { "未设置" }}",
                            color = if (enableTimeOfDay >= 0) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Text(
                    "余额从启用时刻起算，之前的流水记为补录；不设置时间则从当天 00:00 起算",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val fen = balanceText.replace(",", "").toDoubleOrNull()?.let { (it * 100).toLong() } ?: 0L
                if (name.isBlank()) return@TextButton
                onSave(
                    (account ?: AccountEntity(
                        id = 0, bookId = 0, name = "", iconKey = "",
                        initialBalance = 0, enableDate = enableDate, sortOrder = 0, archived = false,
                    )).copy(
                        name = name.trim(),
                        iconKey = normalizeIconText(iconText.ifBlank { name.trim() }),
                        initialBalance = fen,
                        enableDate = enableDate,
                        enableTimeOfDay = enableTimeOfDay,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showBalanceDialog) {
        MoneyInputDialog(
            title = "期初余额",
            initialFen = moneyTextToFen(balanceText, allowNegative = true) ?: 0L,
            allowNegative = true,
            allowEmpty = true,
            onConfirm = { fen ->
                balanceText = if (fen % 100 == 0L) "${fen / 100}" else "%.2f".format(fen / 100.0)
                persistDraft()
                showBalanceDialog = false
            },
            onDismiss = { showBalanceDialog = false },
        )
    }

    if (showDatePicker) {
        DayPickerDialog(
            initialMillis = enableDate,
            onConfirm = { enableDate = it; persistDraft(); showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = (enableTimeOfDay / 60).coerceIn(0, 23),
            initialMinute = (enableTimeOfDay % 60).let { if (enableTimeOfDay < 0) 0 else it.coerceIn(0, 59) },
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("启用时间") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    enableTimeOfDay = timePickerState.hour * 60 + timePickerState.minute
                    persistDraft()
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        enableTimeOfDay = -1
                        persistDraft()
                        showTimePicker = false
                    }) { Text("不设置") }
                    TextButton(onClick = { showTimePicker = false }) { Text("取消") }
                }
            },
        )
    }
}
