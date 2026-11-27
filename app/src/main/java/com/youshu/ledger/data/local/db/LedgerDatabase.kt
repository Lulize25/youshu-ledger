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
 * - v5 → v6：退款/报销记录增加「账户 + 发生时间」（旧数据迁移为流水账户 / 录入时间）；
 *   流水增加「项目名称」；新增代付人明细表 advance_records，
 *   旧 advanceAmount > 0 的流水迁移为一条代付记录（总额缓存列保留）。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 退款：补账户（默认该笔流水的账户）与时间（默认录入时间）
        db.execSQL("ALTER TABLE `refund_records` ADD COLUMN `accountId` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "UPDATE `refund_records` SET `accountId` = " +
                "(SELECT `accountId` FROM `transactions` WHERE `transactions`.`id` = `refund_records`.`transactionId`) " +
                "WHERE `accountId` = 0",
        )
        db.execSQL("ALTER TABLE `refund_records` ADD COLUMN `timeMillis` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `refund_records` SET `timeMillis` = `createdAt` WHERE `timeMillis` = 0")
        // 报销：同上
        db.execSQL("ALTER TABLE `reimburse_records` ADD COLUMN `accountId` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "UPDATE `reimburse_records` SET `accountId` = " +
                "(SELECT `accountId` FROM `transactions` WHERE `transactions`.`id` = `reimburse_records`.`transactionId`) " +
                "WHERE `accountId` = 0",
        )
        db.execSQL("ALTER TABLE `reimburse_records` ADD COLUMN `timeMillis` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `reimburse_records` SET `timeMillis` = `createdAt` WHERE `timeMillis` = 0")
        // 流水：项目名称
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `project` TEXT NOT NULL DEFAULT ''")
        // 代付人明细表
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `advance_records` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`transactionId` INTEGER NOT NULL, `person` TEXT NOT NULL, " +
                "`amount` INTEGER, `accountId` INTEGER, `timeMillis` INTEGER, `note` TEXT NOT NULL, " +
                "FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_advance_records_transactionId` ON `advance_records` (`transactionId`)")
        // 旧代付总额 → 一条代付记录
        db.execSQL(
            "INSERT INTO `advance_records` (`transactionId`, `person`, `amount`, `accountId`, `timeMillis`, `note`) " +
                "SELECT `id`, '代付', `advanceAmount`, `accountId`, `createdAt`, '' FROM `transactions` " +
                "WHERE `advanceAmount` > 0",
        )
    }
}

