package com.youshu.ledger.ui.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.youshu.ledger.data.AppContainer
import com.youshu.ledger.data.io.CsvEngine
import com.youshu.ledger.data.local.entity.BookEntity
import com.youshu.ledger.data.local.entity.TransactionWithDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.csv.CSVRecord
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** CSV 导入向导状态 */
data class CsvImportState(
    val headers: List<String> = emptyList(),
    val rows: List<CSVRecord> = emptyList(),
    val mapping: Map<CsvEngine.Field, Int> = emptyMap(),
    val overrideDuplicate: Boolean = false,
    val imported: Boolean = false,
)

/**
 * 导入导出 ViewModel：CSV/ZIP 导出、CSV 导入向导、JSON 全量备份/恢复。
 * 文件读写通过 SAF uri 由 UI 层传入输出/输入流。
 */
class BackupViewModel(
    private val container: AppContainer,
    private val appContext: Context,
) : ViewModel() {

    private val bookRepo = container.bookRepository
    private val txnDao = container.transactionDao
    private val categoryRepo = container.categoryRepository
    private val accountDao = container.accountDao
    private val categoryDao = container.categoryDao
    private val backupManager = container.backupManager

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _csvImport = MutableStateFlow<CsvImportState?>(null)
    val csvImport: StateFlow<CsvImportState?> = _csvImport

    private val _importReport = MutableStateFlow<CsvEngine.ImportResult?>(null)
    val importReport: StateFlow<CsvEngine.ImportResult?> = _importReport

    fun clearMessage() { _message.value = null }
    fun clearImportReport() { _importReport.value = null }

    // ---------- 导出 ----------

    /** 组装导出数据（可选日期区间与账本范围） */
    private suspend fun collectTxns(startDate: Long?, endDate: Long?, allBooks: Boolean): Pair<Map<Long, BookEntity>, List<TransactionWithDetails>> {
        val books = bookRepo.observeAll().first()
        val scopeBooks = if (allBooks) books else books.filter { it.id == bookRepo.currentBook.first()?.id }
        val bookMap = scopeBooks.associateBy { it.id }
        val txns = scopeBooks.flatMap { txnDao.getByBook(it.id) }
            .filter { d ->
                (startDate == null || d.transaction.businessDate >= startDate) &&
                    (endDate == null || d.transaction.businessDate <= endDate)
            }
        return bookMap to txns
    }

    fun exportCsv(uri: Uri, startDate: Long?, endDate: Long?, allBooks: Boolean) {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val (bookMap, txns) = collectTxns(startDate, endDate, allBooks)
                val accounts = bookMap.keys.flatMap { accountDao.getByBook(it) }.associateBy { it.id }
                val categories = bookMap.keys.flatMap { categoryDao.getByBook(it) }
                // 预计算分类完整路径（如「餐饮/外卖」）
                val pathCache = HashMap<Long, String>()
                txns.mapNotNull { it.transaction.categoryId }.distinct().forEach { cid ->
                    pathCache[cid] = categoryRepo.fullPath(cid, categories)
                }
                val csv = CsvEngine.buildCsv(
                    txns,
                    bookNameOf = { bookMap[it]?.name ?: "未知账本" },
                    accountNameOf = { accounts[it]?.name ?: "未知账户" },
                    categoryPathOf = { id -> if (id == null) "" else pathCache[id] ?: "" },
                )
                writeToUri(uri) { out -> out.write(csv.toByteArray(Charsets.UTF_8)) }
                "已导出 ${txns.size} 条流水到 CSV"
            }.onSuccess { _message.value = it }.onFailure { _message.value = "导出失败：${it.message}" }
            _busy.value = false
        }
    }

    /** ZIP 导出：manifest.csv（同 CSV 格式）+ attachments/{流水id}/ 下的附件图片 */
    fun exportZip(uri: Uri, startDate: Long?, endDate: Long?, allBooks: Boolean) {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val (bookMap, txns) = collectTxns(startDate, endDate, allBooks)
                val accounts = bookMap.keys.flatMap { accountDao.getByBook(it) }.associateBy { it.id }
                val categories = bookMap.keys.flatMap { categoryDao.getByBook(it) }
                // 预计算分类完整路径（如「餐饮/外卖」）
                val pathCache = HashMap<Long, String>()
                txns.mapNotNull { it.transaction.categoryId }.distinct().forEach { cid ->
                    pathCache[cid] = categoryRepo.fullPath(cid, categories)
                }
                val csv = CsvEngine.buildCsv(
                    txns,
                    bookNameOf = { bookMap[it]?.name ?: "未知账本" },
                    accountNameOf = { accounts[it]?.name ?: "未知账户" },
                    categoryPathOf = { id -> if (id == null) "" else pathCache[id] ?: "" },
                )
                writeToUri(uri) { out ->
                    ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                        zip.putNextEntry(ZipEntry("manifest.csv"))
                        zip.write(csv.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                        txns.forEach { d ->
                            d.attachments.forEach { att ->
                                val file = File(container.filesDir, att.relativePath)
                                if (file.exists()) {
                                    zip.putNextEntry(ZipEntry("attachments/${d.transaction.id}/${file.name}"))
                                    file.inputStream().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                            }
                        }
                    }
                }
                "已导出 ZIP（${txns.size} 条流水及附件）"
            }.onSuccess { _message.value = it }.onFailure { _message.value = "导出失败：${it.message}" }
            _busy.value = false
        }
    }

    // ---------- JSON 备份/恢复 ----------

    fun exportJson(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                writeToUri(uri) { out -> backupManager.writeBackup(out) }
                "JSON 全量备份完成"
            }.onSuccess { _message.value = it }.onFailure { _message.value = "备份失败：${it.message}" }
            _busy.value = false
        }
    }

    fun restoreJson(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.IO) {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    backupManager.restore(input)
                } ?: Result.failure(Exception("无法读取所选文件"))
            }
            _message.value = result.fold(
                onSuccess = { "恢复完成：$it 条流水，账本/账户/分类/预算与备份一致" },
                onFailure = { "恢复失败：${it.message}" },
            )
            _busy.value = false
        }
    }

    // ---------- CSV 导入向导 ----------

    /** 第一步：解析文件并自动猜测列映射 */
    fun csvParse(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        CsvEngine.parse(input)
                    } ?: error("无法读取所选文件")
                }
            }.onSuccess { parsed ->
                _csvImport.value = CsvImportState(
                    headers = parsed.headers,
                    rows = parsed.rows,
                    mapping = CsvEngine.guessMapping(parsed.headers),
                )
                _busy.value = false
            }.onFailure {
                _message.value = "解析失败：${it.message}"
                _busy.value = false
            }
        }
    }

    fun csvUpdateMapping(mapping: Map<CsvEngine.Field, Int>) {
        _csvImport.value = _csvImport.value?.copy(mapping = mapping)
    }

    fun csvSetOverride(override: Boolean) {
        _csvImport.value = _csvImport.value?.copy(overrideDuplicate = override)
    }

    fun csvCancelImport() {
        _csvImport.value = null
    }

    /** 第三步：执行导入并生成报告 */
    fun csvRunImport() {
        val state = _csvImport.value ?: return
        viewModelScope.launch {
            _busy.value = true
            val targetBook = bookRepo.currentBook.first() ?: return@launch
            val report = CsvEngine.import(
                rows = state.rows,
                mapping = state.mapping,
                overrideDuplicate = state.overrideDuplicate,
                defaultBookId = targetBook.id,
                bookRepo = bookRepo,
                accountDao = accountDao,
                categoryDao = categoryDao,
                txnDao = txnDao,
            )
            _importReport.value = report
            _csvImport.value = null
            _busy.value = false
        }
    }

    // ---------- 工具 ----------

    private suspend fun writeToUri(uri: Uri, block: suspend (OutputStream) -> Unit) =
        withContext(Dispatchers.IO) {
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { out -> block(out) }
                ?: error("无法写入所选位置")
        }

    companion object {
        fun factory(container: AppContainer, context: Context) = viewModelFactory {
            initializer { BackupViewModel(container, context.applicationContext) }
        }
    }
}
