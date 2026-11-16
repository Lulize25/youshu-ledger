package com.youshu.ledger.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.youshu.ledger.data.local.dao.AccountDao
import com.youshu.ledger.data.local.dao.BookDao
import com.youshu.ledger.data.local.dao.BudgetDao
import com.youshu.ledger.data.local.dao.CategoryDao
import com.youshu.ledger.data.local.dao.TransactionDao
import com.youshu.ledger.data.local.entity.AccountEntity
import com.youshu.ledger.data.local.entity.AttachmentEntity
import com.youshu.ledger.data.local.entity.BookEntity
import com.youshu.ledger.data.local.entity.BudgetEntity
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.ReimburseRecordEntity
import com.youshu.ledger.data.local.entity.RefundRecordEntity
import com.youshu.ledger.data.local.entity.TransactionEntity

/**
 * Room 数据库。
 * 结构变更采用 fallbackToDestructiveMigration（本项目为单机离线应用，升级即重建库），
 * schema 导出至 app/schemas/ 便于审阅与 diff。
 */
@Database(
    entities = [
        BookEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        RefundRecordEntity::class,
        ReimburseRecordEntity::class,
        AttachmentEntity::class,
        BudgetEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun budgetDao(): BudgetDao

    companion object {
        @Volatile
        private var instance: LedgerDatabase? = null

        fun getInstance(context: Context): LedgerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LedgerDatabase::class.java,
                    "ledger.db",
                )
                    // 单机离线应用：结构变更直接重建数据库
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
