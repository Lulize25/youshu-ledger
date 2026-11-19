package com.youshu.ledger.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.domain.util.toEpochMillis
import com.youshu.ledger.domain.util.toLocalDate
import com.youshu.ledger.domain.util.toYuan
import com.youshu.ledger.domain.util.toYuanWithThousands
import kotlinx.coroutines.launch

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

/** 金额输入是否合法：仅允许数字、小数点、（可选）减号；最多两位小数 */
fun isValidMoneyText(text: String, allowNegative: Boolean): Boolean {
    if (text.isEmpty()) return true
    var t = text
    if (t.startsWith("-")) {
        if (!allowNegative) return false
        t = t.drop(1)
    }
    if (t.isEmpty()) return true            // 只有一个 "-"，尚未输完
    if (t.count { it == '.' } > 1) return false
    if (!t.all { it.isDigit() || it == '.' }) return false
    val decimals = t.substringAfter('.', "")
    return decimals.length <= 2 && t.replace(".", "").length <= 9
}

/** 金额文本（元）→ 分；空/不合法返回 null */
fun moneyTextToFen(text: String, allowNegative: Boolean): Long? {
    if (!isValidMoneyText(text, allowNegative)) return null
    val t = text.ifEmpty { return null }
    return t.toDoubleOrNull()?.let { (it * 100).toLong() }
}

/**
 * 金额输入对话框（应用内所有金额输入统一走这里）。
 * 输入框下方对非法字符（数字、小数点、减号之外的字符）给出红字提示；
 * 输入不合法或为空（且 [allowEmpty] = false）时禁用确定。
 */
@Composable
fun MoneyInputDialog(
    title: String,
    initialFen: Long = 0,
    allowNegative: Boolean = false,
    allowEmpty: Boolean = false,
    confirmText: String = "确定",
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember {
        mutableStateOf(
            if (initialFen == 0L) ""
            else if (initialFen % 100 == 0L) "${initialFen / 100}"
            else "%.2f".format(initialFen / 100.0),
        )
    }
    val valid = isValidMoneyText(text, allowNegative)
    val fen = moneyTextToFen(text, allowNegative)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("金额（元）") },
                    singleLine = true,
                    isError = !valid,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!valid) {
                    Text(
                        "只能输入数字、小数点${if (allowNegative) "和减号" else ""}",
                        color = Color(0xFFD32F2F),
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else if (text.startsWith("-")) {
                    Text(
                        "负值（负债/欠款场景）",
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && (fen != null || allowEmpty),
                onClick = { onConfirm(fen ?: 0L) },
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 日期时间一体选择对话框：同一个对话框里先选日期、再选时间，一次确定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerDialog(
    initialMillis: Long,
    title: String = "选择日期时间",
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val dayStart = initialMillis.toLocalDate().toEpochMillis()
    val minuteOfDay = ((initialMillis - dayStart) / 60_000L).toInt().coerceIn(0, 1439)
    val dateState = rememberDatePickerState(initialSelectedDateMillis = dayStart)
    val timeState = rememberTimePickerState(
        initialHour = minuteOfDay / 60,
        initialMinute = minuteOfDay % 60,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                DatePicker(state = dateState)
                TimePicker(state = timeState)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val day = dateState.selectedDateMillis ?: return@TextButton
                onConfirm(day + (timeState.hour * 60 + timeState.minute) * 60_000L)
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 账本切换栏：放在每个主页面顶部（标题下方）。
 * 显示当前账本，点击下拉切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSwitcherBar(
    container: com.youshu.ledger.data.AppContainer,
    modifier: Modifier = Modifier,
) {
    val books by container.bookRepository.observeAll()
        .collectAsState(emptyList())
    val current by container.bookRepository.currentBook
        .collectAsState(null)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "账本：${current?.name ?: "…"}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Box {
            TextButton(onClick = { menu = true }) { Text("切换") }
            androidx.compose.material3.DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
            ) {
                books.forEach { book ->
                    DropdownMenuItem(
                        text = {
                            Text((if (book.id == current?.id) "✓ " else "") + book.name)
                        },
                        onClick = {
                            menu = false
                            scope.launch { container.bookRepository.switchTo(book.id) }
                        },
                    )
                }
            }
        }
    }
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
            // 小数点：只允许一个；空文本时补 0；非空直接追加（修复：此前非空时会被静默丢弃，
            // 导致输入「5.」同步到 ViewModel 后变成「5」，保存金额被放大 10 倍）
            c == '.' -> when {
                text.contains('.') -> Unit
                text.isEmpty() -> text = "0."
                else -> text += "."
            }
            c.isDigit() -> {
                val decimals = text.substringAfter('.', "")
                if (decimals.length < 2 && text.replace(".", "").replace("-", "").length < 9) {
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
