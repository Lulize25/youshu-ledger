package com.youshu.ledger.domain.util

import java.text.DecimalFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * 金额与日期的公共工具。
 * 金额内部一律为 Long「分」；展示为「元」保留两位小数。
 */

private val MONEY_FMT = DecimalFormat("0.00")

/** 分 → 元字符串（如 12345 → "123.45"） */
fun Long.toYuan(): String = MONEY_FMT.format(this / 100.0)

/** 元字符串 → 分（解析失败返回 null） */
fun String.yuanToFen(): Long? =
    replace(",", "").toDoubleOrNull()?.let { (it * 100).toLong() }

/** 元(double) → 分 */
fun Double.yuanToFen(): Long = (this * 100).toLong()

/** 分 → 带千分位展示的元 */
fun Long.toYuanWithThousands(): String =
    DecimalFormat("#,##0.00").format(this / 100.0)

private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val MONTH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val ZONE: ZoneId = ZoneId.systemDefault()

/** LocalDate → 当天 00:00 的 epoch 毫秒 */
fun LocalDate.toEpochMillis(): Long = atStartOfDay(ZONE).toInstant().toEpochMilli()

/** epoch 毫秒（当天 00:00）→ LocalDate */
fun Long.toLocalDate(): LocalDate =
    java.time.Instant.ofEpochMilli(this).atZone(ZONE).toLocalDate()

fun LocalDate.formatDay(): String = format(DAY_FMT)
fun LocalDate.formatMonth(): String = format(MONTH_FMT)
fun Long.formatTime(): String = java.time.Instant.ofEpochMilli(this).atZone(ZONE).format(TIME_FMT)
fun Long.formatDay(): String = toLocalDate().formatDay()

/** 当前时间当天 00:00 的 epoch 毫秒 */
fun todayMillis(): Long = LocalDate.now().toEpochMillis()

/** 当前时间的当天分钟数（0~1439） */
fun currentMinuteOfDay(): Int {
    val t = java.time.LocalTime.now()
    return t.hour * 60 + t.minute
}

/** 当天分钟数（0~1439）→ "HH:mm"；负数（未记录）返回空串 */
fun formatMinuteOfDay(minuteOfDay: Int): String =
    if (minuteOfDay < 0) "" else "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/** 将「yyyy-MM」与「yyyy-MM-dd」解析为当天 00:00 毫秒 */
fun parseDayMillis(text: String): Long? = runCatching {
    LocalDate.parse(text, DAY_FMT).toEpochMillis()
}.getOrNull()

fun currentYearMonth(): String = LocalDate.now().formatMonth()

/** 中文星期几 */
fun LocalDate.weekDayCn(): String =
    "周" + listOf("一", "二", "三", "四", "五", "六", "日")[dayOfWeek.value - 1]

/** Locale，保证金额格式稳定 */
val CN_LOCALE: Locale = Locale.CHINA
