package com.youshu.ledger.ui.record

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.youshu.ledger.data.AppContainer
import com.youshu.ledger.data.local.entity.AccountEntity
import com.youshu.ledger.data.local.entity.AttachmentEntity
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.TxnType
import com.youshu.ledger.domain.util.formatDay
import com.youshu.ledger.domain.util.formatMinuteOfDay
import com.youshu.ledger.domain.util.toYuan
import com.youshu.ledger.ui.common.CategoryBadge
import com.youshu.ledger.ui.common.ConfirmDialog
import com.youshu.ledger.ui.common.DayPickerDialog
import com.youshu.ledger.ui.common.LedgerTopBar
import com.youshu.ledger.ui.theme.expense
import com.youshu.ledger.ui.theme.income
import kotlinx.coroutines.launch
import java.io.File

/**
 * 记一笔 / 编辑流水页。
 * 类型切换（支出/收入/转账）、金额键盘、分类两级网格、账户选择、
 * 业务日期（默认今天，可改到任意过去日期即补录）、备注、附件；
 * 支出类型展开退款/报销/代付区块（编辑已保存流水时可用）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    container: AppContainer,
    txnId: Long?,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val vm: RecordViewModel = viewModel(factory = RecordViewModel.factory(container, context, txnId))
    val s by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 金额输入框实时显示（草稿恢复时取已保存的金额文本）
    var amountText by remember { mutableStateOf(vm.restoredAmountText ?: "") }
    val amountRefresh = remember { mutableStateOf(0) }

    // 附件：相册多选
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9),
    ) { uris ->
        if (uris.isNotEmpty()) {
            vm.addAttachments(uris) { err ->
                scope.launch { snackbar.showSnackbar(err ?: "已添加 ${uris.size} 张附件") }
            }
        }
    }
    // 附件：拍照
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingPhoto
        if (ok && file != null) {
            vm.addAttachmentFile(file) { err ->
                scope.launch { snackbar.showSnackbar(err ?: "照片已保存为附件") }
            }
        }
        pendingPhoto = null
    }

    fun launchCamera() {
        val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingPhoto = file
        takePicture.launch(uri)
    }

    Scaffold(
        topBar = {
            LedgerTopBar(
                title = if (s.editing) "编辑流水" else "记一笔",
                onBack = onDone,
                actions = {
                    TextButton(
                        enabled = !s.saving,
                        onClick = {
                            scope.launch {
                                val err = vm.save()
                                if (err == null) {
                                    snackbar.showSnackbar(if (s.editing) "已保存" else "已记账")
                                    onDone()
                                } else snackbar.showSnackbar(err)
                            }
                        },
                    ) { Text("保存") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (s.loading) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中…")
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ---- 类型切换 ----
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val types = listOf(TxnType.EXPENSE, TxnType.INCOME, TxnType.TRANSFER)
                types.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = s.type == type,
                        onClick = { vm.setType(type) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = types.size),
                    ) {
                        Text(
                            when (type) {
                                TxnType.EXPENSE -> "支出"; TxnType.INCOME -> "收入"; TxnType.TRANSFER -> "转账"
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- 金额 ----
            val amountColor = when (s.type) {
                TxnType.EXPENSE -> MaterialTheme.colorScheme.expense
                TxnType.INCOME -> MaterialTheme.colorScheme.income
                TxnType.TRANSFER -> MaterialTheme.colorScheme.primary
            }
            Text(
                text = "¥ ${amountText.ifEmpty { "0.00" }}",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = amountColor,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
            )
            AmountKeyboard(
                onKey = { key ->
                    amountText = when (key) {
                        "⌫" -> amountText.dropLast(1)
                        "." -> if (amountText.contains('.')) amountText else if (amountText.isEmpty()) "0." else "$amountText."
                        else -> {
                            val decimals = amountText.substringAfter('.', "")
                            if (decimals.length < 2 && amountText.replace(".", "").length < 9) amountText + key else amountText
                        }
                    }.let { t ->
                        if (t.length > 1 && t.startsWith("0") && !t.startsWith("0.")) t.trimStart('0').ifEmpty { "0" } else t
                    }
                },
            )
            // 将键盘输入同步到 ViewModel 的金额状态
            LaunchedAmountSync(amountText, vm)

            Spacer(modifier = Modifier.height(8.dp))

            // ---- 分类（两级网格） ----
            if (s.type != TxnType.TRANSFER) {
                CategoryGrid(
                    tree = s.categoryTree,
                    selectedId = s.categoryId,
                    onSelect = { id -> vm.update { it.copy(categoryId = id) } },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ---- 账户 ----
            if (s.type == TxnType.TRANSFER) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountPicker(
                        label = "转出账户",
                        accounts = s.visibleAccounts,
                        selectedId = s.accountId,
                        modifier = Modifier.weight(1f),
                    ) { id -> vm.update { it.copy(accountId = id) } }
                    AccountPicker(
                        label = "转入账户",
                        accounts = s.visibleAccounts,
                        selectedId = s.toAccountId,
                        modifier = Modifier.weight(1f),
                    ) { id -> vm.update { it.copy(toAccountId = id) } }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = if (s.feeFen == 0L) "" else (s.feeFen / 100.0).let { if (s.feeFen % 100 == 0L) "${s.feeFen / 100}" else "%.2f".format(it) },
                    onValueChange = { text ->
                        val fen = text.replace(",", "").toDoubleOrNull()?.let { (it * 100).toLong() } ?: 0L
                        vm.update { it.copy(feeFen = fen) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("手续费（可选，从转出账户扣除）") },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                AccountPicker(
                    label = "账户",
                    accounts = s.visibleAccounts,
                    selectedId = s.accountId,
                    modifier = Modifier.fillMaxWidth(),
                ) { id -> vm.update { it.copy(accountId = id) } }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ---- 日期 + 时间（日期默认今天可改到任意过去日期即补录；时间精确到分钟） ----
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.DateRange, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("日期：${s.businessDate.formatDay()}", maxLines = 1)
                }
                OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.weight(1f)) {
                    Text("时间：${formatMinuteOfDay(s.timeOfDay).ifEmpty { "未设置" }}")
                    Spacer(modifier = Modifier.weight(1f))
                    Text("点击修改", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ---- 备注 ----
            OutlinedTextField(
                value = s.note,
                onValueChange = { text -> vm.update { it.copy(note = text) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("备注") },
                placeholder = { Text("写点什么…") },
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ---- 支出扩展：退款/报销/代付 ----
            if (s.type == TxnType.EXPENSE) {
                ExpenseExtensionCard(vm, s, snackbar, scope)
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ---- 附件 ----
            AttachmentSection(
                attachments = s.attachments,
                filesDir = container.filesDir,
                onAddAlbum = {
                    pickImages.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onAddCamera = { launchCamera() },
                onDelete = { vm.deleteAttachment(it) },
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (s.editing) {
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.expense,
                    ),
                ) { Text("删除这笔流水") }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        DayPickerDialog(
            initialMillis = s.businessDate,
            onConfirm = { millis -> vm.update { it.copy(businessDate = millis) }; showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = (s.timeOfDay / 60).coerceIn(0, 23),
            initialMinute = (s.timeOfDay % 60).let { if (s.timeOfDay < 0) 0 else it.coerceIn(0, 59) },
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("交易时间") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    vm.update { it.copy(timeOfDay = timePickerState.hour * 60 + timePickerState.minute) }
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        vm.update { it.copy(timeOfDay = -1) }
                        showTimePicker = false
                    }) { Text("不记录时间") }
                    TextButton(onClick = { showTimePicker = false }) { Text("取消") }
                }
            },
        )
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "删除流水",
            text = "删除后余额将自动回滚（补录流水不影响余额）。确定删除？",
            confirmText = "删除",
            onConfirm = { vm.deleteTxn(onDone) },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/** 把键盘输入的金额字符串同步进 ViewModel 的 AmountInputState，并写入草稿 */
