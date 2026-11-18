package com.youshu.ledger.data.io

import com.youshu.ledger.data.local.entity.AccountEntity
import com.youshu.ledger.data.local.entity.AttachmentEntity
import com.youshu.ledger.data.local.entity.BookEntity
import com.youshu.ledger.data.local.entity.BudgetEntity
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.CategoryType
import com.youshu.ledger.data.local.entity.ReimburseRecordEntity
import com.youshu.ledger.data.local.entity.RefundRecordEntity
import com.youshu.ledger.data.local.entity.TransactionEntity
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
    val iconKey: String = "",            // v2 起：预置图标 key
    val type: String? = null,            // 旧版（v1）账户类型，恢复时自动映射图标
    val initialBalance: Long, val enableDate: Long, val sortOrder: Int, val archived: Boolean,
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
    val refunds: List<RefundDto> = emptyList(),
    val reimbursements: List<ReimburseDto> = emptyList(),
    val attachments: List<String> = emptyList(),
)

@Serializable
data class RefundDto(val id: Long, val amount: Long, val note: String, val createdAt: Long)

@Serializable
data class ReimburseDto(val id: Long, val amount: Long, val note: String, val createdAt: Long)

@Serializable
data class BudgetDto(
    val id: Long, val bookId: Long, val categoryId: Long?, val yearMonth: String, val amount: Long,
)

// ---------- DTO ↔ 实体 转换 ----------

fun BookEntity.toDto() = BookDto(id, name, createdAt, isDefault)
fun BookDto.toEntity() = BookEntity(id, name, createdAt, isDefault)

fun AccountEntity.toDto() = AccountDto(id, bookId, name, iconKey, initialBalance = initialBalance, enableDate = enableDate, sortOrder = sortOrder, archived = archived)
fun AccountDto.toEntity() = AccountEntity(
    id, bookId, name,
    iconKey.ifBlank { com.youshu.ledger.ui.common.AccountIcons.fromLegacyType(type) },
    initialBalance, enableDate, sortOrder, archived,
)

fun CategoryEntity.toDto() = CategoryDto(id, bookId, type.name, parentId, name, sortOrder, color, iconText)
fun CategoryDto.toEntity() = CategoryEntity(id, bookId, CategoryType.valueOf(type), parentId, name, sortOrder, color, iconText)

fun RefundRecordEntity.toDto() = RefundDto(id, amount, note, createdAt)
fun RefundDto.toEntity(txnId: Long) = RefundRecordEntity(id, txnId, amount, note, createdAt)

fun ReimburseRecordEntity.toDto() = ReimburseDto(id, amount, note, createdAt)
fun ReimburseDto.toEntity(txnId: Long) = ReimburseRecordEntity(id, txnId, amount, note, createdAt)

fun BudgetEntity.toDto() = BudgetDto(id, bookId, categoryId, yearMonth, amount)
fun BudgetDto.toEntity() = BudgetEntity(id, bookId, categoryId, yearMonth, amount)

/**
 * 附件路径重映射到本机私有目录：
 * 备份中可能带有其他设备的绝对路径，统一截取 "attachments/" 之后的相对路径，
 * 使其始终指向本应用 filesDir 下的 attachments/ 目录。
 */
fun remapAttachmentPath(path: String): String {
    val idx = path.indexOf("attachments/")
    return if (idx >= 0) path.substring(idx) else "attachments/$path"
}
