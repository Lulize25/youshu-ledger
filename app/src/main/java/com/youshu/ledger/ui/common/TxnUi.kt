package com.youshu.ledger.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

@Composable
fun TxnRow(item: TxnDisplay, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(item.category)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when (item.type) {
                    TxnType.EXPENSE -> item.category?.name ?: "支出"
                    TxnType.INCOME -> item.category?.name ?: "收入"
                    TxnType.TRANSFER -> "${item.accountName} → ${item.toAccountName ?: ""}"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildString {
                if (item.timeOfDay >= 0) append(formatMinuteOfDay(item.timeOfDay) + " · ")
                if (item.type == TxnType.TRANSFER) append("转账")
                else append(item.accountName)
                if (item.project.isNotBlank()) append(" · ${item.project}")
                if (item.note.isNotBlank()) append(" · ${item.note}")
                if (item.backfill) append(" · 补录")
                if (item.refundSum > 0) append(" · 退${item.refundSum.toYuan()}")
                if (item.reimburseSum > 0) append(" · 报${item.reimburseSum.toYuan()}")
                if (item.attachmentCount > 0) append(" · ${item.attachmentCount}张附件")
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
        )
    }
}
