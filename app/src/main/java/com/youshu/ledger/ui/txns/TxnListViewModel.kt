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
                    categoryRepo.observeByBook(book.id),
                ) { txns, accounts, categories ->
                    val accById = accounts.associateBy { it.id }
                    val catById = categories.associateBy { it.id }
                    val displays = txns.map { it.toDisplay(accById, catById) }
                    val groups = displays
                        .groupBy { it.businessDate.toLocalDate() }
                        .toSortedMap(reverseOrder())
                        .map { (date, items) -> DayGroup(date, items) }
                    TxnListUiState(
                        loading = false,
                        bookName = book.name,
                        groups = groups,
                        accounts = accounts,
                        categories = categories,
                        filters = f,
                        totalIncome = displays.filter { it.type == TxnType.INCOME }.sumOf { it.amount },
                        totalExpense = displays.filter { it.type == TxnType.EXPENSE }.sumOf { it.amount },
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
            else -> account != null && transaction.businessDate < account.enableDate
        }
        return TxnDisplay(
            id = transaction.id,
            type = transaction.type,
            amount = transaction.amount,
            businessDate = transaction.businessDate,
            note = transaction.note,
            category = transaction.categoryId?.let { catById[it] },
            accountName = account?.name ?: "未知账户",
            toAccountName = transaction.toAccountId?.let { accById[it]?.name },
            refundSum = refunds.sumOf { it.amount },
            reimburseSum = reimbursements.sumOf { it.amount },
            attachmentCount = attachments.size,
            backfill = backfill,
        )
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
