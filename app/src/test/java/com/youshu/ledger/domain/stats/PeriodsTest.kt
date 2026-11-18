package com.youshu.ledger.domain.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** 统计周期范围计算测试 */
class PeriodsTest {

    private val anchor = LocalDate.of(2026, 11, 18) // 周三

    @Test
    fun `day range is the anchor day`() {
        val r = periodRange(anchor, PeriodType.DAY)
        assertEquals(anchor, r.start)
        assertEquals(anchor, r.end)
    }

    @Test
    fun `week range is monday to sunday`() {
        val r = periodRange(anchor, PeriodType.WEEK)
        assertEquals(LocalDate.of(2026, 11, 16), r.start) // 周一
        assertEquals(LocalDate.of(2026, 11, 22), r.end)   // 周日
    }

    @Test
    fun `month range covers full month`() {
        val r = periodRange(LocalDate.of(2026, 2, 10), PeriodType.MONTH)
        assertEquals(LocalDate.of(2026, 2, 1), r.start)
        assertEquals(LocalDate.of(2026, 2, 28), r.end)
    }

    @Test
    fun `year range covers full year`() {
        val r = periodRange(anchor, PeriodType.YEAR)
        assertEquals(LocalDate.of(2026, 1, 1), r.start)
        assertEquals(LocalDate.of(2026, 12, 31), r.end)
    }

    @Test
    fun `shift month moves to adjacent month same day`() {
        val r = periodRange(anchor, PeriodType.MONTH).shift(1, PeriodType.MONTH)
        assertEquals(LocalDate.of(2026, 12, 1), r.start)
        assertEquals(LocalDate.of(2026, 12, 31), r.end)
    }

    @Test
    fun `year bucket labels are twelve months`() {
        assertEquals(12, periodRange(anchor, PeriodType.YEAR).bucketLabels().size)
    }
}
