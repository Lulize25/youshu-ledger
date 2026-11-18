package com.youshu.ledger.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.youshu.ledger.domain.util.toYuan
import com.youshu.ledger.ui.theme.expense
import kotlin.math.max

/** 分类饼图（Canvas 绘制）+ 图例 */
@Composable
fun PieChartWithLegend(slices: List<CategorySlice>, modifier: Modifier = Modifier) {
    if (slices.isEmpty()) {
        Box(modifier = modifier.height(160.dp), contentAlignment = Alignment.Center) {
            Text("本周期暂无支出", color = MaterialTheme.colorScheme.outline)
        }
        return
    }
    val total = slices.sumOf { it.amount }.coerceAtLeast(1)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(140.dp)) {
            var startAngle = -90f
            slices.forEach { slice ->
                val sweep = (slice.amount.toFloat() / total) * 360f
                drawArc(
                    color = slice.color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = true,
                    size = Size(size.width, size.height),
                )
                startAngle += sweep
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            slices.take(8).forEach { slice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).background(slice.color, CircleShape))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${slice.name}  ${(slice.amount * 100 / total)}%",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(slice.amount.toYuan(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** 收支趋势柱状图：每桶两根柱（收入/支出） */
@Composable
fun TrendBars(points: List<TrendPoint>, modifier: Modifier = Modifier) {
    if (points.isEmpty()) return
    val maxValue = points.maxOf { max(it.income, it.expense) }.coerceAtLeast(1)
    val showLabels = points.size <= 31

    Column(modifier = modifier.height(140.dp)) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val barGroupWidth = size.width / points.size
            val barWidth = barGroupWidth * 0.32f
            points.forEachIndexed { index, p ->
                val cx = barGroupWidth * index + barGroupWidth / 2
                val incomeH = size.height * (p.income.toFloat() / maxValue)
                val expenseH = size.height * (p.expense.toFloat() / maxValue)
                // 收入柱（左）
                drawRect(
                    color = Color(0xFF43A047),
                    topLeft = Offset(cx - barWidth - 1f, size.height - incomeH),
                    size = Size(barWidth, incomeH),
                )
                // 支出柱（右）
                drawRect(
                    color = Color(0xFFE53935),
                    topLeft = Offset(cx + 1f, size.height - expenseH),
                    size = Size(barWidth, expenseH),
                )
            }
        }
        if (showLabels) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                points.forEach { p ->
                    Text(
                        text = p.label,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 预算进度条（超支红色） */
@Composable
fun BudgetProgressBar(progress: BudgetProgress, modifier: Modifier = Modifier) {
    val color = when {
        progress.overBudget -> MaterialTheme.colorScheme.expense
        progress.ratio > 0.85f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(progress.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                text = "${progress.used.toYuan()} / ${progress.budget.toYuan()}",
                style = MaterialTheme.typography.bodySmall,
                color = if (progress.overBudget) MaterialTheme.colorScheme.expense else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (progress.overBudget) FontWeight.Bold else FontWeight.Normal,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        androidx.compose.material3.LinearProgressIndicator(
            progress = { progress.ratio.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = color,
        )
        if (progress.overBudget) {
            Text(
                "已超支 ${(progress.used - progress.budget).toYuan()} 元",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.expense,
            )
        }
    }
}