/**
 * v6 → v7：分类不再按账本隔离，全局共享（bookId 列保留但语义废弃，统一置 0）。
 * 各账本重复的分类（同类型 + 同名 + 同父）合并为一条：流水与预算中的引用重定向到保留项。
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        data class Row(val id: Long, val type: String, val parentId: Long?, val name: String)

        val rows = mutableListOf<Row>()
        db.query("SELECT `id`, `type`, `parentId`, `name` FROM `categories` ORDER BY `id` ASC").use { c ->
            while (c.moveToNext()) {
                rows.add(
                    Row(
                        id = c.getLong(0),
                        type = c.getString(1),
                        parentId = if (c.isNull(2)) null else c.getLong(2),
                        name = c.getString(3),
                    ),
                )
            }
        }
        val nameOf = rows.associate { it.id to it.name }
        // 去重键：类型 + 父分类名（根分类为空串）+ 名称
        val canonical = HashMap<String, Long>()
        val dupToCanon = HashMap<Long, Long>()
        rows.forEach { r ->
            val parentName = r.parentId?.let { nameOf[it] } ?: ""
            val key = r.type + "" + parentName + "" + r.name
            val keep = canonical.getOrPut(key) { r.id }
            if (keep != r.id) dupToCanon[r.id] = keep
        }
        // 引用重定向：流水与预算中的分类 id 指向被合并项时改指保留项
        dupToCanon.forEach { (dup, canon) ->
            db.execSQL("UPDATE `transactions` SET `categoryId` = $canon WHERE `categoryId` = $dup")
            db.execSQL("UPDATE `budgets` SET `categoryId` = $canon WHERE `categoryId` = $dup")
        }
        if (dupToCanon.isNotEmpty()) {
            val ids = dupToCanon.keys.joinToString(",")
            db.execSQL("DELETE FROM `categories` WHERE `id` IN ($ids)")
        }
        db.execSQL("UPDATE `categories` SET `bookId` = 0")
    }
}

/**
 * v7 → v8：categories 表去掉 bookId → books 的外键（v1.5.1 起分类全局共享，bookId 恒为 0）。
 * 不修的话：新建分类外键校验失败直接崩；删除账本会级联删掉全部分类。
 * SQLite 不支持删外键，重建表（数据原样保留；parentId 自引用外键保留）。
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `categories_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, `type` TEXT NOT NULL, `parentId` INTEGER, " +
                "`name` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, " +
                "`color` INTEGER NOT NULL, `iconText` TEXT NOT NULL, " +
                "FOREIGN KEY(`parentId`) REFERENCES `categories`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "INSERT INTO `categories_new` " +
                "(`id`,`bookId`,`type`,`parentId`,`name`,`sortOrder`,`color`,`iconText`) " +
                "SELECT `id`,`bookId`,`type`,`parentId`,`name`,`sortOrder`,`color`,`iconText` FROM `categories`",
        )
        db.execSQL("DROP TABLE `categories`")
        db.execSQL("ALTER TABLE `categories_new` RENAME TO `categories`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_parentId` ON `categories` (`parentId`)")
    }
}

/**
 * v8 → v9：A钱 / 报销「条目组」（v1.7）。
 * transactions / advance_records / reimburse_records 各加可空列 groupId：
 * 组内「支出条 / A钱条 / 报销条」在任意一条支出上可见可编辑。
 * 旧数据 groupId 全 null = 未入组，行为与旧版完全一致（仅挂在原流水上）。
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `groupId` INTEGER")
        db.execSQL("ALTER TABLE `advance_records` ADD COLUMN `groupId` INTEGER")
        db.execSQL("ALTER TABLE `reimburse_records` ADD COLUMN `groupId` INTEGER")
    }
}

/**
 * v9 → v10：旧版（v1.7.0 之前）的 A钱 / 报销记录正式融入条目组——
 * groupId 回填为宿主流水 id（组号），使其与新建条目一样组内等价可见。
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE `advance_records` SET `groupId` = `transactionId` WHERE `groupId` IS NULL")
        db.execSQL("UPDATE `reimburse_records` SET `groupId` = `transactionId` WHERE `groupId` IS NULL")
    }
}

/**
 * v10 → v11：A钱 / 报销「条目化」（v1.7.2）。
 * 旧的 reimburse_records / advance_records 子表废弃，改为：
 * 流水表加 entryRole 列（NORMAL / ADVANCE / REIMBURSE），
 * A钱条 / 报销条 = 带角色标记并关联进条目组（groupId）的收入流水。
 * 迁移把旧子表数据逐条转换为收入流水（金额/账户/时间/备注/组号原样保留），随后删除旧表。
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `entryRole` TEXT NOT NULL DEFAULT 'NORMAL'")
        // 旧报销记录 → REIMBURSE 收入流水（时间拆成 业务日期 + 当天分钟）
        db.execSQL(
            "INSERT INTO `transactions` " +
                "(`bookId`,`type`,`amount`,`businessDate`,`timeOfDay`,`createdAt`,`note`," +
                "`categoryId`,`accountId`,`toAccountId`,`fee`,`project`," +
                "`advanceAmount`,`recoveredAmount`,`groupId`,`entryRole`) " +
                "SELECT t.`bookId`, 'INCOME', r.`amount`, " +
                "(r.`timeMillis` / 86400000) * 86400000, " +
                "CAST((r.`timeMillis` % 86400000) / 60000 AS INTEGER), " +
                "r.`createdAt`, r.`note`, NULL, r.`accountId`, NULL, 0, '', 0, 0, r.`groupId`, 'REIMBURSE' " +
                "FROM `reimburse_records` r INNER JOIN `transactions` t ON t.`id` = r.`transactionId`",
        )
        // 旧代付记录 → ADVANCE 收入流水（「谁」并入备注；缺金额记 0、缺时间记 -1）
        db.execSQL(
            "INSERT INTO `transactions` " +
                "(`bookId`,`type`,`amount`,`businessDate`,`timeOfDay`,`createdAt`,`note`," +
                "`categoryId`,`accountId`,`toAccountId`,`fee`,`project`," +
                "`advanceAmount`,`recoveredAmount`,`groupId`,`entryRole`) " +
                "SELECT t.`bookId`, 'INCOME', COALESCE(a.`amount`, 0), " +
                "CASE WHEN a.`timeMillis` IS NULL THEN t.`businessDate` " +
                "ELSE (a.`timeMillis` / 86400000) * 86400000 END, " +
                "CASE WHEN a.`timeMillis` IS NULL THEN -1 " +
                "ELSE CAST((a.`timeMillis` % 86400000) / 60000 AS INTEGER) END, " +
                "t.`createdAt`, " +
                "a.`person` || CASE WHEN a.`note` IS NOT NULL AND a.`note` != '' THEN '：' || a.`note` ELSE '' END, " +
                "NULL, COALESCE(a.`accountId`, t.`accountId`), NULL, 0, '', 0, 0, a.`groupId`, 'ADVANCE' " +
                "FROM `advance_records` a INNER JOIN `transactions` t ON t.`id` = a.`transactionId`",
        )
        db.execSQL("DROP TABLE `reimburse_records`")
        db.execSQL("DROP TABLE `advance_records`")
    }
}

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
        AttachmentEntity::class,
        BudgetEntity::class,
    ],
    version = 11,
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
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                        MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
