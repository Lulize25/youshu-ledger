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
import com.youshu.ledger.ui.common.BookTopBar
import com.youshu.ledger.ui.common.CategoryBadge
import com.youshu.ledger.ui.common.DateTimeButtonsRow
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
            // 顶栏只保留账本切换，右侧筛选按钮
            BookTopBar(
                container = container,
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
            // 汇总条（压缩为一行小字；数字与标签同字号同基线）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("收入 ", style = MaterialTheme.typography.bodySmall)
                MoneyText(
                    state.totalIncome,
                    color = MaterialTheme.colorScheme.income,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("支出 ", style = MaterialTheme.typography.bodySmall)
                MoneyText(
                    state.totalExpense,
                    color = MaterialTheme.colorScheme.expense,
                    style = MaterialTheme.typography.bodySmall,
                )
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
                                balancesAfter = state.balancesAfter[item.id],
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
            // 时间区间（起始/截止各自精确到分钟；未设置显示「不限」，可单独清除）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("起始", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.width(8.dp))
                DateTimeButtonsRow(
                    millis = f.startDate ?: System.currentTimeMillis(),
                    onMillisChange = { onFilters(f.copy(startDate = it)) },
                    modifier = Modifier.weight(1f),
                    spacing = 6.dp,
                    datePickerTitle = "起始日期",
                    timePickerTitle = "起始时刻",
                    dateText = if (f.startDate == null) "不限" else null,
                    timeText = if (f.startDate == null) "不限" else null,
                )
                if (f.startDate != null) {
                    IconButton(onClick = { onFilters(f.copy(startDate = null)) }) {
                        Icon(Icons.Default.Close, contentDescription = "清除起始时间")
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("截止", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.width(8.dp))
                DateTimeButtonsRow(
                    millis = f.endDate ?: System.currentTimeMillis(),
                    onMillisChange = { onFilters(f.copy(endDate = it)) },
                    modifier = Modifier.weight(1f),
                    spacing = 6.dp,
                    datePickerTitle = "截止日期",
                    timePickerTitle = "截止时刻",
                    dateText = if (f.endDate == null) "不限" else null,
                    timeText = if (f.endDate == null) "不限" else null,
                )
                if (f.endDate != null) {
                    IconButton(onClick = { onFilters(f.copy(endDate = null)) }) {
                        Icon(Icons.Default.Close, contentDescription = "清除截止时间")
                    }
                }
            }
            if (f.active) {
                TextButton(onClick = { onFilters(TxnFilters()); onClose() }) { Text("清除筛选") }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
