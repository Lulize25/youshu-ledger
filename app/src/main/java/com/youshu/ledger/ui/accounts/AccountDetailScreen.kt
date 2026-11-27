package com.youshu.ledger.ui.accounts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.youshu.ledger.data.AppContainer
import com.youshu.ledger.data.local.entity.AccountEntity
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.TransactionWithDetails
import com.youshu.ledger.data.local.entity.TxnType
import com.youshu.ledger.data.local.entity.effectiveEnableMillis
import com.youshu.ledger.data.local.entity.effectiveTimeMillis
import com.youshu.ledger.domain.util.formatDay
import com.youshu.ledger.domain.util.formatEnableDay
import com.youshu.ledger.domain.util.toLocalDate
import com.youshu.ledger.domain.util.toYuan
import com.youshu.ledger.ui.common.DayGroup
import com.youshu.ledger.ui.common.DayHeader
import com.youshu.ledger.ui.common.LedgerTopBar
import com.youshu.ledger.ui.common.TxnDisplay
import com.youshu.ledger.ui.common.TxnRow
import com.youshu.ledger.ui.theme.expense
import com.youshu.ledger.ui.theme.income
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class AccountDetailUiState(
    val loading: Boolean = true,
    val account: AccountEntity? = null,
    val balance: Long = 0,
    val groups: List<DayGroup> = emptyList(),
)

class AccountDetailViewModel(
    container: AppContainer,
    private val accountId: Long,
) : ViewModel() {

    private val accountRepo = container.accountRepository
    private val txnRepo = container.transactionRepository
    private val categoryRepo = container.categoryRepository
    private val settings = container.settingsStore

    private val _ui = MutableStateFlow(AccountDetailUiState())
    val ui: StateFlow<AccountDetailUiState> = _ui

    init {
        viewModelScope.launch {
            val account = accountRepo.getById(accountId) ?: return@launch
            combine(
                txnRepo.observeByAccount(accountId),
                settings.dataVersion,
            ) { txns, _ -> txns }.collect { txns ->
                val categories = categoryRepo.getAll()
                val catById = categories.associateBy { it.id }
                val displays = txns.map { it.toDisplay(catById) }
                _ui.value = AccountDetailUiState(
                    loading = false,
                    account = account,
                    balance = txnRepo.getAccountBalance(account),
                    groups = displays
                        .groupBy { it.businessDate.toLocalDate() }
                        .toSortedMap(reverseOrder())
                        .map { (date, items) -> DayGroup(date, items.sortedByDescending { it.timeOfDay }) },
                )
            }
        }
    }

    private fun TransactionWithDetails.toDisplay(catById: Map<Long, CategoryEntity>) = TxnDisplay(
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
        accountName = "",
        toAccountName = null,
        refundSum = refunds.sumOf { it.amount },
        reimburseSum = reimbursements.sumOf { it.amount },
        attachmentCount = attachments.size,
        backfill = transaction.type != TxnType.TRANSFER &&
            transaction.effectiveTimeMillis() < (ui.value.account?.effectiveEnableMillis() ?: Long.MIN_VALUE),
    )

    companion object {
        fun factory(container: AppContainer, accountId: Long) = viewModelFactory {
            initializer { AccountDetailViewModel(container, accountId) }
        }
    }
}

/** 单账户流水页：余额头部 + 该账户全部流水（含补录） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    container: AppContainer,
    accountId: Long,
    onEdit: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val vm: AccountDetailViewModel = viewModel(factory = AccountDetailViewModel.factory(container, accountId))
    val state by vm.ui.collectAsState()

    Scaffold(
        topBar = {
            LedgerTopBar(
                title = state.account?.name ?: "账户明细",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // 余额卡
            Card(modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("当前余额", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = state.balance.toYuan() + " 元",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (state.balance < 0) MaterialTheme.colorScheme.expense
                            else MaterialTheme.colorScheme.income,
                        )
                        state.account?.let {
                            Text(
                                "期初 ${it.initialBalance.toYuan()} · 校准 ${formatEnableDay(it.enableDate, it.enableTimeOfDay)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }

            if (state.groups.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("该账户暂无流水", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    state.groups.forEach { group ->
                        item(key = "header_${group.date}") { DayHeader(group) }
                        items(group.items, key = { it.id }) { item ->
                            TxnRow(item = item, onClick = { onEdit(item.id) })
                        }
                    }
                }
            }
        }
    }
}
