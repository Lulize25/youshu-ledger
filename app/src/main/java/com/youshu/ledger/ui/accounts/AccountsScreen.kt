package com.youshu.ledger.ui.accounts

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.youshu.ledger.data.AppContainer
import com.youshu.ledger.data.local.entity.AccountEntity
import com.youshu.ledger.data.local.entity.AccountType
import com.youshu.ledger.domain.util.formatDay
import com.youshu.ledger.domain.util.toYuan
import com.youshu.ledger.ui.common.ConfirmDialog
import com.youshu.ledger.ui.common.DayPickerDialog
import com.youshu.ledger.ui.common.LedgerTopBar
import com.youshu.ledger.ui.theme.expense
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 账户类型 → (中文名, 图标) */
val ACCOUNT_TYPE_LABELS: Map<AccountType, Pair<String, String>> = mapOf(
    AccountType.CASH to ("现金" to "💵"),
    AccountType.BANK_CARD to ("银行卡" to "🏦"),
    AccountType.CREDIT_CARD to ("信用卡" to "💳"),
    AccountType.ALIPAY to ("支付宝" to "💙"),
    AccountType.WECHAT to ("微信" to "💚"),
    AccountType.OTHER to ("其他" to "📦"),
)

data class AccountWithBalance(val account: AccountEntity, val balance: Long)

data class AccountsUiState(
    val loading: Boolean = true,
    val bookId: Long = 0,
    val bookName: String = "",
    val active: List<AccountWithBalance> = emptyList(),
    val archived: List<AccountWithBalance> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModel(container: AppContainer) : ViewModel() {

    private val bookRepo = container.bookRepository
    private val accountRepo = container.accountRepository
    private val txnRepo = container.transactionRepository
    private val settings = container.settingsStore

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
                ) { accounts, _ ->
                    val withBalance = accounts.map { acc ->
                        AccountWithBalance(acc, txnRepo.getAccountBalance(acc))
                    }
                    AccountsUiState(
                        loading = false,
                        bookId = book.id,
                        bookName = book.name,
                        active = withBalance.filter { !it.account.archived },
                        archived = withBalance.filter { it.account.archived },
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

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { AccountsViewModel(container) }
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
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AccountEntity?>(null) }

    Scaffold(
        topBar = { LedgerTopBar(title = "账户 · ${state.bookName}") },
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

    if (showCreate) {
        AccountEditDialog(
            account = null,
            onSave = { acc ->
                vm.saveAccount(acc.copy(bookId = state.bookId))
                showCreate = false
            },
            onDismiss = { showCreate = false },
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
    val (typeLabel, icon) = ACCOUNT_TYPE_LABELS[item.account.type] ?: ("其他" to "📦")
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
            Text(icon, modifier = Modifier.size(40.dp).clip(CircleShape), style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.account.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$typeLabel · 启用 ${item.account.enableDate.formatDay()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                // 负余额红色展示（信用卡/负债场景）
                Text(
                    text = item.balance.toYuan() + " 元",
                    color = if (item.balance < 0) MaterialTheme.colorScheme.expense else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("当前余额", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
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

/** 新建/编辑账户对话框：名称、类型、期初余额、启用日期 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountEditDialog(
    account: AccountEntity?,
    onSave: (AccountEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(account?.name ?: "") }
    var type by remember { mutableStateOf(account?.type ?: AccountType.CASH) }
    var balanceText by remember {
        mutableStateOf(account?.let { (it.initialBalance / 100.0).let { v -> if (it.initialBalance % 100 == 0L) "${it.initialBalance / 100}" else "%.2f".format(v) } } ?: "")
    }
    var enableDate by remember { mutableStateOf(account?.enableDate ?: com.youshu.ledger.domain.util.todayMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (account == null) "新建账户" else "编辑账户") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("账户名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 类型下拉
                ExposedDropdownMenuBox(expanded = typeMenu, onExpandedChange = { typeMenu = it }) {
                    OutlinedTextField(
                        value = ACCOUNT_TYPE_LABELS[type]?.first ?: "其他",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("账户类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeMenu) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        AccountType.entries.forEach { t ->
                            val (label, icon) = ACCOUNT_TYPE_LABELS[t] ?: (t.name to "📦")
                            DropdownMenuItem(
                                text = { Text("$icon $label") },
                                onClick = { type = t; typeMenu = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = balanceText,
                    onValueChange = { balanceText = it },
                    label = { Text("期初余额（元，可为负）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { showDatePicker = true }) {
                    Text("启用日期：${enableDate.formatDay()}（余额从这天起算，之前为补录）")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val fen = balanceText.replace(",", "").toDoubleOrNull()?.let { (it * 100).toLong() } ?: 0L
                if (name.isBlank()) return@TextButton
                onSave(
                    (account ?: AccountEntity(
                        id = 0, bookId = 0, name = "", type = AccountType.CASH,
                        initialBalance = 0, enableDate = enableDate, sortOrder = 0, archived = false,
                    )).copy(
                        name = name.trim(),
                        type = type,
                        initialBalance = fen,
                        enableDate = enableDate,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showDatePicker) {
        DayPickerDialog(
            initialMillis = enableDate,
            onConfirm = { enableDate = it; showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }
}
