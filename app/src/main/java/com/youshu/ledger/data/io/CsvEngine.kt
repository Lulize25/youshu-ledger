package com.youshu.ledger.data.io

import com.youshu.ledger.data.local.dao.TransactionDao
import com.youshu.ledger.data.local.entity.AccountEntity
import com.youshu.ledger.data.local.entity.BookEntity
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.CategoryType
import com.youshu.ledger.data.local.entity.TransactionEntity
import com.youshu.ledger.data.local.entity.TransactionWithDetails
import com.youshu.ledger.data.local.entity.TxnType
import com.youshu.ledger.data.repository.BookRepository
import com.youshu.ledger.data.repository.CategoryRepository
import com.youshu.ledger.domain.util.formatDay
import com.youshu.ledger.domain.util.formatTime
import com.youshu.ledger.domain.util.toYuan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVPrinter
import org.apache.commons.csv.CSVRecord
import java.io.InputStream
import java.io.StringWriter

/**
 * CSV 导入导出引擎。
 *
 * 列定义（UTF-8 带 BOM）：
 * 账本、类型、业务日期、录入时间、分类（完整路径）、账户、对方账户（转账）、
 * 金额、备注、退款合计、报销合计、代付、已收回、附件数量
 *
 * 重复检测：同一账本内 业务日期+金额+账户+类型 相同视为重复，默认跳过，可选覆盖。
 */
object CsvEngine {

    val HEADERS: List<String> = listOf(
        "账本", "类型", "业务日期", "录入时间", "分类（完整路径）", "账户", "对方账户（转账）",
        "金额", "备注", "退款合计", "报销合计", "代付", "已收回", "附件数量",
    )

    // ---------- 导出 ----------

    /**
     * 生成 CSV 文本（带 BOM）。
     * @param txns 流水（含子表）
     * @param bookNameOf 账本 id → 名称
     * @param accountNameOf 账户 id → 名称
     * @param categoryPathOf 分类 id → 完整路径（如「餐饮/外卖」）
     */
    fun buildCsv(
        txns: List<TransactionWithDetails>,
        bookNameOf: (Long) -> String,
        accountNameOf: (Long) -> String,
        categoryPathOf: (Long?) -> String,
    ): String {
        val sw = StringWriter()
        // UTF-8 BOM，Excel 打开不乱码
        sw.write("\uFEFF")
        val printer = CSVPrinter(sw, CSVFormat.DEFAULT.withHeader(*HEADERS.toTypedArray()))
        txns.sortedWith(compareBy({ it.transaction.businessDate }, { it.transaction.createdAt })).forEach { d ->
            val t = d.transaction
            printer.printRecord(
                bookNameOf(t.bookId),
                when (t.type) {
                    TxnType.EXPENSE -> "支出"; TxnType.INCOME -> "收入"; TxnType.TRANSFER -> "转账"
                },
                t.businessDate.formatDay(),
                t.createdAt.formatTime(),
                categoryPathOf(t.categoryId),
                accountNameOf(t.accountId),
                t.toAccountId?.let { accountNameOf(it) } ?: "",
                t.amount.toYuan(),
                t.note,
                d.refunds.sumOf { it.amount }.toYuan(),
                d.reimbursements.sumOf { it.amount }.toYuan(),
                t.advanceAmount.toYuan(),
                t.recoveredAmount.toYuan(),
                d.attachments.size,
            )
        }
        printer.flush()
        return sw.toString()
    }

    // ---------- 解析与映射 ----------

    /** 目标字段（导入列映射的可选项） */
    enum class Field(val label: String) {
        BOOK("账本"),
        TYPE("类型"),
        DATE("业务日期"),
        TIME("录入时间"),
        CATEGORY("分类"),
        ACCOUNT("账户"),
        TO_ACCOUNT("对方账户"),
        AMOUNT("金额"),
        NOTE("备注"),
        REFUND("退款合计"),
        REIMBURSE("报销合计"),
        ADVANCE("代付"),
        RECOVERED("已收回"),
        SKIP("忽略"),
    }

    /** CSV 解析结果：原始表头与数据行 */
    data class ParsedCsv(val headers: List<String>, val rows: List<CSVRecord>)

    /** 解析输入流（先剥离 UTF-8 BOM，避免残留到第一个表头） */
    fun parse(input: InputStream): ParsedCsv {
        val text = input.bufferedReader(Charsets.UTF_8).readText().removePrefix("\uFEFF")
        return with(CSVParser.parse(text, CSVFormat.DEFAULT.withFirstRecordAsHeader())) {
            ParsedCsv(headerNames, records)
        }
    }

