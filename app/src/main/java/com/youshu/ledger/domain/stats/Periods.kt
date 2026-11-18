package com.youshu.ledger.domain.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 统计周期类型：日 / 周 / 月 / 年 */
enum class PeriodType(val label: String) {
    DAY("日"), WEEK("周"), MONTH("月"), YEAR("年"),
}

/** 周期范围（闭区间，均为本地日期） */
data class PeriodRange(val start: LocalDate, val end: LocalDate) {
    fun shift(amount: Long, type: PeriodType): PeriodRange {
        val newAnchor = when (type) {
            PeriodType.DAY -> start.plusDays(amount)
            PeriodType.WEEK -> start.plusWeeks(amount)
            PeriodType.MONTH -> start.plusMonths(amount)
            PeriodType.YEAR -> start.plusYears(amount)
        }
        return periodRange(newAnchor, type)
    }

    /** 范围标题，如 2026-11、2026 年 */
    fun title(): String = when {
        start == end -> start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        start.year == end.year && start.month == end.month -> start.format(DateTimeFormatter.ofPattern("yyyy-MM"))
        start.year == end.year -> "${start.year} 年"
        else -> "${start.year}-${end.year}"
    }

    /** 趋势图分桶标签：日→单点；周/月→天；年→月 */
    fun bucketLabels(): List<String> = when {
        start == end -> listOf(start.format(DateTimeFormatter.ofPattern("MM-dd")))
        start.year == end.year && start.month == end.month ->
            generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }
                .map { it.dayOfMonth.toString() }.toList()

        else -> (1..12).map { "${it}月" }
    }
}

/** 计算锚点日期所在周期（周按周一起算） */
fun periodRange(anchor: LocalDate, type: PeriodType): PeriodRange = when (type) {
    PeriodType.DAY -> PeriodRange(anchor, anchor)
    PeriodType.WEEK -> {
        val monday = anchor.with(DayOfWeek.MONDAY)
        PeriodRange(monday, monday.plusDays(6))
    }

    PeriodType.MONTH -> PeriodRange(
        anchor.withDayOfMonth(1),
        anchor.withDayOfMonth(anchor.lengthOfMonth()),
    )

    PeriodType.YEAR -> PeriodRange(
        LocalDate.of(anchor.year, 1, 1),
        LocalDate.of(anchor.year, 12, 31),
    )
}
