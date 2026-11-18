package com.youshu.ledger.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.domain.util.toYuan
import com.youshu.ledger.domain.util.toYuanWithThousands

/** 金额文本（分 → 元，两位小数） */
@Composable
fun MoneyText(fen: Long, modifier: Modifier = Modifier, color: Color = Color.Unspecified, big: Boolean = false) {
    Text(
        text = if (big) fen.toYuanWithThousands() else fen.toYuan(),
        modifier = modifier,
        color = color,
        fontWeight = if (big) FontWeight.Bold else FontWeight.Normal,
        fontSize = if (big) 22.sp else 14.sp,
    )
}

/** 分类图标：圆形底色 + 圆内 1~2 个文字（需求规定的图标方案） */
@Composable
fun CategoryBadge(category: CategoryEntity?, size: Dp = 36.dp) {
    val bg = category?.let { Color(it.color) } ?: MaterialTheme.colorScheme.surfaceVariant
    val fg = Color.White
    Box(
        modifier = Modifier
            .size(size)
            .background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = category?.iconText?.take(2) ?: "?",
            color = fg,
            fontSize = (size.value * 0.3).sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        },
        actions = { actions() },
    )
}

/** 通用确认对话框 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "确定",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 日期选择对话框（material3 DatePicker 封装） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayPickerDialog(
    initialMillis: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let(onConfirm)
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DatePicker(state = state)
    }
}

/**
 * 金额键盘（记账风格数字键盘）。
 * 维护字符串输入：最多一位小数点、最多两位小数；返回格式化后的「分」。
 */
class AmountInputState(initial: String = "") {
    var text: String = initial
        private set

    fun append(c: Char) {
        when {
            c == '.' && text.contains('.') -> Unit // 只允许一个小数点
            c == '.' && text.isEmpty() -> text = "0."
            c.isDigit() -> {
                val decimals = text.substringAfter('.', "")
                if (decimals.length < 2 && text.replace(".", "").length < 9) {
                    text += c
                }
            }
        }
        // 去掉前导零（保留 0.x）
        if (text.length > 1 && text.startsWith("0") && !text.startsWith("0.")) {
            text = text.trimStart('0').ifEmpty { "0" }
        }
    }

    fun backspace() {
        text = text.dropLast(1)
    }

    fun clear() {
        text = ""
    }

    /** 当前输入金额（分），空或非法返回 null */
    fun toFen(): Long? =
        text.takeIf { it.isNotBlank() && it != "." }?.toDoubleOrNull()?.let { (it * 100).toLong() }
}

@Composable
fun AmountPad(state: AmountInputState, modifier: Modifier = Modifier) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "⌫"),
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { key ->
                    androidx.compose.material3.FilledTonalButton(
                        onClick = {
                            if (key == "⌫") state.backspace() else state.append(key.first())
                        },
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.8f),
                    ) {
                        Text(key, fontSize = 20.sp)
                    }
                }
            }
        }
    }
}
