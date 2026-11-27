package com.youshu.ledger.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.youshu.ledger.data.local.entity.AccountEntity
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.TxnType
import com.youshu.ledger.domain.util.formatDay
import com.youshu.ledger.domain.util.formatMinuteOfDay
import com.youshu.ledger.domain.util.toYuan
import com.youshu.ledger.domain.util.weekDayCn
import com.youshu.ledger.ui.theme.expense
import com.youshu.ledger.ui.theme.income
import java.time.LocalDate

/** 流水列表项展示模型（流水页 / 单账户流水页共用） */
data class TxnDisplay(
    val id: Long,
    val type: TxnType,
    val amount: Long,
    val businessDate: Long,
    /** 当天分钟数（0~1439），-1 = 未记录（旧数据不显示时间） */
    val timeOfDay: Int = -1,
    val note: String,
    /** 项目名称（可空） */
    val project: String = "",
    val category: CategoryEntity?,
    /** 分类两级路径（「一级 · 二级」，无父级时仅分类名），账户流水行直接展示用 */
    val categoryPath: String = "",
    val accountName: String,
    val toAccountName: String?,
    val refundSum: Long,
    val attachmentCount: Int,
    /** 补录：生效时刻（业务日期+时间）早于账户启用时刻（转账不判定） */
    val backfill: Boolean,
)

/** 按天分组：一天内的流水列表 */
data class DayGroup(val date: LocalDate, val items: List<TxnDisplay>) {
    val dayIncome: Long get() = items.filter { it.type == TxnType.INCOME }.sumOf { it.amount }
    val dayExpense: Long get() = items.filter { it.type == TxnType.EXPENSE }.sumOf { it.amount }
}

@Composable
fun DayHeader(group: DayGroup) {
    Column {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = group.date.formatDay() + " " + group.date.weekDayCn(),
                    fontWeight = FontWeight.Medium,
                )
                if (group.date == LocalDate.now()) {
                    Text(
                        "今天",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (group.dayIncome > 0) {
                Text(
                    "+${group.dayIncome.toYuan()}  ",
                    color = MaterialTheme.colorScheme.income,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                )
            }
            if (group.dayExpense > 0) {
                Text(
                    "-${group.dayExpense.toYuan()}",
                    color = MaterialTheme.colorScheme.expense,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                )
            }
        }
    }
}

/**
 * 流水列表行（v1.6.4 起）：
 * 左侧分类图标；右侧从上到下——具体时间 / 项目名称+金额（同一行）/ 账户+分类路径+该账户余额 /
 * 部分备注（放不下省略号）+ 展开箭头；展开显示**完整备注**与「经过这步后」各账户余额（无说明文字）。
 * [balancesAfter] 为空（如单账户流水页）时不显示余额。
 */
@Composable
fun TxnRow(
    item: TxnDisplay,
    balancesAfter: Map<String, Long>? = null,
    onClick: () -> Unit,
) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // 顶部对齐：图标固定在展开前的位置，不随展开内容变高而移动
        Row(verticalAlignment = Alignment.Top) {
            CategoryBadge(item.category, size = 44.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // 具体时间
                if (item.timeOfDay >= 0) {
                    Text(
                        text = formatMinuteOfDay(item.timeOfDay),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                // 项目名称 + 金额（同一行，互不争抢）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when (item.type) {
                            TxnType.TRANSFER -> "${item.accountName} → ${item.toAccountName ?: ""}"
                            else -> item.project.ifBlank {
                                item.category?.name ?: when (item.type) {
                                    TxnType.EXPENSE -> "支出"; TxnType.INCOME -> "收入"; TxnType.TRANSFER -> "转账"
                                }
                            }
                        },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val sign = when (item.type) {
                        TxnType.EXPENSE -> "-"
                        TxnType.INCOME -> "+"
                        TxnType.TRANSFER -> "⇄"
                    }
                    val color = when (item.type) {
                        TxnType.EXPENSE -> MaterialTheme.colorScheme.expense
                        TxnType.INCOME -> MaterialTheme.colorScheme.income
                        TxnType.TRANSFER -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        text = "$sign${item.amount.toYuan()}",
                        style = MaterialTheme.typography.titleSmall,
                        color = color,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                // 账户 | 一级分类 · 二级分类 ＋ 该账户此刻余额
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val accountLine = when (item.type) {
                        TxnType.TRANSFER -> "${item.accountName} → ${item.toAccountName ?: ""}"
                        else -> {
                            val cat = item.categoryPath.ifBlank { item.category?.name ?: "" }
                            if (item.accountName.isBlank()) cat
                            else if (cat.isBlank()) item.accountName
                            else "${item.accountName} | $cat"
                        }
                    }
                    Text(
                        text = accountLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val afterBalance = if (item.type != TxnType.TRANSFER && item.accountName.isNotBlank()) {
                        balancesAfter?.get(item.accountName)
                    } else null
                    if (afterBalance != null) {
                        Text(
                            text = afterBalance.toYuan() + " 元",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                        )
                    }
                }
                // 部分备注（省略号截断；展开后隐藏，展开区显示完整备注）+ 展开箭头
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!expanded) {
                        Text(
                            text = item.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 2.dp),
                        )
                    } else {
                        // 展开时占位，保证箭头位置不动
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(22.dp),
                    ) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expanded) "收起" else "展开",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                // 展开：完整备注 + 各账户余额
                if (expanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                    ) {
                        if (item.note.isNotBlank()) {
                            Text(
                                text = item.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        balancesAfter?.forEach { (name, bal) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    bal.toYuan() + " 元",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (bal < 0) MaterialTheme.colorScheme.expense
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
