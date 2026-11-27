package com.youshu.ledger.data.io

import com.youshu.ledger.data.local.entity.AccountEntity
import com.youshu.ledger.data.local.entity.AttachmentEntity
import com.youshu.ledger.data.local.entity.BookEntity
import com.youshu.ledger.data.local.entity.BudgetEntity
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.CategoryType
import com.youshu.ledger.data.local.entity.RefundRecordEntity
import com.youshu.ledger.data.local.entity.TransactionEntity
import com.youshu.ledger.data.local.entity.TxnRole
import com.youshu.ledger.data.local.entity.TxnType
import kotlinx.serialization.Serializable

/*
 * JSON 全量备份的数据传输模型（DTO）。
 * 与 Room 实体一一对应，但全部使用可序列化的基本类型，附带版本号便于后续迁移。
 */

@Serializable
data class BackupFile(
    val formatVersion: Int = 1,
    val app: String = "youshu-ledger",
    val exportedAt: Long,
    val biometricEnabled: Boolean = true,
    val currentBookId: Long? = null,
    val books: List<BookDto> = emptyList(),
    val accounts: List<AccountDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val transactions: List<TransactionDto> = emptyList(),
    val budgets: List<BudgetDto> = emptyList(),
)

@Serializable
data class BookDto(
    val id: Long, val name: String, val createdAt: Long, val isDefault: Boolean,
)

@Serializable
data class AccountDto(
    val id: Long, val bookId: Long, val name: String,
    val iconKey: String = "",            // v2~v4：预置图标 key；v5 起：自定义图标文字（1~2 字）
    val type: String? = null,            // 旧版（v1）账户类型，恢复时自动映射图标
    val initialBalance: Long, val enableDate: Long, val sortOrder: Int, val archived: Boolean,
    val enableTimeOfDay: Int = -1,       // v4 起：启用当天分钟数；旧备份无此字段，默认 -1
)

@Serializable
data class CategoryDto(
    val id: Long, val bookId: Long, val type: String, val parentId: Long?,
    val name: String, val sortOrder: Int, val color: Long, val iconText: String,
)

@Serializable
data class TransactionDto(
    val id: Long, val bookId: Long, val type: String, val amount: Long,
    val businessDate: Long, val createdAt: Long, val note: String,
    val categoryId: Long?, val accountId: Long, val toAccountId: Long?, val fee: Long,
    val advanceAmount: Long, val recoveredAmount: Long,
    val timeOfDay: Int = -1,              // v3 起：当天分钟数；旧备份无此字段，默认 -1
    val project: String = "",             // v6 起：项目名称；旧备份无此字段
    val groupId: Long? = null,            // v1.7 起：条目组号；旧备份无此字段
    val entryRole: String = "NORMAL",     // v1.7.2 起：条目角色（A钱条/报销条）；旧备份默认 NORMAL
    val refunds: List<RefundDto> = emptyList(),
    val reimbursements: List<ReimburseDto> = emptyList(),  // v1.7.1 及以前的报销子表，恢复时转为报销条目流水
    val advances: List<AdvanceDto> = emptyList(),   // v1.7.1 及以前的代付子表，恢复时转为 A钱条目流水
    val attachments: List<String> = emptyList(),
)

@Serializable
data class RefundDto(
    val id: Long, val amount: Long, val note: String, val createdAt: Long,
    val accountId: Long? = null,      // v6 起：退款账户；旧备份按流水账户处理
    val timeMillis: Long? = null,     // v6 起：退款发生时间；旧备份按录入时间处理
)

@Serializable
data class ReimburseDto(
    val id: Long, val amount: Long, val note: String, val createdAt: Long,
    val accountId: Long? = null,
    val timeMillis: Long? = null,
)

@Serializable
data class AdvanceDto(
    val id: Long, val person: String,
    val amount: Long? = null, val accountId: Long? = null,
    val timeMillis: Long? = null, val note: String = "",
)

@Serializable
data class BudgetDto(
    val id: Long, val bookId: Long, val categoryId: Long?, val yearMonth: String, val amount: Long,
)

