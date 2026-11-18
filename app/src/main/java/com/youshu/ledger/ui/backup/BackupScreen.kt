package com.youshu.ledger.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.youshu.ledger.data.AppContainer
import com.youshu.ledger.data.io.CsvEngine
import com.youshu.ledger.domain.util.formatDay
import com.youshu.ledger.ui.common.ConfirmDialog
import com.youshu.ledger.ui.common.DayPickerDialog
import com.youshu.ledger.ui.common.LedgerTopBar
import com.youshu.ledger.ui.theme.expense
import com.youshu.ledger.domain.util.todayMillis
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据备份与恢复：
 * - CSV 导出（UTF-8 带 BOM；可选日期区间、当前账本/全部）
 * - ZIP 导出（manifest.csv + attachments/{流水id}/ 附件图片）
 * - CSV 导入（选文件 → 列映射 → 预览 → 重复检测跳过/覆盖 → 完成报告）
 * - JSON 全量备份 / 恢复（全量替换，二次确认；恢复时附件路径重映射到私有目录）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: BackupViewModel = viewModel(factory = BackupViewModel.factory(container, context))
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val csvImport by vm.csvImport.collectAsState()
    val importReport by vm.importReport.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 导出选项
    var allBooks by remember { mutableStateOf(false) }
    var limitRange by remember { mutableStateOf(false) }
    var startDate by remember { mutableStateOf<Long?>(null) }
    var endDate by remember { mutableStateOf<Long?>(null) }
    var dateTarget by remember { mutableStateOf<String?>(null) }
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val stamp = remember { SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Date()) }

    // 待执行的导出动作（等 SAF 返回 uri 后执行）
    var pendingExport by remember { mutableStateOf<((android.net.Uri) -> Unit)?>(null) }

    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { pendingExport?.invoke(it) }
        pendingExport = null
    }
    val createZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { pendingExport?.invoke(it) }
        pendingExport = null
    }
    val createJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { pendingExport?.invoke(it) }
        pendingExport = null
    }
    val openCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.csvParse(it) }
    }
    val openJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pendingRestoreUri = it }
    }

    message?.let {
        androidx.compose.runtime.LaunchedEffect(it) {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        topBar = { LedgerTopBar(title = "数据备份与恢复", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 导出选项 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("导出选项", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !allBooks, onClick = { allBooks = false }, label = { Text("当前账本") })
                        FilterChip(selected = allBooks, onClick = { allBooks = true }, label = { Text("全部账本") })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = limitRange, onCheckedChange = { limitRange = it })
                        Text("限定日期区间")
                    }
                    if (limitRange) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { dateTarget = "start" }) {
                                Text("从 ${startDate?.formatDay() ?: "…"}")
                            }
                            OutlinedButton(onClick = { dateTarget = "end" }) {
                                Text("到 ${endDate?.formatDay() ?: "…"}")
                            }
                        }
                    }
                }
            }

            val rangeError = limitRange && startDate != null && endDate != null && startDate!! > endDate!!
            if (rangeError) {
                Text("起始日期不能晚于结束日期", color = MaterialTheme.colorScheme.expense, style = MaterialTheme.typography.labelMedium)
            }

            // ---- 导出按钮 ----
            Button(
                enabled = !busy && !rangeError,
                onClick = {
                    pendingExport = { uri ->
                        vm.exportCsv(uri, if (limitRange) startDate else null, if (limitRange) endDate else null, allBooks)
                    }
                    createCsv.launch("记一笔_${stamp}.csv")
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("导出 CSV（UTF-8 带 BOM）") }

            Button(
                enabled = !busy && !rangeError,
                onClick = {
                    pendingExport = { uri ->
                        vm.exportZip(uri, if (limitRange) startDate else null, if (limitRange) endDate else null, allBooks)
                    }
                    createZip.launch("记一笔_${stamp}.zip")
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("导出 ZIP（含附件图片）") }

            HorizontalDivider()

            // ---- CSV 导入 ----
            OutlinedButton(
                enabled = !busy,
                onClick = { openCsv.launch(arrayOf("text/*", "text/csv", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("导入 CSV（列映射 → 预览 → 查重）") }

            HorizontalDivider()

            // ---- JSON 备份/恢复 ----
            Button(
                enabled = !busy,
                onClick = {
                    pendingExport = { uri -> vm.exportJson(uri) }
                    createJson.launch("记一笔备份_${stamp}.json")
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("JSON 全量备份") }

            OutlinedButton(
                enabled = !busy,
                onClick = { openJson.launch(arrayOf("application/json", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("从 JSON 备份恢复（全量替换）") }

            Text(
                "恢复将全量替换现有账本、账户、分类、流水与预算，且不可撤销；附件路径会重映射到本机私有目录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text("处理中…")
                }
            }
        }
    }

    // ---- 日期选择 ----
    dateTarget?.let { target ->
        DayPickerDialog(
            initialMillis = (if (target == "start") startDate else endDate) ?: todayMillis(),
            onConfirm = { millis ->
                if (target == "start") startDate = millis else endDate = millis
                dateTarget = null
            },
            onDismiss = { dateTarget = null },
        )
    }

    // ---- CSV 导入向导 ----
    csvImport?.let { state ->
        CsvImportWizard(
            state = state,
            onMapping = vm::csvUpdateMapping,
            onOverride = vm::csvSetOverride,
            onRun = vm::csvRunImport,
            onCancel = vm::csvCancelImport,
        )
    }

    // ---- 导入报告 ----
    importReport?.let { report ->
        AlertDialog(
            onDismissRequest = vm::clearImportReport,
            title = { Text("导入完成报告") },
            text = {
                Column {
                    Text("成功 ${report.success} 条，跳过（重复）${report.skipped} 条，失败 ${report.failed} 条")
                    if (report.errors.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        report.errors.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.expense) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::clearImportReport) { Text("知道了") } },
        )
    }

    // ---- 恢复二次确认 ----
    pendingRestoreUri?.let { uri ->
        ConfirmDialog(
            title = "确认恢复",
            text = "恢复将全量替换当前所有数据（账本/账户/分类/流水/预算），此操作不可撤销。确定继续？",
            confirmText = "恢复",
            onConfirm = {
                vm.restoreJson(uri)
                pendingRestoreUri = null
            },
            onDismiss = { pendingRestoreUri = null },
        )
    }
}

/** CSV 导入向导：列映射 → 预览 → 重复策略 → 执行 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CsvImportWizard(
    state: CsvImportState,
    onMapping: (Map<CsvEngine.Field, Int>) -> Unit,
    onOverride: (Boolean) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("CSV 导入（共 ${state.rows.size} 行）") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("列映射（按表头对应目标字段）", style = MaterialTheme.typography.labelLarge)
                CsvEngine.Field.entries.forEach { field ->
                    var menu by remember { mutableStateOf(false) }
                    val selectedIdx = state.mapping[field]
                    ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = it }) {
                        OutlinedTextField(
                            value = selectedIdx?.let { state.headers.getOrNull(it) } ?: "忽略",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(field.label) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("忽略") },
                                onClick = {
                                    onMapping(state.mapping - field)
                                    menu = false
                                },
                            )
                            state.headers.forEachIndexed { idx, header ->
                                DropdownMenuItem(
                                    text = { Text(header) },
                                    onClick = {
                                        onMapping(state.mapping + (field to idx))
                                        menu = false
                                    },
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()
                Text("预览（前 3 行）", style = MaterialTheme.typography.labelLarge)
                state.rows.take(3).forEach { row ->
                    Text(
                        CsvEngine.previewRow(row, state.mapping).joinToString("；"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = state.overrideDuplicate, onCheckedChange = onOverride)
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text(
                        if (state.overrideDuplicate) "重复时：覆盖旧记录" else "重复时：跳过（默认）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "重复判定：同一账本内 业务日期 + 金额 + 账户 + 类型 完全相同。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = state.mapping[CsvEngine.Field.AMOUNT] != null &&
                    state.mapping[CsvEngine.Field.DATE] != null &&
                    state.mapping[CsvEngine.Field.ACCOUNT] != null &&
                    state.mapping[CsvEngine.Field.TYPE] != null,
                onClick = onRun,
            ) { Text("开始导入") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}
