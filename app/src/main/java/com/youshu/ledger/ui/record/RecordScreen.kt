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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.youshu.ledger.data.AppContainer
import com.youshu.ledger.data.local.entity.AccountEntity
import com.youshu.ledger.data.local.entity.AdvanceRecordEntity
import com.youshu.ledger.data.local.entity.AttachmentEntity
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.CategoryType
import com.youshu.ledger.data.local.entity.ReimburseRecordEntity
import com.youshu.ledger.data.local.entity.TxnType
import com.youshu.ledger.domain.util.currentMinuteOfDay
import com.youshu.ledger.domain.util.formatDay
import com.youshu.ledger.domain.util.formatMinuteOfDay
import com.youshu.ledger.domain.util.formatTime
import com.youshu.ledger.domain.util.toEpochMillis
import com.youshu.ledger.domain.util.toLocalDate
import com.youshu.ledger.domain.util.toYuan
import com.youshu.ledger.ui.common.AccountIcon
import com.youshu.ledger.ui.common.CategoryBadge
import com.youshu.ledger.ui.common.ConfirmDialog
import com.youshu.ledger.ui.common.DateTimeButtonsRow
import com.youshu.ledger.ui.common.DayPickerDialog
import com.youshu.ledger.ui.common.MinutePickerDialog
import com.youshu.ledger.ui.common.LedgerTopBar
import com.youshu.ledger.ui.common.MoneyInputDialog
import com.youshu.ledger.ui.common.accountIconColor
import com.youshu.ledger.ui.common.isValidMoneyText
import com.youshu.ledger.ui.common.moneyTextToFen
import com.youshu.ledger.ui.theme.expense
import com.youshu.ledger.ui.theme.income
import kotlinx.coroutines.launch
import java.io.File

/**
 * 记一笔 / 编辑流水页。
 * 类型切换、金额键盘、分类搜索与两级网格（可加分类/二级分类）、账户选择、
 * 时间（日期+分钟，默认当前）、项目名称、备注、附件；
 * 支出类型提供退款/报销/代付三个开关区块（编辑已保存流水时可直接操作）。
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
    var showFeeDialog by remember { mutableStateOf(false) }
    // 添加分类：null=添加根分类，否则为父分类 id（添加二级分类）
    var addCategoryParentId by remember { mutableStateOf<Long?>(null) }
    var addCategoryShow by remember { mutableStateOf(false) }

    // 金额输入以 ViewModel 的 AmountInputState 为唯一数据源（修复小数点同步丢失问题）
    var amountRefresh by remember { mutableIntStateOf(0) }

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
                                    // 保存成功立即返回流水页（不再等待 Snackbar）
                                    onDone()
                                } else {
                                    snackbar.showSnackbar(err)
                                }
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
            // ---- 项目名称（表单第一项） ----
            OutlinedTextField(
                value = s.project,
                onValueChange = { text -> vm.update { it.copy(project = text) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("项目名称（可选）") },
                placeholder = { Text("如：装修、旅行…") },
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ---- 时间：左日期右时间两栏，各自独立修改（默认当前时刻） ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.DateRange, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = s.businessDate.formatDay(),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Schedule, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formatMinuteOfDay(s.timeOfDay)
                            .ifEmpty { formatMinuteOfDay(currentMinuteOfDay()) },
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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

            Spacer(modifier = Modifier.height(8.dp))

            // ---- 金额：系统数字键盘文本框（非法字符直接拒输，输入永远合法） ----
            val amountColor = when (s.type) {
                TxnType.EXPENSE -> MaterialTheme.colorScheme.expense
                TxnType.INCOME -> MaterialTheme.colorScheme.income
                TxnType.TRANSFER -> MaterialTheme.colorScheme.primary
            }
            amountRefresh.let { }
            OutlinedTextField(
                value = vm.amountInput.text,
                onValueChange = { input ->
                    vm.amountInput.setText(input)
                    vm.persistDraft()
                    amountRefresh++
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("金额（元）") },
                placeholder = { Text("0.00") },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = amountColor,
                    fontWeight = FontWeight.Bold,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ---- 分类：两级网格（可新增分类/二级分类，无搜索框） ----
            if (s.type != TxnType.TRANSFER) {
                CategoryGrid(
                    tree = s.categoryTree,
                    selectedId = s.categoryId,
                    onSelect = { id -> vm.update { it.copy(categoryId = id) } },
                    onAdd = { parentId ->
                        addCategoryParentId = parentId
                        addCategoryShow = true
                    },
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
                // 手续费：点击弹金额对话框（金额输入统一走对话框）
                OutlinedButton(onClick = { showFeeDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (s.feeFen == 0L) "手续费（可选，点击设置）"
                        else "手续费：${s.feeFen.toYuan()} 元（点击修改）",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showFeeDialog) {
                    MoneyInputDialog(
                        title = "手续费（从转出账户扣除）",
                        initialFen = s.feeFen,
                        onConfirm = { fen -> vm.update { it.copy(feeFen = fen) }; showFeeDialog = false },
                        onDismiss = { showFeeDialog = false },
                    )
                }
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

            // ---- 备注 ----
            OutlinedTextField(
                value = s.note,
                onValueChange = { text -> vm.update { it.copy(note = text) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("备注") },
                placeholder = { Text("写点什么…") },
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ---- 扩展：退款/报销/代付 + 条目组（支出/收入可用，开关控制显隐） ----
            if (s.type != TxnType.TRANSFER) {
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
            onConfirm = { millis ->
                vm.update { it.copy(businessDate = millis) }
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }

    if (showTimePicker) {
        MinutePickerDialog(
            title = "交易时间",
            initialMinuteOfDay = if (s.timeOfDay >= 0) s.timeOfDay else currentMinuteOfDay(),
            onConfirm = { minute ->
                vm.update { it.copy(timeOfDay = minute) }
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
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

    if (addCategoryShow) {
        AddCategoryDialog(
            parentName = addCategoryParentId?.let { pid ->
                s.categories.firstOrNull { it.id == pid }?.name
            },
            onConfirm = { name ->
                vm.createCategory(
                    name = name,
                    parentId = addCategoryParentId,
                    type = if (s.type == TxnType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE,
                ) { err ->
                    scope.launch { snackbar.showSnackbar(err ?: "已添加分类") }
                }
                addCategoryShow = false
            },
            onDismiss = { addCategoryShow = false },
        )
    }
}

/** 自定义记账键盘已移除（v1.6）：金额改为系统数字键盘文本框，见表单内 OutlinedTextField。 */

