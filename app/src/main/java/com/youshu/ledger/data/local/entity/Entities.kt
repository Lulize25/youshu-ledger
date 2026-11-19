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
    val iconKey: String,      // 账户图标文字（1~2 个字，v1.4.0 起；旧版预置 key 已迁移为文字）
    val initialBalance: Long, // 期初余额（分），可为负
    val enableDate: Long,     // 启用日期（当天 00:00 的 epoch 毫秒），余额从这天起算
    val enableTimeOfDay: Int = -1, // 启用当天的分钟数（0~1439），-1 = 未设置（从当天 00:00 起算）
    val sortOrder: Int,
    val archived: Boolean,
)

/** 分类（v1.5.1 起全局共享，不按账本隔离；树状多级；根分类 parentId = null。
 *  bookId 列保留但不再建外键——全局分类没有所属账本，删除账本绝不能再级联删除分类。） */
@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE, // 删除父分类时一并删除子分类
        ),
    ],
    indices = [Index("parentId")],
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
    val project: String = "",     // 项目名称（可选，标记用途/项目维度）
    val advanceAmount: Long = 0,    // 仅支出：代付金额合计（分，= 代付记录之和，由仓库维护）
    val recoveredAmount: Long = 0,  // 仅支出：已收回金额（分）
)

/**
 * 退款记录：可部分退款、可多次。
 * 金额 / 退款账户 / 退款时间 必填，备注可选；退款计入「退款账户」的余额。
 */
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
    val accountId: Long,   // 退款到账账户（v1.5.0 起；旧数据迁移为流水账户）
    val timeMillis: Long,   // 退款发生时间（v1.5.0 起；旧数据迁移为录入时间）
    val note: String,
    val createdAt: Long,
)

/** 报销记录：字段规则与退款一致 */
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
    val accountId: Long,
    val timeMillis: Long,
    val note: String,
    val createdAt: Long,
)

/**
 * 代付记录：这笔支出替谁付了多少钱（一人一条，可多人）。
 * 仅「谁」必填；金额 / 时间 / 账户 / 备注均可选补充。
 * 不改变余额（资金已随支出流出）；收回登记仍在流水上按总额进行。
 */
@Entity(
    tableName = "advance_records",
    foreignKeys = [ForeignKey(
        entity = TransactionEntity::class,
        parentColumns = ["id"],
        childColumns = ["transactionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("transactionId")],
)
data class AdvanceRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val person: String,       // 代付对象（谁）——必填
    val amount: Long?,      // 代付金额（分，可选）
    val accountId: Long?,   // 关联账户（可选）
    val timeMillis: Long?,  // 发生时间（可选）
    val note: String,
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

/** 流水及其子表（退款/报销/代付/附件）的一次性查询结果 */
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
    ) val advances: List<AdvanceRecordEntity>,
    @androidx.room.Relation(
        parentColumn = "id",
        entityColumn = "transactionId",
    ) val attachments: List<AttachmentEntity>,
)

/**
 * 流水生效时刻（epoch 毫秒）= 业务日期 + 当天分钟数。
 * 未记录时间的旧数据按当天 23:59 处理，保证「启用日当天无时间流水」永远计入余额（与旧版行为一致）。
 */
fun TransactionEntity.effectiveTimeMillis(): Long =
    businessDate + (if (timeOfDay >= 0) timeOfDay else 1439) * 60_000L

/**
 * 账户启用时刻（epoch 毫秒）= 启用日期 + 启用当天分钟数。
 * 未设置时间的旧账户按当天 00:00 处理（与旧版按天判断行为一致）；
 * 设置了启用时间的账户，当天早于该时刻的流水将判为补录。
 */
fun AccountEntity.effectiveEnableMillis(): Long =
    enableDate + (if (enableTimeOfDay >= 0) enableTimeOfDay else 0) * 60_000L