// ---------- DTO ↔ 实体 转换 ----------

fun BookEntity.toDto() = BookDto(id, name, createdAt, isDefault)
fun BookDto.toEntity() = BookEntity(id, name, createdAt, isDefault)

fun AccountEntity.toDto() = AccountDto(id, bookId, name, iconKey, initialBalance = initialBalance, enableDate = enableDate, sortOrder = sortOrder, archived = archived, enableTimeOfDay = enableTimeOfDay)
fun AccountDto.toEntity() = AccountEntity(
    id, bookId, name,
    com.youshu.ledger.ui.common.iconTextFromLegacyKey(
        iconKey.ifBlank { com.youshu.ledger.ui.common.iconTextFromLegacyType(type) },
    ),
    initialBalance, enableDate, enableTimeOfDay, sortOrder, archived,
)

fun CategoryEntity.toDto() = CategoryDto(id, bookId, type.name, parentId, name, sortOrder, color, iconText)
// v1.5.1 起分类全局共享：恢复时 bookId 统一置 0
fun CategoryDto.toEntity() = CategoryEntity(id, 0, CategoryType.valueOf(type), parentId, name, sortOrder, color, iconText)

fun RefundRecordEntity.toDto() = RefundDto(id, amount, note, createdAt, accountId, timeMillis)
fun RefundDto.toEntity(txnId: Long, defaultAccountId: Long) = RefundRecordEntity(
    id, txnId, amount,
    accountId = accountId ?: defaultAccountId,
    timeMillis = timeMillis ?: createdAt,
    note, createdAt,
)

fun BudgetEntity.toDto() = BudgetDto(id, bookId, categoryId, yearMonth, amount)
fun BudgetDto.toEntity() = BudgetEntity(id, bookId, categoryId, yearMonth, amount)

/**
 * v1.7.1 及更早备份的报销子表 → 报销条目流水（REIMBURSE 收入流水，挂入宿主条目组）。
 * 与数据库迁移 MIGRATION_10_11 同一口径。
 */
fun ReimburseDto.toRoleTxn(host: TransactionDto): TransactionEntity {
    val millis = timeMillis ?: createdAt
    return TransactionEntity(
        id = 0, bookId = host.bookId, type = TxnType.INCOME, amount = amount,
        businessDate = millis / 86400000 * 86400000,
        timeOfDay = ((millis % 86400000) / 60000).toInt(),
        createdAt = createdAt, note = note, categoryId = null,
        accountId = accountId ?: host.accountId, toAccountId = null, fee = 0,
        groupId = host.groupId ?: host.id,
        entryRole = TxnRole.REIMBURSE,
    )
}

/** v1.7.1 及更早备份的代付子表 → A钱条目流水（ADVANCE 收入流水；「谁」并入备注） */
fun AdvanceDto.toRoleTxn(host: TransactionDto): TransactionEntity {
    val millis = timeMillis
    return TransactionEntity(
        id = 0, bookId = host.bookId, type = TxnType.INCOME, amount = amount ?: 0,
        businessDate = millis?.let { it / 86400000 * 86400000 } ?: host.businessDate,
        timeOfDay = millis?.let { ((it % 86400000) / 60000).toInt() } ?: -1,
        createdAt = host.createdAt,
        note = person + if (note.isNotBlank()) "：$note" else "",
        categoryId = null,
        accountId = accountId ?: host.accountId, toAccountId = null, fee = 0,
        groupId = host.groupId ?: host.id,
        entryRole = TxnRole.ADVANCE,
    )
}

/**
 * 附件路径重映射到本机私有目录：
 * 备份中可能带有其他设备的绝对路径，统一截取 "attachments/" 之后的相对路径，
 * 使其始终指向本应用 filesDir 下的 attachments/ 目录。
 */
fun remapAttachmentPath(path: String): String {
    val idx = path.indexOf("attachments/")
    return if (idx >= 0) path.substring(idx) else "attachments/$path"
}
