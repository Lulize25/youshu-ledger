package com.youshu.ledger.ui.txns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.youshu.ledger.data.AppContainer
import com.youshu.ledger.data.local.entity.AccountEntity
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.TransactionWithDetails
import com.youshu.ledger.data.local.entity.TxnType
import com.youshu.ledger.data.local.entity.effectiveEnableMillis
import com.youshu.ledger.data.local.entity.effectiveTimeMillis
import com.youshu.ledger.domain.util.toLocalDate
import com.youshu.ledger.ui.common.DayGroup
import com.youshu.ledger.ui.common.TxnDisplay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TxnFilters(
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val type: TxnType? = null,
    val keyword: String = "",
    val startDate: Long? = null,
    val endDate: Long? = null,
) {
    val keywordOrNull: String? get() = keyword.takeIf { it.isNotBlank() }
    val active: Boolean
        get() = accountId != null || categoryId != null || type != null ||
            keyword.isNotBlank() || startDate != null || endDate != null
}

data class TxnListUiState(
    val loading: Boolean = true,
    val bookName: String = "",
    val groups: List<DayGroup> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val filters: TxnFilters = TxnFilters(),
    val totalIncome: Long = 0,
    val totalExpense: Long = 0,
    /** 每笔流水「经过这一步之后」**全部账户**的余额：txnId →（账户名 → 余额） */
    val balancesAfter: Map<Long, Map<String, Long>> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TxnListViewModel(container: AppContainer) : ViewModel() {

    private val bookRepo = container.bookRepository
    private val txnRepo = container.transactionRepository
    private val accountRepo = container.accountRepository
    private val categoryRepo = container.categoryRepository

    private val filters = MutableStateFlow(TxnFilters())

    private val bookFlow = bookRepo.currentBook.filterNotNull()

    val uiState: StateFlow<TxnListUiState> =
        combine(bookFlow, filters) { book, f -> book to f }
            .flatMapLatest { (book, f) ->
                combine(
                    txnRepo.observeFiltered(
                        book.id, f.accountId, f.categoryId, f.type,
                        f.keywordOrNull, f.startDate, f.endDate,
                    ),
                    accountRepo.observeByBook(book.id),
                    categoryRepo.observeAll(),
                    txnRepo.observeByBook(book.id),
                ) { txns, accounts, categories, allTxns ->
                    val accById = accounts.associateBy { it.id }
                    val catById = categories.associateBy { it.id }
                    val displays = txns.map { it.toDisplay(accById, catById) }
                    val groups = displays
                        .groupBy { it.businessDate.toLocalDate() }
                        .toSortedMap(reverseOrder())
                        .map { (date, items) -> DayGroup(date, items.sortedByDescending { it.timeOfDay }) }
                    TxnListUiState(
                        loading = false,
                        bookName = book.name,
                        groups = groups,
                        accounts = accounts,
                        categories = categories,
                        filters = f,
                        totalIncome = displays.filter { it.type == TxnType.INCOME }.sumOf { it.amount },
                        totalExpense = displays.filter { it.type == TxnType.EXPENSE }.sumOf { it.amount },
                        balancesAfter = buildBalancesAfter(allTxns, accounts),
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TxnListUiState())

    private fun TransactionWithDetails.toDisplay(
        accById: Map<Long, AccountEntity>,
        catById: Map<Long, CategoryEntity>,
    ): TxnDisplay {
        val account = accById[transaction.accountId]
        val backfill = when (transaction.type) {
            TxnType.TRANSFER -> false
            else -> account != null && transaction.effectiveTimeMillis() < account.effectiveEnableMillis()
        }
        return TxnDisplay(
            id = transaction.id,
            type = transaction.type,
            amount = transaction.amount,
            businessDate = transaction.businessDate,
            timeOfDay = transaction.timeOfDay,
            note = transaction.note,
            project = transaction.project,
            category = transaction.categoryId?.let { catById[it] },
            categoryPath = transaction.categoryId?.let { catById[it] }?.let { leaf ->
                catById[leaf.parentId]?.let { "${it.name} · ${leaf.name}" } ?: leaf.name
            } ?: "",
            accountName = account?.name ?: "未知账户",
            toAccountName = transaction.toAccountId?.let { accById[it]?.name },
            refundSum = refunds.sumOf { it.amount },
            reimburseSum = reimbursements.sumOf { it.amount },
            attachmentCount = attachments.size,
            backfill = backfill,
        )
    }

    /**
     * 计算每笔流水「经过这一步之后」各账户的余额：
     * 把账本内全部流水按账户展开成事件流（流水影响 / 退款 / 报销 / 收回代付，各带发生时刻），
     * 再对每个账户做按时间排序的前缀和；某笔流水之后的余额 = 期初 + 时刻 ≤ 该流水生效时刻的事件合计。
     * v1.6 起对每笔流水返回**全部账户**的余额（流水页展开区全量展示）。
     */
    private fun buildBalancesAfter(
        allTxns: List<TransactionWithDetails>,
        accounts: List<AccountEntity>,
    ): Map<Long, Map<String, Long>> {
        val eventsByAccount = HashMap<Long, MutableList<Pair<Long, Long>>>() // accountId -> (时刻, 变动)
        fun addEvent(accountId: Long?, timeMillis: Long, delta: Long) {
            if (accountId == null || delta == 0L) return
            eventsByAccount.getOrPut(accountId) { mutableListOf() }.add(timeMillis to delta)
        }
        allTxns.forEach { d ->
            val t = d.transaction
            val eff = t.effectiveTimeMillis()
            when (t.type) {
                TxnType.EXPENSE -> addEvent(t.accountId, eff, -t.amount)
                TxnType.INCOME -> addEvent(t.accountId, eff, t.amount)
                TxnType.TRANSFER -> {
                    addEvent(t.accountId, eff, -(t.amount + t.fee))
                    addEvent(t.toAccountId, eff, t.amount)
                }
            }
            d.refunds.forEach { addEvent(it.accountId, it.timeMillis, it.amount) }
            d.reimbursements.forEach { addEvent(it.accountId, it.timeMillis, it.amount) }
            addEvent(t.accountId, eff, t.recoveredAmount) // 收回代付（发生即计入）
        }

        // 每个账户：按时刻排序 + 前缀和，balanceAt(acc, t) = 期初 + Σ(时刻 ≤ t)
        val prefixByAccount = HashMap<Long, Pair<LongArray, LongArray>>() // accountId -> (sortedTimes, cumulative)
        val initialOf = accounts.associate { it.id to it.initialBalance }
        eventsByAccount.forEach { (accId, events) ->
            val sorted = events.sortedBy { it.first }
            val times = LongArray(sorted.size)
            val cum = LongArray(sorted.size + 1)
            sorted.forEachIndexed { i, (time, delta) ->
                times[i] = time
                cum[i + 1] = cum[i] + delta
            }
            prefixByAccount[accId] = times to cum
        }
        fun balanceAt(accId: Long, timeMillis: Long): Long {
            val p = prefixByAccount[accId] ?: return initialOf[accId] ?: 0L
            // times 中 ≤ timeMillis 的个数（同一时刻的事件全部计入）
            var lo = 0
            var hi = p.first.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (p.first[mid] <= timeMillis) lo = mid + 1 else hi = mid
            }
            return (initialOf[accId] ?: 0L) + p.second[lo]
        }

        return allTxns.associate { d ->
            val t = d.transaction
            val eff = t.effectiveTimeMillis()
            // v1.6：展开区显示「全部账户」在此时此刻的余额（不再只列涉及账户）
            t.id to accounts.associate { it.name to balanceAt(it.id, eff) }
        }
    }

    fun updateFilters(f: TxnFilters) {
        filters.value = f
    }

    fun deleteTxn(item: TxnDisplay, onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val detail = txnRepo.getWithDetails(item.id) ?: return@launch
            txnRepo.deleteTransaction(detail.transaction)
            onMessage(if (item.backfill) "已删除补录流水（不影响余额）" else "已删除，余额已自动回滚")
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { TxnListViewModel(container) }
        }
    }
}
