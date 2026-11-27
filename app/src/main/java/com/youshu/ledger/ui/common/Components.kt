package com.youshu.ledger.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.domain.util.formatDay
import com.youshu.ledger.domain.util.formatMinuteOfDay
import com.youshu.ledger.domain.util.toEpochMillis
import com.youshu.ledger.domain.util.toLocalDate
import com.youshu.ledger.domain.util.toYuan
import com.youshu.ledger.domain.util.toYuanWithThousands
import kotlinx.coroutines.launch

/**
 * 金额文本（分 → 元，两位小数）。
 * [style] 传入时完全沿用该样式（用于与同行标签同字号，保证基线对齐）。
 */
@Composable
fun MoneyText(
    fen: Long,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    big: Boolean = false,
    style: TextStyle? = null,
) {
    if (style != null) {
        Text(
            text = if (big) fen.toYuanWithThousands() else fen.toYuan(),
            modifier = modifier,
            color = color,
            style = style,
        )
    } else {
        Text(
            text = if (big) fen.toYuanWithThousands() else fen.toYuan(),
            modifier = modifier,
            color = color,
            fontWeight = if (big) FontWeight.Bold else FontWeight.Normal,
            fontSize = if (big) 22.sp else 14.sp,
        )
    }
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

/** 纯时间（当天分钟数）选择对话框：表盘式 TimePicker，不滚动 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinutePickerDialog(
    title: String,
    initialMinuteOfDay: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = (initialMinuteOfDay / 60).coerceIn(0, 23),
        initialMinute = (initialMinuteOfDay % 60).coerceIn(0, 59),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 日期时间两栏选择（全应用统一的日期时间入口）：左「日历」选日期、右「时钟」选当天时刻，
 * 各自弹独立对话框（日历 / 表盘）。改日期保留原时刻，改时刻保留原日期。
 *
 * @param dateText/timeText 覆盖按钮文字（如筛选未设置时显示「不限」）；null 则按 millis 格式化
 */
@Composable
fun DateTimeButtonsRow(
    millis: Long,
    onMillisChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    datePickerTitle: String = "选择日期",
    timePickerTitle: String = "选择时间",
    dateText: String? = null,
    timeText: String? = null,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val dayStart = millis.toLocalDate().toEpochMillis()
    val minuteOfDay = ((millis - dayStart) / 60_000L).toInt().coerceIn(0, 1439)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(spacing)) {
        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.DateRange, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = dateText ?: millis.formatDay(),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Schedule, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = timeText ?: formatMinuteOfDay(minuteOfDay),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (showDatePicker) {
        DayPickerDialog(
            initialMillis = millis,
            onConfirm = { dayMillis ->
                onMillisChange(dayMillis + minuteOfDay * 60_000L)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
    if (showTimePicker) {
        MinutePickerDialog(
            title = timePickerTitle,
            initialMinuteOfDay = minuteOfDay,
            onConfirm = { min ->
                onMillisChange(dayStart + min * 60_000L)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

/** 账本切换行（无外边距，供 BookSwitcherBar / BookTopBar 复用） */
@Composable
private fun BookSwitcherRow(
    container: com.youshu.ledger.data.AppContainer,
    style: androidx.compose.ui.text.TextStyle,
) {
    val books by container.bookRepository.observeAll()
        .collectAsState(emptyList())
    val current by container.bookRepository.currentBook
        .collectAsState(null)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "账本：${current?.name ?: "…"}",
            style = style,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Box {
            TextButton(onClick = { menu = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { Text("切换") }
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

/**
 * 账本切换栏（带外边距的独立行，适合放在内容区顶部）。
 * [large] = true 时使用更大字号。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSwitcherBar(
    container: com.youshu.ledger.data.AppContainer,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (large) 6.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookSwitcherRow(
            container,
            if (large) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 主页面顶栏：只保留账本切换行（无页面标题），右侧可放操作按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookTopBar(
    container: com.youshu.ledger.data.AppContainer,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = {
            Column(
                modifier = Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                BookSwitcherRow(container, MaterialTheme.typography.titleMedium)
            }
        },
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
    // M3 DatePicker 的 selectedDateMillis 是 UTC 语义：本地日期需先转成 UTC 当天 00:00，
    // 否则东八区会显示/存回前一天
    val utcMillis = initialMillis.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = utcMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { utc ->
                    onConfirm(
                        java.time.Instant.ofEpochMilli(utc).atZone(java.time.ZoneOffset.UTC)
                            .toLocalDate().toEpochMillis(),
                    )
                }
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

    /**
     * 文本框直输（v1.6 起金额改为系统键盘文本框）：仅接受合法金额文本，
     * 非法字符 / 多个小数点 / 超过两位小数 / 超过 9 位数字的输入直接忽略，
     * 输入框因此永远合法，无需额外的红字校验。
     */
    fun setText(newText: String) {
        val t = newText
        if (t.isEmpty()) {
            text = ""
            return
        }
        if (t.count { it == '.' } > 1) return
        if (!t.all { it.isDigit() || it == '.' }) return
        val decimals = t.substringAfter('.', "")
        if (decimals.length > 2) return
        if (t.replace(".", "").length > 9) return
        text = t
        // 去掉前导零（保留 0.x）
        if (text.length > 1 && text.startsWith("0") && !text.startsWith("0.")) {
            text = text.trimStart('0').ifEmpty { "0" }
        }
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
