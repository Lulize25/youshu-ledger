package com.youshu.ledger.ui.txns

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.youshu.ledger.data.AppContainer
import com.youshu.ledger.data.local.entity.TxnType
import com.youshu.ledger.domain.util.formatTime
import com.youshu.ledger.domain.util.weekDayCn
import com.youshu.ledger.ui.common.BookSwitcherBar
import com.youshu.ledger.ui.common.CategoryBadge
import com.youshu.ledger.ui.common.DateTimePickerDialog
import com.youshu.ledger.ui.common.DayHeader
import com.youshu.ledger.ui.common.TxnDisplay
import com.youshu.ledger.ui.common.TxnRow
import com.youshu.ledger.ui.common.ConfirmDialog
import com.youshu.ledger.ui.common.LedgerTopBar
import com.youshu.ledger.ui.common.MoneyText
import com.youshu.ledger.ui.theme.expense
import com.youshu.ledger.ui.theme.income
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 流水页：按天分组（每天显示当日收支合计）、筛选（账本/账户/分类/类型）、
 * 备注搜索、点击进入编辑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxnsScreen(
    container: AppContainer,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
) {
    val vm: TxnListViewModel = viewModel(factory = TxnListViewModel.factory(container))
    val state by vm.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showFilters by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<TxnDisplay?>(null) }

    Scaffold(
        topBar = {
            LedgerTopBar(
                title = "流水",
                actions = {
                    IconButton(onClick = { showFilters = !showFilters }) {
                        Icon(Icons.Default.Search, contentDescription = "筛选")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // 账本切换
            BookSwitcherBar(
                container = container,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            // 汇总条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("收入 ", style = MaterialTheme.typography.bodySmall)
                MoneyText(state.totalIncome, color = MaterialTheme.colorScheme.income)
                Text("支出 ", style = MaterialTheme.typography.bodySmall)
                MoneyText(state.totalExpense, color = MaterialTheme.colorScheme.expense)
            }

            if (showFilters || state.filters.active) {
                FilterBar(
                    state = state,
                    onFilters = { vm.updateFilters(it) },
                    onClose = { showFilters = false },
                )
            }

            if (state.groups.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无流水，点击右下角「记一笔」开始记账", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    state.groups.forEach { group ->
                        item(key = "header_${group.date}") {
                            DayHeader(group)
                        }
                        items(group.items, key = { it.id }) { item ->
                            TxnRow(
                                item = item,
                                onClick = { onEdit(item.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        ConfirmDialog(
            title = "删除流水",
            text = if (item.backfill) "这是一笔补录流水，删除不会影响当前余额。确定删除？"
            else "删除后余额将自动回滚。确定删除？",
            confirmText = "删除",
            onConfirm = {
                vm.deleteTxn(item) { scope.launch { snackbar.showSnackbar(it) } }
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    state: TxnListUiState,
    onFilters: (TxnFilters) -> Unit,
    onClose: () -> Unit,
) {
    var f = state.filters
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = f.keyword,
            onValueChange = { onFilters(f.copy(keyword = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索备注") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (f.keyword.isNotEmpty()) {
                    IconButton(onClick = { onFilters(f.copy(keyword = "")) }) {
                        Icon(Icons.Default.Close, contentDescription = "清空")
                    }
                }
            },
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 类型筛选
            FilterChip(
                selected = f.type != null,
                onClick = {
                    val next = when (f.type) {
                        null -> TxnType.EXPENSE
                        TxnType.EXPENSE -> TxnType.INCOME
                        TxnType.INCOME -> TxnType.TRANSFER
                        TxnType.TRANSFER -> null
                    }
                    onFilters(f.copy(type = next))
                },
                label = {
                    Text(
                        when (f.type) {
                            null -> "类型"; TxnType.EXPENSE -> "支出"; TxnType.INCOME -> "收入"; TxnType.TRANSFER -> "转账"
                        },
                    )
                },
            )
            // 账户筛选（简化：循环切换）——使用下拉
            var accountMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = accountMenu, onExpandedChange = { accountMenu = it }) {
                FilterChip(
                    selected = f.accountId != null,
                    onClick = { accountMenu = true },
                    label = {
                        Text(
                            f.accountId?.let { id -> state.accounts.firstOrNull { a -> a.id == id }?.name } ?: "账户",
                        )
                    },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(accountMenu) },
                    modifier = Modifier.menuAnchor(),
                )
                ExposedDropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("全部账户") },
                        onClick = { onFilters(f.copy(accountId = null)); accountMenu = false },
                    )
                    state.accounts.filter { !it.archived }.forEach { acc ->
                        DropdownMenuItem(
                            text = { Text(acc.name) },
                            onClick = { onFilters(f.copy(accountId = acc.id)); accountMenu = false },
                        )
                    }
                }
            }
            // 分类筛选
            var categoryMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = categoryMenu, onExpandedChange = { categoryMenu = it }) {
                FilterChip(
                    selected = f.categoryId != null,
                    onClick = { categoryMenu = true },
                    label = {
                        Text(
                            f.categoryId?.let { id -> state.categories.firstOrNull { c -> c.id == id }?.name } ?: "分类",
                        )
                    },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryMenu) },
                    modifier = Modifier.menuAnchor(),
                )
                ExposedDropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("全部分类") },
                        onClick = { onFilters(f.copy(categoryId = null)); categoryMenu = false },
                    )
                    state.categories.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(cat.name) },
                            onClick = { onFilters(f.copy(categoryId = cat.id)); categoryMenu = false },
                        )
                    }
                }
            }
            // 时间区间（起始/截止各自精确到分钟）
            var dateTarget by remember { mutableStateOf<String?>(null) }
            FilterChip(
                selected = f.startDate != null,
                onClick = { dateTarget = "start" },
                label = {
                    Text(
                        "起始 " + (f.startDate?.formatTime() ?: "不限"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
            )
            FilterChip(
                selected = f.endDate != null,
                onClick = { dateTarget = "end" },
                label = {
                    Text(
                        "截止 " + (f.endDate?.formatTime() ?: "不限"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
            )
            if (f.active) {
                TextButton(onClick = { onFilters(TxnFilters()); onClose() }) { Text("清除筛选") }
            }
            if (dateTarget != null) {
                DateTimePickerDialog(
                    initialMillis = (if (dateTarget == "start") f.startDate else f.endDate) ?: System.currentTimeMillis(),
                    title = if (dateTarget == "start") "起始时间" else "截止时间",
                    onConfirm = { millis ->
                        onFilters(if (dateTarget == "start") f.copy(startDate = millis) else f.copy(endDate = millis))
                        dateTarget = null
                    },
                    onDismiss = { dateTarget = null },
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
