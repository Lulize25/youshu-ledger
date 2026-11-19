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
    val accountName: String,
    val toAccountName: String?,
    val refundSum: Long,
    val reimburseSum: Long,
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
 * 流水列表行：
 * 顶上显示具体时间；主行 = 分类图标 + 名称（项目名称优先，其次分类名，转账显示「A → B」）+ 金额；
 * 小字通栏只显示备注；点箭头展开：分类、涉及账户、退款/报销/附件摘要，
 * 以及「经过这一步之后」**全部账户**的余额（[balancesAfter] 提供时）。
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
        // 顶上：具体时间
        if (item.timeOfDay >= 0) {
            Text(
                text = formatMinuteOfDay(item.timeOfDay),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryBadge(item.category)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = when (item.type) {
                    TxnType.TRANSFER -> "${item.accountName} → ${item.toAccountName ?: ""}"
                    else -> item.project.ifBlank {
                        item.category?.name ?: when (item.type) {
                            TxnType.EXPENSE -> "支出"; TxnType.INCOME -> "收入"; TxnType.TRANSFER -> "转账"
                        }
                    }
                },
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
                color = color,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        // 小字：整行通栏，只显示备注；右侧展开箭头
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size(22.dp),
            ) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起详情" else "展开详情",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        // 展开区：分类 / 涉及账户 / 退款报销附件摘要 + 全部账户余额
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                if (item.type != TxnType.TRANSFER && item.category != null) {
                    TxnDetailLine("分类", item.category.name)
                }
                TxnDetailLine(
                    if (item.type == TxnType.TRANSFER) "转出 → 转入" else "账户",
                    when (item.type) {
                        TxnType.TRANSFER -> "${item.accountName} → ${item.toAccountName ?: ""}"
                        else -> item.accountName
                    },
                )
                val extra = buildList {
                    if (item.refundSum > 0) add("退款合计 ${item.refundSum.toYuan()} 元")
                    if (item.reimburseSum > 0) add("报销合计 ${item.reimburseSum.toYuan()} 元")
                    if (item.attachmentCount > 0) add("${item.attachmentCount} 张附件")
                }
                extra.forEach { TxnDetailLine("其他", it) }
                if (!balancesAfter.isNullOrEmpty()) {
                    Text(
                        "经过这步后各账户余额",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                    balancesAfter.forEach { (name, bal) ->
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

/** 展开区的一行「标签：内容」 */
@Composable
private fun TxnDetailLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
