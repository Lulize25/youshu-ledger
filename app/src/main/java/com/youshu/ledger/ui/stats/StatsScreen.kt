package com.youshu.ledger.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.youshu.ledger.data.AppContainer
import com.youshu.ledger.domain.stats.PeriodType
import com.youshu.ledger.domain.util.toYuan
import com.youshu.ledger.ui.common.BookTopBar
import com.youshu.ledger.ui.common.LedgerTopBar
import com.youshu.ledger.ui.theme.expense
import com.youshu.ledger.ui.theme.income

/**
 * 统计页：周期切换（日/周/月/年）、收支概览卡、分类饼图、收支趋势柱状图、
 * 预算进度条（超支红色）、应收总额入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(container: AppContainer) {
    val vm: StatsViewModel = viewModel(factory = StatsViewModel.factory(container))
    val s by vm.uiState.collectAsState()
    var showBudgetDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            // 一根顶栏：最顶部是账本切换（大字），下方是页面标题
            BookTopBar(container = container, title = "统计")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ---- 周期切换 ----
            SingleChoiceSegmentedButtonRow(modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)) {
                PeriodType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = s.periodType == type,
                        onClick = { vm.setPeriodType(type); vm.resetToNow() },
                        shape = SegmentedButtonDefaults.itemShape(index, PeriodType.entries.size),
                    ) { Text(type.label) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(onClick = { vm.shiftPeriod(-1) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一周期")
                }
                Text(
                    text = s.range.title(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                IconButton(onClick = { vm.shiftPeriod(1) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下一周期")
                }
            }

            // ---- 收支概览卡 ----
            Card(modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OverviewRow("收入合计", s.income, MaterialTheme.colorScheme.income)
                    OverviewRow("支出总额", s.expenseGross, MaterialTheme.colorScheme.expense)
                    if (s.refundSum > 0 || s.reimburseSum > 0) {
                        OverviewRow(
                            "　− 退款 / 报销",
                            -(s.refundSum + s.reimburseSum),
                            MaterialTheme.colorScheme.outline,
                        )
                    }
                    OverviewRow("净支出", s.netExpense, MaterialTheme.colorScheme.onSurface)
                    if (s.advanceSum > 0) {
                        OverviewRow("　− 代付", -s.advanceSum, MaterialTheme.colorScheme.outline)
                    }
                    OverviewRow("个人实际支出", s.personalExpense, MaterialTheme.colorScheme.onSurface)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    OverviewRow("结余（收入 − 净支出）", s.balance, MaterialTheme.colorScheme.primary)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { /* 应收明细可后续扩展 */ },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("应收（别人欠我）", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            s.receivableTotal.toYuan() + " 元",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (s.receivableTotal > 0) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // ---- 分类饼图 ----
            Card(modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("支出分类构成", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    PieChartWithLegend(slices = s.pieSlices)
                }
            }

            // ---- 收支趋势 ----
            Card(modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("收支趋势（绿=收入 红=支出）", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    TrendBars(points = s.trend)
                }
            }

            // ---- 预算 ----
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("预算（${s.monthKey}）", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = { showBudgetDialog = true }) {
                            Text(if (s.hasBudget) "调整预算" else "设置预算")
                        }
                    }
                    val totalBudget = s.totalBudget
                    if (totalBudget != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        BudgetProgressBar(progress = totalBudget)
                    }
                    s.categoryBudgets.forEach { progress ->
                        Spacer(modifier = Modifier.height(10.dp))
                        BudgetProgressBar(progress = progress)
                    }
                    if (!s.hasBudget) {
                        Text(
                            "未设置预算。可设置月度总预算或分类预算，超支将红色提示。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showBudgetDialog) {
        BudgetEditDialog(
            totalBudget = s.totalBudget?.budget,
            categoryBudgets = s.categoryBudgets,
            onSave = { total, categoryUpdates ->
                vm.setTotalBudget(total)
                categoryUpdates.forEach { (categoryId, amount) ->
                    vm.setCategoryBudget(categoryId, amount)
                }
                showBudgetDialog = false
            },
            onDismiss = { showBudgetDialog = false },
        )
    }
}

@Composable
private fun OverviewRow(label: String, amount: Long, color: androidx.compose.ui.graphics.Color) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = (if (amount < 0) "" else "") + amount.toYuan(),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 预算编辑对话框：月度总预算 + 分类预算（留空表示不设置/清除） */
@Composable
private fun BudgetEditDialog(
    totalBudget: Long?,
    categoryBudgets: List<BudgetProgress>,
    onSave: (totalFen: Long?, categoryUpdates: List<Pair<Long, Long?>>) -> Unit,
    onDismiss: () -> Unit,
) {
    var totalText by remember {
        mutableStateOf(
            totalBudget?.let {
                if (it % 100 == 0L) "${it / 100}" else "%.2f".format(it / 100.0)
            } ?: "",
        )
    }
    val categoryTexts = remember {
        mutableMapOf<Long, String>().apply {
            categoryBudgets.forEach { put(it.categoryId ?: 0L, if (it.budget % 100 == 0L) "${it.budget / 100}" else "%.2f".format(it.budget / 100.0)) }
        }
    }

    fun textToFen(text: String): Long? =
        text.takeIf { it.isNotBlank() }?.replace(",", "")?.toDoubleOrNull()?.let { (it * 100).toLong() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置预算") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = totalText,
                    onValueChange = { totalText = it },
                    label = { Text("月度总预算（元，留空为不设置）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (categoryBudgets.isNotEmpty()) {
                    Text("分类预算", style = MaterialTheme.typography.labelLarge)
                    categoryBudgets.forEach { progress ->
                        OutlinedTextField(
                            value = categoryTexts[progress.categoryId] ?: "",
                            onValueChange = { categoryTexts[progress.categoryId ?: 0L] = it },
                            label = { Text("${progress.name}（元）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Text(
                        "暂无分类预算。已设置过预算的分类会显示在这里，可修改或留空清除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val updates = categoryBudgets.mapNotNull { progress ->
                    val id = progress.categoryId ?: return@mapNotNull null
                    id to textToFen(categoryTexts[id] ?: "")
                }
                onSave(textToFen(totalText), updates)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
