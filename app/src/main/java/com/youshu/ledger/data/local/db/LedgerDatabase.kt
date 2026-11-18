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
 * - v2 → v3：transactions 新增 timeOfDay（当天分钟数，-1 = 未记录），
 *   仅影响展示与录入，余额/统计仍按 businessDate（天）计算，旧数据默认 -1。
 * - v3 → v4：accounts 新增 enableTimeOfDay（启用当天分钟数，-1 = 未设置），
 *   补录判断精确到分钟：启用当天早于该时刻的流水判为补录，旧账户默认 -1（行为不变）。
 * - v4 → v5：accounts.iconKey 语义变更——预置图标 key 转换为用户自定义图标文字（1~2 字）。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE `accounts` SET `iconKey` = CASE `iconKey` " +
                "WHEN 'wechat' THEN '微信' WHEN 'alipay' THEN '支付' " +
                "WHEN 'cash' THEN '现金' WHEN 'bank' THEN '银行' " +
                "WHEN 'credit' THEN '信用' WHEN 'wallet' THEN '钱包' " +
                "WHEN 'transit' THEN '公交' WHEN 'campus' THEN '校园' " +
                "WHEN 'meal' THEN '饭卡' WHEN 'other' THEN '其他' " +
                "ELSE substr(`name`, 1, 2) END",
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `accounts` ADD COLUMN `enableTimeOfDay` INTEGER NOT NULL DEFAULT -1")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `timeOfDay` INTEGER NOT NULL DEFAULT -1")
    }
}

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
 * 结构变更必须提供 Migration（见 MIGRATION_1_2）——用户记账数据绝不因升级丢失；
 * 无迁移路径时 Room 直接报错，宁可无法启动也不能静默清空。
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
    version = 5,
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
                    // 只走显式迁移：宁可升级失败报错，也绝不静默清空用户记账数据
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
    }
}
