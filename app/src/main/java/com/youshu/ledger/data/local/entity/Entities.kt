package com.youshu.ledger.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * 核心数据模型（Room 实体）。
 * 金额一律使用 Long 表示「分」，避免浮点误差（如 0.1 元 = 10）。
 * 日期一律使用本地时区当天的 epoch 毫秒（当天 00:00），精确到「天」。
 */

/** 流水类型：支出 / 收入 / 转账 */
enum class TxnType { EXPENSE, INCOME, TRANSFER }

/** 分类类型：收入、支出各一棵独立的分类树 */
enum class CategoryType { EXPENSE, INCOME }

/** 账本：分类、预算、账户均按账本隔离 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,   // 创建时间（epoch 毫秒）
    val isDefault: Boolean,
)

/** 账户（按账本隔离）。支持负余额（信用卡 / 负债场景）。 */
@Entity(
    tableName = "accounts",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("bookId")],
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,
    val iconKey: String,      // 账户图标（见 ui.common.AccountIcons），替代旧版类型字段
    val initialBalance: Long, // 期初余额（分），可为负
    val enableDate: Long,     // 启用日期（当天 00:00 的 epoch 毫秒），余额从这天起算
    val sortOrder: Int,
    val archived: Boolean,
)

/** 分类（按账本隔离，树状多级；根分类 parentId = null） */
@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE, // 删除父分类时一并删除子分类
        ),
    ],
    indices = [Index("bookId"), Index("parentId")],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val type: CategoryType,
    val parentId: Long?,   // 根分类为 null
    val name: String,
    val sortOrder: Int,
    val color: Long,       // 圆形底色（ARGB）
    val iconText: String,  // 圆内 1~2 个文字，如「餐」「交通」
)

/**
 * 流水。
 * 支出/收入：categoryId + accountId 必填；
 * 转账：accountId（转出）+ toAccountId（转入）必填，fee 为可选手续费（分，从转出账户扣除）。
 * 支出扩展字段：advanceAmount = 代付金额（我替他人支付的部分），recoveredAmount = 已收回金额。
 * 退款 / 报销为独立子表（见 RefundRecordEntity / ReimburseRecordEntity），可部分、可多次。
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("bookId"), Index("businessDate"), Index("accountId"), Index("categoryId")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val type: TxnType,
    val amount: Long,        // 金额（分）
    val businessDate: Long,  // 业务日期（当天 00:00 的 epoch 毫秒），统计一律按此归档
    val timeOfDay: Int = -1, // 当天分钟数（0~1439），仅展示维度；-1 = 未记录（旧数据）
    val createdAt: Long,     // 录入时间（epoch 毫秒）
    val note: String,
    val categoryId: Long?,   // 转账时为 null
    val accountId: Long,     // 支出/收入的账户；转账的转出账户
    val toAccountId: Long?,  // 仅转账：转入账户
    val fee: Long = 0,       // 仅转账：手续费（分），从转出账户扣除
    val advanceAmount: Long = 0,    // 仅支出：代付金额（分）
    val recoveredAmount: Long = 0,  // 仅支出：已收回金额（分）
)

/** 退款记录：每条含金额、备注、录入时间；可部分退款、可多次 */
@Entity(
    tableName = "refund_records",
    foreignKeys = [ForeignKey(
        entity = TransactionEntity::class,
        parentColumns = ["id"],
        childColumns = ["transactionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("transactionId")],
)
data class RefundRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val amount: Long,
    val note: String,
    val createdAt: Long,
)

/** 报销记录：规则与退款一致 */
@Entity(
    tableName = "reimburse_records",
    foreignKeys = [ForeignKey(
        entity = TransactionEntity::class,
        parentColumns = ["id"],
        childColumns = ["transactionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("transactionId")],
)
data class ReimburseRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val amount: Long,
    val note: String,
    val createdAt: Long,
)

/** 附件：图片保存在 filesDir/attachments/，此处记录相对路径 */
@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(
        entity = TransactionEntity::class,
        parentColumns = ["id"],
        childColumns = ["transactionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("transactionId")],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val relativePath: String,
)

/**
 * 预算（按账本）：categoryId = null 表示月度总预算，否则为分类级预算。
 * yearMonth 格式「yyyy-MM」。
 */
@Entity(
    tableName = "budgets",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("bookId")],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val categoryId: Long?,   // null = 月度总预算
    val yearMonth: String,   // 「yyyy-MM」
    val amount: Long,        // 预算金额（分）
)

/** 流水及其子表（退款/报销/附件）的一次性查询结果 */
data class TransactionWithDetails(
    @androidx.room.Embedded val transaction: TransactionEntity,
    @androidx.room.Relation(
        parentColumn = "id",
        entityColumn = "transactionId",
    ) val refunds: List<RefundRecordEntity>,
    @androidx.room.Relation(
        parentColumn = "id",
        entityColumn = "transactionId",
    ) val reimbursements: List<ReimburseRecordEntity>,
    @androidx.room.Relation(
        parentColumn = "id",
        entityColumn = "transactionId",
    ) val attachments: List<AttachmentEntity>,
)
