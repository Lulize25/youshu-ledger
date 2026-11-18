package com.youshu.ledger.ui.stats

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.youshu.ledger.data.AppContainer
import com.youshu.ledger.data.local.dao.DaySum
import com.youshu.ledger.data.local.entity.TxnType
import com.youshu.ledger.domain.stats.PeriodRange
import com.youshu.ledger.domain.stats.PeriodType
import com.youshu.ledger.domain.stats.periodRange
import com.youshu.ledger.domain.util.formatMonth
import com.youshu.ledger.domain.util.toEpochMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 饼图切片 */
data class CategorySlice(val name: String, val color: Color, val amount: Long)

/** 趋势图单桶 */
data class TrendPoint(val label: String, val income: Long, val expense: Long)

/** 预算进度（总额或单分类） */
data class BudgetProgress(
    val name: String,
    val budget: Long,
    val used: Long,
    val categoryId: Long? = null,
) {
    val ratio: Float get() = if (budget <= 0) 0f else (used.toFloat() / budget).coerceAtMost(1.5f)
    val overBudget: Boolean get() = used > budget
}

data class StatsUiState(
    val loading: Boolean = true,
    val bookId: Long = 0,
    val bookName: String = "",
    val periodType: PeriodType = PeriodType.MONTH,
    val range: PeriodRange = periodRange(LocalDate.now(), PeriodType.MONTH),
    // 收支概览（均按业务日期归档；退款/报销/收回归属原支出的那一天）
    val income: Long = 0,
    val expenseGross: Long = 0,
    val refundSum: Long = 0,
    val reimburseSum: Long = 0,
    val netExpense: Long = 0,
    val personalExpense: Long = 0,
    val advanceSum: Long = 0,
    val recoveredSum: Long = 0,
    val balance: Long = 0,          // 结余 = 收入 − 净支出
    val receivableTotal: Long = 0,  // 应收（别人欠我），全账本累计
    // 图表
    val pieSlices: List<CategorySlice> = emptyList(),
    val trend: List<TrendPoint> = emptyList(),
    // 预算（按锚点所在月）
    val monthKey: String = "",
    val totalBudget: BudgetProgress? = null,
    val categoryBudgets: List<BudgetProgress> = emptyList(),
) {
    val hasBudget: Boolean get() = totalBudget != null || categoryBudgets.isNotEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(container: AppContainer) : ViewModel() {

    private val bookRepo = container.bookRepository
    private val txnDao = container.transactionDao
    private val categoryRepo = container.categoryRepository
    private val budgetRepo = container.budgetRepository
    private val settings = container.settingsStore

    private val periodType = MutableStateFlow(PeriodType.MONTH)
    private val anchor = MutableStateFlow(LocalDate.now())

    private val rangeFlow = combine(periodType, anchor) { t, a -> periodRange(a, t) }

    val uiState: StateFlow<StatsUiState> =
        combine(
            bookRepo.currentBook,
            rangeFlow,
            settings.dataVersion,
        ) { book, range, _ -> book to range }
            .flatMapLatest { (book, range) ->
                flow {
                    if (book == null) {
                        emit(StatsUiState(loading = false))
                        return@flow
                    }
                    emit(computeStats(book.id, book.name, range))
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    private suspend fun computeStats(bookId: Long, bookName: String, range: PeriodRange): StatsUiState {
        val type = periodType.value
        val start = range.start.toEpochMillis()
        val end = range.end.toEpochMillis()

        val income = txnDao.sumByType(bookId, TxnType.INCOME, start, end)
        val expenseGross = txnDao.sumByType(bookId, TxnType.EXPENSE, start, end)
        val refundSum = txnDao.sumRefundsInRange(bookId, start, end)
        val reimburseSum = txnDao.sumReimbursementsInRange(bookId, start, end)
        val netExpense = expenseGross - refundSum - reimburseSum
        val advanceSum = txnDao.sumAdvanceInRange(bookId, start, end)
        val recoveredSum = txnDao.sumRecoveredInRange(bookId, start, end)
        val personalExpense = (netExpense - advanceSum).coerceAtLeast(0)
        val receivableTotal = txnDao.receivableTotal(bookId)

        // 分类饼图（按支出金额合计，未退款抵减；切片按金额倒序，取前 8 + 其他）
        val categories = categoryRepo.getByBook(bookId)
        val catById = categories.associateBy { it.id }
        val sums = txnDao.sumExpenseByCategory(bookId, start, end)
        val slices = sums
            .filter { it.total > 0 }
            .sortedByDescending { it.total }
            .map { cs ->
                val c = catById[cs.categoryId]
                CategorySlice(c?.name ?: "未分类", c?.let { Color(it.color) } ?: Color.Gray, cs.total)
            }
            .let { list ->
                if (list.size > 8) {
                    list.take(7) + CategorySlice(
                        "其他", Color.Gray, list.drop(7).sumOf { it.amount },
                    )
                } else list
            }

        // 趋势：收入/支出分桶
        val labels = range.bucketLabels()
        val incomeByDay = txnDao.sumByDay(bookId, TxnType.INCOME, start, end).associateByDay()
        val expenseByDay = txnDao.sumByDay(bookId, TxnType.EXPENSE, start, end).associateByDay()
        val trend = labels.mapIndexed { index, label ->
            val (inc, exp) = when {
                range.start == range.end -> income to expenseGross
                labels.size == 12 -> {
                    // 年视图：按月聚合
                    val monthIncome = incomeByDay.filterKeys {
                        it.toLocalMonth() == index + 1
                    }.values.sum()
                    val monthExpense = expenseByDay.filterKeys {
                        it.toLocalMonth() == index + 1
                    }.values.sum()
                    monthIncome to monthExpense
                }

                else -> {
                    val day = range.start.plusDays(index.toLong()).toEpochMillis()
                    (incomeByDay[day] ?: 0L) to (expenseByDay[day] ?: 0L)
                }
            }
            TrendPoint(label, inc, exp)
        }

        // 预算（锚点所在月；预算使用口径 = 支出金额合计）
        val monthKey = anchor.value.formatMonth()
        val monthStart = anchor.value.withDayOfMonth(1).toEpochMillis()
        val monthEnd = anchor.value.withDayOfMonth(anchor.value.lengthOfMonth()).toEpochMillis()
        val monthExpense = txnDao.sumByType(bookId, TxnType.EXPENSE, monthStart, monthEnd)
        val budgets = budgetRepo.getForMonth(bookId, monthKey)
        val totalBudget = budgets.firstOrNull { it.categoryId == null }?.let {
            BudgetProgress("月度总预算", it.amount, monthExpense)
        }
        val monthCatSums = txnDao.sumExpenseByCategory(bookId, monthStart, monthEnd)
            .associate { it.categoryId to it.total }
        val categoryBudgets = budgets.filter { it.categoryId != null }.mapNotNull { b ->
            val cat = b.categoryId?.let { catById[it] } ?: return@mapNotNull null
            BudgetProgress(
                cat.name, b.amount, monthCatSums[b.categoryId] ?: 0L, categoryId = b.categoryId,
            )
        }

        return StatsUiState(
            loading = false,
            bookId = bookId,
            bookName = bookName,
            periodType = type,
            range = range,
            income = income,
            expenseGross = expenseGross,
            refundSum = refundSum,
            reimburseSum = reimburseSum,
            netExpense = netExpense,
            personalExpense = personalExpense,
            advanceSum = advanceSum,
            recoveredSum = recoveredSum,
            balance = income - netExpense,
            receivableTotal = receivableTotal,
            pieSlices = slices,
            trend = trend,
            monthKey = monthKey,
            totalBudget = totalBudget,
            categoryBudgets = categoryBudgets,
        )
    }

    private fun List<DaySum>.associateByDay(): Map<Long, Long> =
        associate { it.day to it.total }

    /** epoch 毫秒 → 月（1-12），用于年视图按月聚合 */
    private fun Long.toLocalMonth(): Int =
        java.time.Instant.ofEpochMilli(this).atZone(java.time.ZoneId.systemDefault()).monthValue

    fun setPeriodType(type: PeriodType) {
        periodType.value = type
    }

    fun shiftPeriod(amount: Long) {
        anchor.value = anchor.value.let {
            when (periodType.value) {
                PeriodType.DAY -> it.plusDays(amount)
                PeriodType.WEEK -> it.plusWeeks(amount)
                PeriodType.MONTH -> it.plusMonths(amount)
                PeriodType.YEAR -> it.plusYears(amount)
            }
        }
    }

    fun resetToNow() {
        anchor.value = LocalDate.now()
    }

    fun setTotalBudget(amountFen: Long?) {
        val s = uiState.value
        viewModelScope.launch { budgetRepo.setTotalBudget(s.bookId, s.monthKey, amountFen) }
    }

    fun setCategoryBudget(categoryId: Long, amountFen: Long?) {
        val s = uiState.value
        viewModelScope.launch { budgetRepo.setCategoryBudget(s.bookId, s.monthKey, categoryId, amountFen) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { StatsViewModel(container) }
        }
    }
}