/** 添加分类对话框（根分类或某根分类下的二级分类） */
@Composable
private fun AddCategoryDialog(
    parentName: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (parentName == null) "新增分类" else "新增「$parentName」的子分类") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(8) },
                label = { Text("分类名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) },
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 分类两级网格：第一级根分类 + 「+」新增；展开后可加二级分类 */
@Composable
private fun CategoryGrid(
    tree: List<Pair<CategoryEntity, List<CategoryEntity>>>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onAdd: (parentId: Long?) -> Unit,
) {
    var expandedRootId by remember { mutableStateOf<Long?>(null) }
    // 选中项在子级时保持其父级展开
    val selectedInChildren = tree.firstOrNull { (_, children) -> children.any { it.id == selectedId } }?.first?.id
    val effectiveExpanded = expandedRootId ?: selectedInChildren

    Column {
        Text("分类", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))
        // 第一级（网格 4 列，末尾「+」为新增根分类）
        val roots = tree.map { it.first }.plus(NULL_CATEGORY)
        roots.chunked(4).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { item ->
                    if (item === NULL_CATEGORY) {
                        AddCategoryCell(modifier = Modifier.weight(1f)) { onAdd(null) }
                    } else {
                        CategoryCell(
                            category = item,
                            selected = selectedId == item.id,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (tree.firstOrNull { it.first.id == item.id }?.second?.isEmpty() == true) {
                                onSelect(item.id) // 无子分类：直接选中
                            } else {
                                expandedRootId = if (effectiveExpanded == item.id) null else item.id
                                onSelect(item.id)
                            }
                        }
                    }
                }
                repeat(4 - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        // 第二级（展开；末尾「+」为新增子分类）
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
                        val cells = children.plus(NULL_CATEGORY)
                        cells.chunked(4).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                rowItems.forEach { item ->
                                    if (item === NULL_CATEGORY) {
                                        AddCategoryCell(modifier = Modifier.weight(1f)) { onAdd(rootId) }
                                    } else {
                                        CategoryCell(
                                            category = item,
                                            selected = selectedId == item.id,
                                            modifier = Modifier.weight(1f),
                                        ) { onSelect(item.id) }
                                    }
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

private val NULL_CATEGORY = CategoryEntity(
    id = -1, bookId = 0, type = CategoryType.EXPENSE, parentId = null,
    name = "", sortOrder = 0, color = 0, iconText = "",
)

@Composable
private fun AddCategoryCell(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(22.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Add, contentDescription = "新增分类", tint = MaterialTheme.colorScheme.outline)
        }
        Text("新增", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
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

/**
 * 退款/报销/代付区块：三个开关，开启后显示对应内容。
 * 退款/报销：金额+账户+时间必填、备注可选，可多次；
 * 代付：按「谁」逐人登记（金额/时间/账户/备注可选），收回按总额登记。
 */
@Composable
private fun ExpenseExtensionCard(
    vm: RecordViewModel,
    s: RecordUiState,
    snackbar: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var refundOn by remember { mutableStateOf(s.refunds.isNotEmpty()) }
    var reimburseOn by remember { mutableStateOf(s.reimbursements.isNotEmpty()) }
    var advanceOn by remember { mutableStateOf(s.advances.isNotEmpty() || s.recoveredFen > 0) }
    var showRefundDialog by remember { mutableStateOf(false) }
    var showRecoveryDialog by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ---- 退款 ----
            ExtensionSwitchRow("退款（可多次、部分退）", refundOn) { refundOn = it }
            if (refundOn) {
                RecordList(
                    rows = s.refunds.map { r ->
                        buildString {
                            append("${r.amount.toYuan()} 元")
                            append(" → ")
                            append(s.accounts.firstOrNull { it.id == r.accountId }?.name ?: "未知账户")
                            append(" · ")
                            append(r.timeMillis.formatTime())
                            if (r.note.isNotBlank()) append("（${r.note}）")
                        }
                    },
                    onAdd = { showRefundDialog = true },
                    onDeleteAt = { idx -> s.refunds.getOrNull(idx)?.let(vm::deleteRefund) },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 报销 ----
            ExtensionSwitchRow("报销（可多次）", reimburseOn) { reimburseOn = it }
            if (reimburseOn) {
                ReimburseSection(vm, s, snackbar, scope)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 代付 ----
            ExtensionSwitchRow("代付（替他人支付）", advanceOn) { advanceOn = it }
            if (advanceOn) {
                AdvanceSection(
                    vm = vm,
                    s = s,
                    snackbar = snackbar,
                    scope = scope,
                )
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

            // ---- 关联流水（条目组）：A钱/报销条添加后自动组组，组内任一流水等价可见 ----
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("关联流水（本组）", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.loadLinkCandidates(); showLinkDialog = true }) { Text("添加") }
            }
            if (s.groupExpenses.isEmpty()) {
                Text(
                    "暂无。添加 A钱 / 报销条后自动组成本组；组内条目在任一流水上都能查看和修改",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                s.groupExpenses.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = buildString {
                                append("${t.amount.toYuan()} 元 · ")
                                append(s.accounts.firstOrNull { it.id == t.accountId }?.name ?: "未知账户")
                                append(" · ")
                                append(t.businessDate.formatDay())
                                if (t.note.isNotBlank()) append("（${t.note}）")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.unlinkExpenseFromGroup(t.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "移出本组", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    // 选择已有流水加入本组
    if (showLinkDialog) {
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text("添加流水到本组") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (s.linkCandidates.isEmpty()) {
                        Text("没有可添加的支出流水", color = MaterialTheme.colorScheme.outline)
                    } else {
                        s.linkCandidates.forEach { t ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        buildString {
                                            append("${t.amount.toYuan()} 元 · ")
                                            append(s.accounts.firstOrNull { it.id == t.accountId }?.name ?: "未知账户")
                                            append(" · ")
                                            append(t.businessDate.formatDay())
                                            if (t.note.isNotBlank()) append("（${t.note}）")
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                onClick = {
                                    vm.linkExpenseToGroup(t.id) { err ->
                                        scope.launch { snackbar.showSnackbar(err ?: "已加入本组") }
                                    }
                                    showLinkDialog = false
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLinkDialog = false }) { Text("关闭") } },
        )
    }

    if (showRefundDialog) {
        RefundReimburseDialog(
            title = "添加退款（发生即退回所选账户余额）",
            accounts = s.visibleAccounts,
            defaultAmountFen = (vm.amountInput.toFen() ?: 0L) - s.refundSum,
            fullAmountFen = vm.amountInput.toFen()?.takeIf { it > 0 },
            onConfirm = { amount, accountId, timeMillis, note ->
                vm.addRefund(amount, accountId, timeMillis, note) { err ->
                    scope.launch { snackbar.showSnackbar(err ?: "退款已记录") }
                }
                showRefundDialog = false
            },
            onDismiss = { showRefundDialog = false },
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
private fun ExtensionSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 分 → 元输入框文本（整数不带小数点，否则两位小数） */
private fun formatFenInput(fen: Long): String =
    if (fen % 100 == 0L) "${fen / 100}" else "%.2f".format(fen / 100.0)

/**
 * 退款 / 报销共用的录入对话框：金额、账户、时间 必填，备注可选。
 * [fullAmountFen] 非空（仅退款使用）时提供「原价退还」开关：开启后金额锁定为本笔支出，无需手填。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefundReimburseDialog(
    title: String,
    accounts: List<AccountEntity>,
    defaultAmountFen: Long,
    fullAmountFen: Long? = null,
    onConfirm: (amount: Long, accountId: Long, timeMillis: Long, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var amountText by remember {
        mutableStateOf(if (defaultAmountFen <= 0) "" else formatFenInput(defaultAmountFen))
    }
    var fullPrice by remember { mutableStateOf(false) }
    var accountId by remember { mutableStateOf<Long?>(null) }
    var timeMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var note by remember { mutableStateOf("") }
    var accountMenu by remember { mutableStateOf(false) }

    val amountValid = isValidMoneyText(amountText, allowNegative = false)
    val amountFen = moneyTextToFen(amountText, allowNegative = false)
    val ready = accountId != null && (fullPrice || (amountFen != null && amountFen > 0))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 原价退还（退款专用）：开启后金额 = 本笔支出，无需手填
                if (fullAmountFen != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("原价退还（金额 = 本笔支出）", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(
                            checked = fullPrice,
                            onCheckedChange = { on ->
                                fullPrice = on
                                if (on) amountText = formatFenInput(fullAmountFen)
                            },
                        )
                    }
                }
                // 金额（必填，校验红字；原价退还时锁定）
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("金额（元）*") },
                    singleLine = true,
                    enabled = !fullPrice,
                    isError = !fullPrice && (!amountValid || (amountText.isNotEmpty() && amountFen == null)),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!amountValid) {
                    Text("只能输入数字和小数点", color = Color(0xFFD32F2F), style = MaterialTheme.typography.labelSmall)
                }
                // 账户（必填）
                ExposedDropdownMenuBox(expanded = accountMenu, onExpandedChange = { accountMenu = it }) {
                    OutlinedTextField(
                        value = accounts.firstOrNull { it.id == accountId }?.name ?: "选择账户 *",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("到账账户") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(accountMenu) },
                        isError = accountId == null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                        accounts.forEach { acc ->
                            DropdownMenuItem(
                                text = { Text(acc.name) },
                                onClick = { accountId = acc.id; accountMenu = false },
                            )
                        }
                    }
                }
                // 时间（必填，默认当前；左日期右时刻两栏）
                DateTimeButtonsRow(
                    millis = timeMillis,
                    onMillisChange = { timeMillis = it },
                    datePickerTitle = "发生日期",
                    timePickerTitle = "发生时刻",
                )
                // 备注（可选）
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
            TextButton(
                enabled = ready,
                onClick = {
                    val acc = accountId ?: return@TextButton
                    val fen = if (fullPrice) fullAmountFen else amountFen
                    if (fen != null && fen > 0) onConfirm(fen, acc, timeMillis, note.trim())
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 报销区块：组内全部报销条行内编辑（金额/账户/时间必填，备注可选）；
 * 尾行填齐必填（金额>0 + 账户）自动入库并出现新行。
 */
@Composable
private fun ReimburseSection(
    vm: RecordViewModel,
    s: RecordUiState,
    snackbar: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    // 尾行草稿（尚未入库）：0 表示未填
    var draft by remember { mutableStateOf(ReimburseRecordEntity(0, 0, 0, 0, 0, "", 0, null)) }

    fun draftHasContent(d: ReimburseRecordEntity) =
        d.amount > 0 || d.accountId != 0L || d.timeMillis != 0L || d.note.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        s.reimbursements.forEach { record ->
            ReimburseRow(
                record = record,
                accounts = s.visibleAccounts,
                onChange = { updated ->
                    vm.updateReimburse(updated.copy(id = record.id, transactionId = record.transactionId)) { err ->
                        scope.launch { err?.let { snackbar.showSnackbar(it) } }
                    }
                },
                onDelete = { vm.deleteReimburse(record) },
            )
        }
        ReimburseRow(
            record = draft,
            accounts = s.visibleAccounts,
            onChange = { updated ->
                draft = updated
                // 必填齐了自动入库
                if (updated.amount > 0 && updated.accountId != 0L) {
                    vm.addReimburse(
                        updated.amount,
                        updated.accountId,
                        updated.timeMillis.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        updated.note,
                    ) { err ->
                        if (err == null) {
                            draft = ReimburseRecordEntity(0, 0, 0, 0, 0, "", 0, null)
                        } else {
                            scope.launch { snackbar.showSnackbar(err) }
                        }
                    }
                }
            },
            onDelete = { if (draftHasContent(draft)) draft = ReimburseRecordEntity(0, 0, 0, 0, 0, "", 0, null) },
            isDraft = true,
        )
    }
}

/** 单条报销行：金额（必填）/ 账户（必填）/ 时间 / 备注，全部行内可改 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReimburseRow(
    record: ReimburseRecordEntity,
    accounts: List<AccountEntity>,
    onChange: (ReimburseRecordEntity) -> Unit,
    onDelete: () -> Unit,
    isDraft: Boolean = false,
) {
    var showAmountDialog by remember { mutableStateOf(false) }
    var accountMenu by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showAmountDialog = true }, modifier = Modifier.weight(1f)) {
                    Text(
                        if (record.amount > 0) "${record.amount.toYuan()} 元" else "金额 *",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "删除", modifier = Modifier.size(18.dp))
                }
            }
            // 时间：左日期右时刻两栏
            DateTimeButtonsRow(
                millis = record.timeMillis.takeIf { it > 0 } ?: System.currentTimeMillis(),
                onMillisChange = { onChange(record.copy(timeMillis = it)) },
                spacing = 6.dp,
                datePickerTitle = "报销日期",
                timePickerTitle = "报销时刻",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExposedDropdownMenuBox(
                    expanded = accountMenu,
                    onExpandedChange = { accountMenu = it },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = accounts.firstOrNull { it.id == record.accountId }?.name ?: "账户 *",
                        onValueChange = {},
                        readOnly = true,
                        isError = !isDraft && record.accountId == 0L,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(accountMenu) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                        accounts.forEach { acc ->
                            DropdownMenuItem(
                                text = { Text(acc.name) },
                                onClick = { onChange(record.copy(accountId = acc.id)); accountMenu = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = record.note,
                    onValueChange = { onChange(record.copy(note = it.take(20))) },
                    label = { Text("备注") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (showAmountDialog) {
        MoneyInputDialog(
            title = "报销金额",
            initialFen = record.amount,
            allowEmpty = true,
            onConfirm = { onChange(record.copy(amount = it)); showAmountDialog = false },
            onDismiss = { showAmountDialog = false },
        )
    }
}

/** 代付区块：按「谁」逐人登记；填了最后一行自动出现新行，只有最后一行允许留空 */
@Composable
private fun AdvanceSection(
    vm: RecordViewModel,
    s: RecordUiState,
    snackbar: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    // 尾行草稿（尚未入库）：任何字段有内容即尝试入库，成功后刷新
    var draft by remember {
        mutableStateOf(AdvanceRecordEntity(0, 0, "", null, null, null, ""))
    }

    fun draftHasContent(d: AdvanceRecordEntity) =
        d.person.isNotBlank() || d.amount != null || d.accountId != null || d.timeMillis != null || d.note.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        s.advances.forEach { record ->
            AdvanceRow(
                record = record,
                accounts = s.visibleAccounts,
                onChange = { updated ->
                    vm.updateAdvance(updated.copy(id = record.id, transactionId = record.transactionId)) { err ->
                        scope.launch { err?.let { snackbar.showSnackbar(it) } }
                    }
                },
                onDelete = { vm.deleteAdvance(record) },
            )
        }
        AdvanceRow(
            record = draft,
            accounts = s.visibleAccounts,
            onChange = { updated ->
                draft = updated
                if (updated.person.isNotBlank()) {
                    vm.addAdvance(
                        person = updated.person,
                        amount = updated.amount,
                        accountId = updated.accountId,
                        timeMillis = updated.timeMillis,
                        note = updated.note,
                    ) { err ->
                        if (err == null) {
                            draft = AdvanceRecordEntity(0, 0, "", null, null, null, "")
                        } else {
                            scope.launch { snackbar.showSnackbar(err) }
                        }
                    }
                }
            },
            onDelete = { if (draftHasContent(draft)) draft = AdvanceRecordEntity(0, 0, "", null, null, null, "") },
            isDraft = true,
        )
        Text(
            "合计代付 ${s.advanceTotal.toYuan()} 元",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** 单条代付人行：谁（必填）+ 金额/时间/账户/备注（可选） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvanceRow(
    record: AdvanceRecordEntity,
    accounts: List<AccountEntity>,
    onChange: (AdvanceRecordEntity) -> Unit,
    onDelete: () -> Unit,
    isDraft: Boolean = false,
) {
    var showAmountDialog by remember { mutableStateOf(false) }
    var accountMenu by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = record.person,
                    onValueChange = { onChange(record.copy(person = it.take(8))) },
                    label = { Text("谁 *") },
                    isError = !isDraft && record.person.isBlank(),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "删除", modifier = Modifier.size(18.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { showAmountDialog = true }, modifier = Modifier.weight(1f)) {
                    Text(
                        record.amount?.let { "${it.toYuan()} 元" } ?: "金额",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 时间：左日期右时刻两栏（未设置时显示占位文字；空间紧凑用短格式）
                DateTimeButtonsRow(
                    millis = record.timeMillis ?: System.currentTimeMillis(),
                    onMillisChange = { onChange(record.copy(timeMillis = it)) },
                    modifier = Modifier.weight(1.6f),
                    spacing = 4.dp,
                    datePickerTitle = "代付日期",
                    timePickerTitle = "代付时刻",
                    dateText = record.timeMillis?.formatDay()?.takeLast(5) ?: "日期",
                    timeText = record.timeMillis?.formatTime()?.substringAfter(' ') ?: "时刻",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExposedDropdownMenuBox(
                    expanded = accountMenu,
                    onExpandedChange = { accountMenu = it },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = accounts.firstOrNull { it.id == record.accountId }?.name ?: "账户（可选）",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(accountMenu) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("不关联账户") },
                            onClick = { onChange(record.copy(accountId = null)); accountMenu = false },
                        )
                        accounts.forEach { acc ->
                            DropdownMenuItem(
                                text = { Text(acc.name) },
                                onClick = { onChange(record.copy(accountId = acc.id)); accountMenu = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = record.note,
                    onValueChange = { onChange(record.copy(note = it.take(20))) },
                    label = { Text("备注") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (showAmountDialog) {
        MoneyInputDialog(
            title = "代付金额",
            initialFen = record.amount ?: 0L,
            allowEmpty = true,
            onConfirm = { onChange(record.copy(amount = it.takeIf { v -> v > 0 })); showAmountDialog = false },
            onDismiss = { showAmountDialog = false },
        )
    }
}

@Composable
private fun RecordList(
    rows: List<String>,
    onAdd: () -> Unit,
    onDeleteAt: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onAdd) { Text("添加") }
    }
    if (rows.isEmpty()) {
        Text("无", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    } else {
        rows.forEachIndexed { index, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { onDeleteAt(index) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "删除", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** 金额+备注输入对话框（登记收回用）；金额校验：数字/小数点，非法红字提示 */
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
    val amountValid = isValidMoneyText(amountText, allowNegative = false)
    val amountFen = moneyTextToFen(amountText, allowNegative = false)
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
                    isError = !amountValid,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!amountValid) {
                    Text("只能输入数字和小数点", color = Color(0xFFD32F2F), style = MaterialTheme.typography.labelSmall)
                }
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
            TextButton(
                enabled = amountFen != null && amountFen > 0,
                onClick = { amountFen?.let { onConfirm(it, note.trim()) } },
            ) { Text("确定") }
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
