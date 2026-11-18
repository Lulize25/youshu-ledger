package com.youshu.ledger.data

import android.content.Context
import com.youshu.ledger.data.local.db.LedgerDatabase
import com.youshu.ledger.data.local.seed.DefaultDataSeeder
import com.youshu.ledger.data.repository.AccountRepository
import com.youshu.ledger.data.repository.BookRepository
import com.youshu.ledger.data.repository.BudgetRepository
import com.youshu.ledger.data.repository.CategoryRepository
import com.youshu.ledger.data.repository.TransactionRepository
import com.youshu.ledger.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用级依赖容器（手工 DI）。
 * 持有数据库、各 DAO、设置存储；应用启动时在 IO 协程中初始化默认数据。
 */
class AppContainer(context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: LedgerDatabase = LedgerDatabase.getInstance(context)

    val bookDao = database.bookDao()
    val accountDao = database.accountDao()
    val categoryDao = database.categoryDao()
    val transactionDao = database.transactionDao()
    val budgetDao = database.budgetDao()

    val settingsStore = SettingsStore(context)

    /** 流水仓库（余额/补录核心规则的落地入口） */
    val transactionRepository = TransactionRepository(transactionDao, settingsStore)

    val bookRepository = BookRepository(bookDao, settingsStore)
    val accountRepository = AccountRepository(accountDao, transactionDao)
    val categoryRepository = CategoryRepository(categoryDao)
    val budgetRepository = BudgetRepository(budgetDao)

    val filesDir = context.filesDir

    init {
        // 首次启动：自动创建默认账本「日常账本」与预置分类
        appScope.launch(Dispatchers.IO) {
            DefaultDataSeeder.seedIfEmpty(bookDao, categoryDao, System.currentTimeMillis())
        }
    }
}