    /** 依据表头自动猜测列映射 */
    fun guessMapping(headers: List<String>): Map<Field, Int> {
        fun findIdx(vararg keys: String): Int? {
            for (h in headers.indices) {
                val name = headers[h].trim()
                if (keys.any { name.contains(it) }) return h
            }
            return null
        }
        return buildMap {
            findIdx("账本")?.let { put(Field.BOOK, it) }
            findIdx("类型")?.let { put(Field.TYPE, it) }
            findIdx("业务日期", "日期")?.let { put(Field.DATE, it) }
            findIdx("录入时间")?.let { put(Field.TIME, it) }
            findIdx("分类")?.let { put(Field.CATEGORY, it) }
            findIdx("账户")?.let { put(Field.ACCOUNT, it) }
            findIdx("对方账户")?.let { put(Field.TO_ACCOUNT, it) }
            findIdx("金额")?.let { put(Field.AMOUNT, it) }
            findIdx("备注")?.let { put(Field.NOTE, it) }
            findIdx("退款")?.let { put(Field.REFUND, it) }
            findIdx("报销")?.let { put(Field.REIMBURSE, it) }
            findIdx("代付")?.let { put(Field.ADVANCE, it) }
            findIdx("已收回")?.let { put(Field.RECOVERED, it) }
        }
    }

    // ---------- 导入 ----------

    data class ImportResult(
        val success: Int,
        val skipped: Int,
        val failed: Int,
        val errors: List<String>,
    )

