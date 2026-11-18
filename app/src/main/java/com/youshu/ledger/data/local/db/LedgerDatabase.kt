package com.youshu.ledger.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
 * 数据库结构演进：
 * - v1 → v2：accounts.type（枚举）改为 accounts.iconKey（预置图标 key），
 *   数据保留，旧类型自动映射到对应图标。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `accounts` ADD COLUMN `iconKey` TEXT NOT NULL DEFAULT 'other'")
        db.execSQL(
            "UPDATE `accounts` SET `iconKey` = CASE `type` " +
                "WHEN 'WECHAT' THEN 'wechat' WHEN 'ALIPAY' THEN 'alipay' " +
                "WHEN 'BANK_CARD' THEN 'bank' WHEN 'CREDIT_CARD' THEN 'credit' " +
                "WHEN 'CASH' THEN 'cash' ELSE 'other' END",
        )
        // SQLite（minSdk 28 上的低版本）不支持 DROP COLUMN，重建表去掉 type
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `accounts_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, `name` TEXT NOT NULL, `iconKey` TEXT NOT NULL, " +
                "`initialBalance` INTEGER NOT NULL, `enableDate` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, `archived` INTEGER NOT NULL, " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "INSERT INTO `accounts_new` (`id`,`bookId`,`name`,`iconKey`,`initialBalance`,`enableDate`,`sortOrder`,`archived`) " +
                "SELECT `id`,`bookId`,`name`,`iconKey`,`initialBalance`,`enableDate`,`sortOrder`,`archived` FROM `accounts`",
        )
        db.execSQL("DROP TABLE `accounts`")
        db.execSQL("ALTER TABLE `accounts_new` RENAME TO `accounts`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_accounts_bookId` ON `accounts` (`bookId`)")
    }
}

/**
 * Room 数据库。
 * 结构变更优先写 Migration 保留用户数据；无法迁移时退回重建（单机离线应用）。
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
    version = 2,
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
                    // 优先执行显式迁移保留数据；无迁移路径时才重建
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
