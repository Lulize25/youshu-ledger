package com.youshu.ledger.data.io

import android.content.Context
import com.youshu.ledger.data.local.dao.BookDao
import com.youshu.ledger.data.local.dao.BudgetDao
import com.youshu.ledger.data.local.dao.CategoryDao
import com.youshu.ledger.data.local.dao.TransactionDao
import com.youshu.ledger.data.local.entity.AttachmentEntity
import com.youshu.ledger.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * JSON 全量备份 / 恢复。
 * 备份包含：账本、账户、分类、流水（含退款/报销/附件）、预算、设置（生物识别开关、当前账本）。
 * 恢复为全量替换现有数据（调用方负责二次确认），保留原 id 以保证外键关系一致；
 * 附件路径重映射到本机私有目录（见 remapAttachmentPath）。
 */
class LedgerBackupManager(
    private val context: Context,
    private val bookDao: BookDao,
    private val accountDao: com.youshu.ledger.data.local.dao.AccountDao,
    private val categoryDao: CategoryDao,
    private val txnDao: TransactionDao,
    private val budgetDao: BudgetDao,
    private val settings: SettingsStore,
) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** 生成备份对象（从数据库读取全量数据） */
    suspend fun buildBackup(): BackupFile {
        val books = bookDao.getAll()
        val accounts = books.flatMap { accountDao.getByBook(it.id) }
        val categories = categoryDao.getAll()
        val txns = books.flatMap { txnDao.getByBook(it.id) }
        val budgets = books.flatMap { budgetDao.getAllByBook(it.id) }
        return BackupFile(
            exportedAt = System.currentTimeMillis(),
            biometricEnabled = settings.biometricEnabled.first(),
            currentBookId = settings.currentBookId.first(),
            books = books.map { it.toDto() },
            accounts = accounts.map { it.toDto() },
            categories = categories.map { it.toDto() },
            transactions = txns.map { d ->
                TransactionDto(
                    id = d.transaction.id, bookId = d.transaction.bookId,
                    type = d.transaction.type.name, amount = d.transaction.amount,
                    businessDate = d.transaction.businessDate, createdAt = d.transaction.createdAt,
                    note = d.transaction.note, categoryId = d.transaction.categoryId,
                    accountId = d.transaction.accountId, toAccountId = d.transaction.toAccountId,
                    fee = d.transaction.fee, advanceAmount = d.transaction.advanceAmount,
                    recoveredAmount = d.transaction.recoveredAmount,
                    timeOfDay = d.transaction.timeOfDay,
                    refunds = d.refunds.map { it.toDto() },
                    reimbursements = d.reimbursements.map { it.toDto() },
                    advances = d.advances.map { it.toDto() },
                    attachments = d.attachments.map { it.relativePath },
                )
            },
            budgets = budgets.map { it.toDto() },
        )
    }

    /** 写出 JSON 备份到输出流（SAF 目标） */
    suspend fun writeBackup(out: OutputStream): Unit = withContext(Dispatchers.IO) {
        val backup = buildBackup()
        out.write(json.encodeToString(BackupFile.serializer(), backup).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** 从输入流恢复（全量替换）。返回恢复的流水条数。 */
    suspend fun restore(input: InputStream): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = input.bufferedReader(Charsets.UTF_8).readText()
            val backup = json.decodeFromString(BackupFile.serializer(), text)

            // 1) 清空现有数据
            txnDao.clearAll()
            txnDao.clearAdvances()
            budgetDao.clearAll()
            categoryDao.clearAll()
            accountDao.clearAll()
            bookDao.clearAll()

            // 2) 写入账本与设置
            backup.books.forEach { bookDao.upsert(it.toEntity()) }
            settings.setBiometricEnabled(backup.biometricEnabled)
            backup.currentBookId?.let { settings.setCurrentBookId(it) }

            // 3) 账户
            backup.accounts.forEach { accountDao.upsert(it.toEntity()) }

            // 4) 分类：先父后子（源数据按 id 升序通常满足；这里显式排序保证）
            val cats = backup.categories.sortedBy { if (it.parentId == null) 0 else 1 }
            cats.forEach { categoryDao.upsert(it.toEntity()) }

            // 5) 流水 + 退款/报销/附件
            backup.transactions.forEach { t ->
                txnDao.upsert(
                    com.youshu.ledger.data.local.entity.TransactionEntity(
                        id = t.id, bookId = t.bookId,
                        type = com.youshu.ledger.data.local.entity.TxnType.valueOf(t.type),
                        amount = t.amount, businessDate = t.businessDate, createdAt = t.createdAt,
                        note = t.note, categoryId = t.categoryId, accountId = t.accountId,
                        toAccountId = t.toAccountId, fee = t.fee, project = t.project,
                        advanceAmount = t.advanceAmount, recoveredAmount = t.recoveredAmount,
                        timeOfDay = t.timeOfDay,
                    ),
                )
                t.refunds.forEach { txnDao.upsertRefund(it.toEntity(t.id, t.accountId)) }
                t.reimbursements.forEach { txnDao.upsertReimburse(it.toEntity(t.id, t.accountId)) }
                t.advances.forEach { txnDao.upsertAdvance(it.toEntity(t.id)) }
                t.attachments.forEach { rel ->
                    txnDao.upsertAttachment(AttachmentEntity(transactionId = t.id, relativePath = remapAttachmentPath(rel)))
                }
            }

            // 6) 预算
            backup.budgets.forEach { budgetDao.upsert(it.toEntity()) }

            settings.bumpDataVersion()
            backup.transactions.size
        }
    }

    /** 恢复附件文件：把某个 attachments 根目录（如 ZIP 解压结果）复制进私有目录 */
    suspend fun restoreAttachmentFiles(sourceDir: File) = withContext(Dispatchers.IO) {
        val targetRoot = File(context.filesDir, "attachments")
        sourceDir.listFiles()?.forEach { txnDir ->
            if (txnDir.isDirectory) {
                val target = File(targetRoot, txnDir.name)
                txnDir.copyRecursively(target, overwrite = true)
            }
        }
    }
}