@Composable
private fun LaunchedAmountSync(text: String, vm: RecordViewModel) {
    androidx.compose.runtime.LaunchedEffect(text) {
        vm.amountInput.clear()
        text.forEach { c -> vm.amountInput.append(c) }
        vm.persistDraft()
    }
}

/** 自定义记账键盘（与 AmountPad 等价的行布局） */
@Composable
private fun AmountKeyboard(onKey: (String) -> Unit) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "⌫"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { key ->
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { onKey(key) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) { Text(key, fontSize = 18.sp) }
                }
            }
        }
    }
}

/** 分类两级网格：第一级根分类；有子分类的根选中后展开第二级 */
@Composable
private fun CategoryGrid(
    tree: List<Pair<CategoryEntity, List<CategoryEntity>>>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
) {
    var expandedRootId by remember { mutableStateOf<Long?>(null) }
    // 选中项在子级时保持其父级展开
    val selectedInChildren = tree.firstOrNull { (_, children) -> children.any { it.id == selectedId } }?.first?.id
    val effectiveExpanded = expandedRootId ?: selectedInChildren

    Column {
        Text("分类", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))
        // 第一级（网格 4 列）
        val roots = tree.map { it.first }.chunked(4)
        roots.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { root ->
                    CategoryCell(
                        category = root,
                        selected = selectedId == root.id,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (tree.firstOrNull { it.first.id == root.id }?.second?.isEmpty() == true) {
                            onSelect(root.id) // 无子分类：直接选中
                        } else {
                            expandedRootId = if (effectiveExpanded == root.id) null else root.id
                            onSelect(root.id)
                        }
                    }
                }
                // 补齐列数保持对齐
                repeat(4 - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        // 第二级（展开）
        effectiveExpanded?.let { rootId ->
            tree.firstOrNull { it.first.id == rootId }?.second?.takeIf { it.isNotEmpty() }?.let { children ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("选择${tree.first { it.first.id == rootId }.first.name}下的子分类", style = MaterialTheme.typography.labelSmall)
                        Spacer(modifier = Modifier.height(6.dp))
                        children.chunked(4).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                rowItems.forEach { child ->
                                    CategoryCell(
                                        category = child,
                                        selected = selectedId == child.id,
                                        modifier = Modifier.weight(1f),
                                    ) { onSelect(child.id) }
                                }
                                repeat(4 - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryCell(
    category: CategoryEntity,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            CategoryBadge(category, size = 44.dp)
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color(0x66000000), RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            text = category.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 账户下拉选择器 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountPicker(
    label: String,
    accounts: List<AccountEntity>,
    selectedId: Long?,
    modifier: Modifier = Modifier,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = accounts.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected?.name ?: "选择账户",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accounts.forEach { acc ->
                DropdownMenuItem(
                    text = { Text(acc.name) },
                    onClick = { onSelect(acc.id); expanded = false },
                )
            }
        }
    }
}

/** 支出扩展区块：退款记录、报销记录、代付金额、已收回 */
@Composable
private fun ExpenseExtensionCard(
    vm: RecordViewModel,
    s: RecordUiState,
    snackbar: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var showRefundDialog by remember { mutableStateOf(false) }
    var showReimburseDialog by remember { mutableStateOf(false) }
    var showRecoveryDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("退款 / 报销 / 代付", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))

            // 退款记录
            RecordList(
                title = "退款记录",
                rows = s.refunds.map { "${it.amount.toYuan()} 元${if (it.note.isNotBlank()) "（${it.note}）" else ""}" },
                onAdd = { showRefundDialog = true },
                onDeleteAt = { idx -> s.refunds.getOrNull(idx)?.let(vm::deleteRefund) },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 报销记录
            RecordList(
                title = "报销记录",
                rows = s.reimbursements.map { "${it.amount.toYuan()} 元${if (it.note.isNotBlank()) "（${it.note}）" else ""}" },
                onAdd = { showReimburseDialog = true },
                onDeleteAt = { idx -> s.reimbursements.getOrNull(idx)?.let(vm::deleteReimburse) },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 代付金额
            OutlinedTextField(
                value = if (s.advanceFen == 0L) "" else s.advanceFen.toYuan(),
                onValueChange = { text ->
                    val fen = text.replace(",", "").toDoubleOrNull()?.let { (it * 100).toLong() } ?: 0L
                    vm.update { it.copy(advanceFen = fen) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("代付金额（我替他人支付的部分）") },
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已收回 ${s.recoveredFen.toYuan()} 元，应收 ${s.receivable.toYuan()} 元",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (s.receivable > 0) {
                    TextButton(onClick = { showRecoveryDialog = true }) { Text("记收回") }
                }
            }
        }
    }

    if (showRefundDialog) {
        AmountNoteDialog(
            title = "添加退款（发生即退回账户余额）",
            defaultAmountFen = (vm.amountInput.toFen() ?: 0L) - s.refundSum,
            onConfirm = { amount, note ->
                vm.addRefund(amount, note) { err ->
                    scope.launch { snackbar.showSnackbar(err ?: "退款已记录") }
                }
                showRefundDialog = false
            },
            onDismiss = { showRefundDialog = false },
        )
    }
    if (showReimburseDialog) {
        AmountNoteDialog(
            title = "添加报销到账",
            defaultAmountFen = (vm.amountInput.toFen() ?: 0L) - s.reimburseSum,
            onConfirm = { amount, note ->
                vm.addReimburse(amount, note) { err ->
                    scope.launch { snackbar.showSnackbar(err ?: "报销已记录") }
                }
                showReimburseDialog = false
            },
            onDismiss = { showReimburseDialog = false },
        )
    }
    if (showRecoveryDialog) {
        AmountNoteDialog(
            title = "登记收回代付（发生即 +账户余额）",
            defaultAmountFen = s.receivable,
            onConfirm = { amount, _ ->
                vm.addRecovery(amount) { err ->
                    scope.launch { snackbar.showSnackbar(err ?: "已登记收回") }
                }
                showRecoveryDialog = false
            },
            onDismiss = { showRecoveryDialog = false },
        )
    }
}

@Composable
private fun RecordList(
    title: String,
    rows: List<String>,
    onAdd: () -> Unit,
    onDeleteAt: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onAdd) { Text("添加") }
    }
    if (rows.isEmpty()) {
        Text("无", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    } else {
        rows.forEachIndexed { index, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { onDeleteAt(index) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "删除", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** 金额+备注输入对话框（退款/报销/收回共用） */
@Composable
private fun AmountNoteDialog(
    title: String,
    defaultAmountFen: Long,
    onConfirm: (amount: Long, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var amountText by remember {
        mutableStateOf(
            if (defaultAmountFen <= 0) ""
            else if (defaultAmountFen % 100 == 0L) "${defaultAmountFen / 100}"
            else "%.2f".format(defaultAmountFen / 100.0),
        )
    }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("金额（元）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val fen = amountText.replace(",", "").toDoubleOrNull()?.let { (it * 100).toLong() }
                if (fen != null && fen > 0) onConfirm(fen, note.trim()) else amountText = ""
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 附件区：缩略图网格 + 相册/拍照添加 */
@Composable
private fun AttachmentSection(
    attachments: List<AttachmentEntity>,
    filesDir: File,
    onAddAlbum: () -> Unit,
    onAddCamera: () -> Unit,
    onDelete: (AttachmentEntity) -> Unit,
) {
    Text("附件（${attachments.size}/9）", style = MaterialTheme.typography.labelLarge)
    Spacer(modifier = Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (attachments.size < 9) {
            OutlinedButton(onClick = onAddAlbum) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("相册")
            }
            OutlinedButton(onClick = onAddCamera) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("拍照")
            }
        }
    }
    if (attachments.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        attachments.chunked(4).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { att ->
                    Box(modifier = Modifier.size(76.dp)) {
                        AttachmentThumb(file = File(filesDir, att.relativePath))
                        IconButton(
                            onClick = { onDelete(att) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(22.dp)
                                .background(Color(0x99000000), RoundedCornerShape(11.dp)),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "删除附件", tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AttachmentThumb(file: File) {
    val bitmap = remember(file.absolutePath) {
        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "附件",
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier
                .size(76.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("…", color = MaterialTheme.colorScheme.outline) }
    }
}