    /**
     * 执行导入。
     * @param rows 数据行
     * @param mapping 目标字段 → 列号
     * @param overrideDuplicate 重复时是否覆盖（默认跳过）
     * @param defaultBookId 无「账本」列时的目标账本
     */
    suspend fun import(
        rows: List<CSVRecord>,
        mapping: Map<Field, Int>,
        overrideDuplicate: Boolean,
        defaultBookId: Long,
        bookRepo: BookRepository,
        accountDao: com.youshu.ledger.data.local.dao.AccountDao,
        categoryDao: com.youshu.ledger.data.local.dao.CategoryDao,
        txnDao: TransactionDao,
    ): ImportResult = withContext(Dispatchers.IO) {
        var success = 0
        var skipped = 0
        var failed = 0
        val errors = mutableListOf<String>()

        // 缓存：名称 → 实体（按账本隔离）
        val bookCache = HashMap<String, BookEntity>()
        val accountCache = HashMap<Pair<Long, String>, AccountEntity>()
        val categoryCache = HashMap<Pair<Long, String>, CategoryEntity>()

        suspend fun resolveBook(name: String?): BookEntity {
            val key = name?.takeIf { it.isNotBlank() } ?: run {
                return bookCache.values.firstOrNull { it.id == defaultBookId }
                    ?: bookRepo.getById(defaultBookId)
                    ?: throw IllegalStateException("目标账本不存在")
            }
            return bookCache.getOrPut(key) {
                val existing = bookRepo.findByName(key)
                existing ?: BookEntity(
                    id = bookRepo.createBook(key),
                    name = key,
                    createdAt = System.currentTimeMillis(),
                    isDefault = false,
                )
            }
        }

        suspend fun resolveAccount(bookId: Long, name: String, txnDate: Long): AccountEntity {
            val key = bookId to name
            return accountCache.getOrPut(key) {
                val existing = accountDao.getByBook(bookId).firstOrNull { it.name == name }
                existing ?: AccountEntity(
                    id = accountDao.upsert(
                        AccountEntity(
                            id = 0, bookId = bookId, name = name, iconKey = "other",
                            initialBalance = 0, enableDate = txnDate, sortOrder = 0, archived = false,
                        ),
                    ),
                    bookId = bookId, name = name, iconKey = "other",
                    initialBalance = 0, enableDate = txnDate, sortOrder = 0, archived = false,
                )
            }
        }

        suspend fun resolveCategory(
            bookId: Long,
            path: String?,
            type: TxnType,
        ): CategoryEntity? {
            if (type == TxnType.TRANSFER) return null
            val leaf = path?.takeIf { it.isNotBlank() }?.split('/')?.last() ?: "未分类"
            val key = bookId to leaf
            return categoryCache.getOrPut(key) {
                val catType = when (type) {
                    TxnType.EXPENSE -> CategoryType.EXPENSE; else -> CategoryType.INCOME
                }
                val existing = categoryDao.getByBookAndType(bookId, catType).firstOrNull { it.name == leaf }
                existing ?: CategoryEntity(
                    id = categoryDao.upsert(
                        CategoryEntity(
                            id = 0, bookId = bookId, type = catType, parentId = null,
                            name = leaf, sortOrder = 0,
                            color = DefaultColors.next(), iconText = leaf.take(2),
                        ),
                    ),
                    bookId = bookId, type = catType, parentId = null,
                    name = leaf, sortOrder = 0,
                    color = 0xFF90A4AE, iconText = leaf.take(2),
                )
            }
        }

        rows.forEachIndexed { index, row ->
            runCatching {
                val col = { f: Field -> mapping[f]?.let { row.getOrNull(it) }?.trim() }
                val book = resolveBook(col(Field.BOOK))
                val type = when (col(Field.TYPE)) {
                    "支出", "EXPENSE", "expense" -> TxnType.EXPENSE
                    "收入", "INCOME", "income" -> TxnType.INCOME
                    "转账", "TRANSFER", "transfer" -> TxnType.TRANSFER
                    else -> throw IllegalArgumentException("无法识别的类型「${col(Field.TYPE)}」")
                }
                val amount = col(Field.AMOUNT)?.replace(",", "")?.toDoubleOrNull()
                    ?.let { (it * 100).toLong() }
                    ?: throw IllegalArgumentException("金额缺失或非法")
                val dateStr = col(Field.DATE) ?: throw IllegalArgumentException("业务日期缺失")
                val businessDate = com.youshu.ledger.domain.util.parseDayMillis(dateStr)
                    ?: throw IllegalArgumentException("业务日期格式非法：$dateStr")
                val accountName = col(Field.ACCOUNT) ?: throw IllegalArgumentException("账户缺失")
                val account = resolveAccount(book.id, accountName, businessDate)
                val category = resolveCategory(book.id, col(Field.CATEGORY), type)

                // 重复检测：同一账本内 业务日期+金额+账户+类型 相同
                val dupes = txnDao.findDuplicate(book.id, type, amount, businessDate, account.id)
                if (dupes.isNotEmpty()) {
                    if (overrideDuplicate) {
                        dupes.forEach { txnDao.deleteById(it.id) }
                    } else {
                        skipped++
                        return@runCatching
                    }
                }

                val toAccount = col(Field.TO_ACCOUNT)?.takeIf { it.isNotBlank() }?.let {
                    resolveAccount(book.id, it, businessDate)
                }
                val createdAt = col(Field.TIME)
                    ?.let { com.youshu.ledger.domain.util.parseDayMillis(it.substringBefore(' ')) ?: businessDate }
                    ?: businessDate

                val txnId = txnDao.upsert(
                    TransactionEntity(
                        id = 0, bookId = book.id, type = type, amount = amount,
                        businessDate = businessDate, createdAt = createdAt,
                        note = col(Field.NOTE) ?: "",
                        categoryId = category?.id,
                        accountId = account.id,
                        toAccountId = toAccount?.id,
                        fee = 0,
                        advanceAmount = col(Field.ADVANCE)?.replace(",", "")?.toDoubleOrNull()?.let { (it * 100).toLong() } ?: 0,
                        recoveredAmount = col(Field.RECOVERED)?.replace(",", "")?.toDoubleOrNull()?.let { (it * 100).toLong() } ?: 0,
                    ),
                )
                // 退款/报销合计作为一条汇总记录导入（与原支出同账户）
                col(Field.REFUND)?.replace(",", "")?.toDoubleOrNull()?.let { if (it > 0) (it * 100).toLong() else null }
                    ?.let { txnDao.upsertRefund(com.youshu.ledger.data.local.entity.RefundRecordEntity(0, txnId, it, "CSV 导入", System.currentTimeMillis())) }
                col(Field.REIMBURSE)?.replace(",", "")?.toDoubleOrNull()?.let { if (it > 0) (it * 100).toLong() else null }
                    ?.let { txnDao.upsertReimburse(com.youshu.ledger.data.local.entity.ReimburseRecordEntity(0, txnId, it, "CSV 导入", System.currentTimeMillis())) }
                success++
            }.onFailure { e ->
                failed++
                errors.add("第 ${index + 2} 行：${e.message}")
            }
        }
        ImportResult(success, skipped, failed, errors.take(5))
    }

    /** 预览用：把一行按当前映射转成可读摘要 */
    fun previewRow(row: CSVRecord, mapping: Map<Field, Int>): List<String> =
        Field.entries.filter { it != Field.SKIP && mapping.containsKey(it) }
            .map { "${it.label}: ${mapping[it]?.let { i -> row.getOrNull(i) } ?: ""}" }

    private object DefaultColors {
        private val colors = listOf(0xFFE57373, 0xFF64B5F6, 0xFF81C784, 0xFFFFD54F, 0xFFBA68C8)
        private var idx = 0
        fun next(): Long = colors[idx++ % colors.size]
    }
}

private fun CSVRecord.getOrNull(i: Int): String? = if (i < size()) get(i) else null
