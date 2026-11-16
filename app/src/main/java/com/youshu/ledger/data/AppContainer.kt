package com.youshu.ledger.data

import android.content.Context
import com.youshu.ledger.data.local.db.LedgerDatabase
import com.youshu.ledger.data.local.seed.DefaultDataSeeder
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

    val filesDir = context.filesDir

    init {
        // 首次启动：自动创建默认账本「日常账本」与预置分类
        appScope.launch(Dispatchers.IO) {
            DefaultDataSeeder.seedIfEmpty(bookDao, categoryDao, System.currentTimeMillis())
        }
    }
}
