package com.youshu.ledger.ui.categories

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.youshu.ledger.data.AppContainer
import com.youshu.ledger.data.io.CategoryTransfer
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.CategoryType
import com.youshu.ledger.data.local.seed.DefaultDataSeeder
import com.youshu.ledger.ui.common.CategoryBadge
import com.youshu.ledger.ui.common.ConfirmDialog
import com.youshu.ledger.ui.common.LedgerTopBar
import com.youshu.ledger.ui.theme.expense
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CategoriesUiState(
    val categories: List<CategoryEntity> = emptyList(),
    val typeTab: CategoryType = CategoryType.EXPENSE,
)

/**
 * 分类管理（v1.5.1 起分类全局共享，不再按账本隔离）。
 * 数据层走 Flow，新增 / 删除 / 编辑实时刷新，无需退出重进。
 */
class CategoriesViewModel(
    private val container: AppContainer,
    private val appContext: Context,
) : ViewModel() {

    private val categoryRepo = container.categoryRepository

    private val typeTab = MutableStateFlow(CategoryType.EXPENSE)

    val uiState: StateFlow<CategoriesUiState> =
        combine(categoryRepo.observeAll(), typeTab) { categories, tab ->
            CategoriesUiState(categories = categories, typeTab = tab)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CategoriesUiState())

    fun setTab(type: CategoryType) {
        typeTab.value = type
    }

    fun save(category: CategoryEntity) {
        viewModelScope.launch { categoryRepo.upsert(category) }
    }

    fun delete(category: CategoryEntity) {
        viewModelScope.launch { categoryRepo.delete(category) }
    }

    /** 导出全部分类到 JSON 文件（SAF） */
    fun export(uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    CategoryTransfer.buildExport(categoryRepo.getAll(), System.currentTimeMillis())
                }
                appContext.contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入所选位置")
            }
            onResult(result.fold({ "已导出分类" }, { "导出失败：${it.message}" }))
        }
    }

    /** 从 JSON 文件导入分类（按 类型+父分类+名称 去重，缺失的创建） */
    fun import(uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use {
                        it.bufferedReader(Charsets.UTF_8).readText()
                    } ?: error("无法读取所选文件")
                }
                val nodes = CategoryTransfer.parse(text)
                val n = categoryRepo.importTree(nodes)
                "已导入 $n 个新分类"
            }
            onResult(result.getOrElse { "导入失败：${it.message}" })
        }
    }

    companion object {
        fun factory(container: AppContainer, context: Context) = viewModelFactory {
            initializer { CategoriesViewModel(container, context.applicationContext) }
        }
    }
}

/**
 * 分类管理：树状展示（支出/收入分 tab）、增删改、
 * 图标编辑（圆形底色 + 1~2 字）、分类导出 / 导入（JSON）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(container: AppContainer, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: CategoriesViewModel = viewModel(factory = CategoriesViewModel.factory(container, context))
    val state by vm.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var creatingParent by remember { mutableStateOf<CategoryEntity?>(null) }
    var showCreateRoot by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CategoryEntity?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.export(it) { msg -> scope.launch { snackbar.showSnackbar(msg) } } }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.import(it) { msg -> scope.launch { snackbar.showSnackbar(msg) } } }
    }

    Scaffold(
        topBar = { LedgerTopBar(title = "分类管理", onBack = onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateRoot = true }) {
                Icon(Icons.Default.Add, contentDescription = "新建分类")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // 支出/收入 tab
            SingleChoiceSegmentedButtonRow(modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)) {
                CategoryType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = state.typeTab == type,
                        onClick = { vm.setTab(type) },
                        shape = SegmentedButtonDefaults.itemShape(index, CategoryType.entries.size),
                    ) { Text(if (type == CategoryType.EXPENSE) "支出" else "收入") }
                }
            }
            // 导出 / 导入
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { exportLauncher.launch("youshu-categories.json") },
                    modifier = Modifier.weight(1f),
                ) { Text("导出分类") }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                    modifier = Modifier.weight(1f),
                ) { Text("导入分类") }
            }

            val ofType = state.categories
                .filter { it.type == state.typeTab }
                .sortedBy { it.sortOrder }
            val roots = ofType.filter { it.parentId == null }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(roots, key = { it.id }) { root ->
                    CategoryNode(
                        category = root,
                        children = ofType.filter { it.parentId == root.id },
                        onEdit = { editing = it },
                        onAddChild = { creatingParent = it },
                        onDelete = { pendingDelete = it },
                    )
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (showCreateRoot) {
        CategoryEditDialog(
            category = null,
            parent = null,
            type = state.typeTab,
            maxSort = state.categories.count { it.type == state.typeTab },
            onSave = { cat ->
                vm.save(cat)
                showCreateRoot = false
            },
            onDismiss = { showCreateRoot = false },
        )
    }
    creatingParent?.let { parent ->
        CategoryEditDialog(
            category = null,
            parent = parent,
            type = parent.type,
            maxSort = state.categories.count { it.type == state.typeTab },
            onSave = { cat ->
                vm.save(cat)
                creatingParent = null
            },
            onDismiss = { creatingParent = null },
        )
    }
    editing?.let { cat ->
        CategoryEditDialog(
            category = cat,
            parent = null,
            type = cat.type,
            maxSort = state.categories.count { it.type == state.typeTab },
            onSave = { updated ->
                vm.save(updated)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    pendingDelete?.let { cat ->
        val childCount = state.categories.count { it.parentId == cat.id }
        ConfirmDialog(
            title = "删除分类",
            text = if (childCount > 0) "删除「${cat.name}」将同时删除其 $childCount 个子分类。确定删除？"
            else "删除分类「${cat.name}」？",
            confirmText = "删除",
            onConfirm = {
                vm.delete(cat)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun CategoryNode(
    category: CategoryEntity,
    children: List<CategoryEntity>,
    onEdit: (CategoryEntity) -> Unit,
    onAddChild: (CategoryEntity) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
) {
    var expanded by remember { mutableStateOf(children.isNotEmpty()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryBadge(category, size = 36.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(category.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (children.isNotEmpty()) {
                    Text(
                        "${children.size} 个子分类 ${if (expanded) "▲" else "▼"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                IconButton(onClick = { onEdit(category) }) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = { onAddChild(category) }) {
                    Icon(Icons.Default.Add, contentDescription = "添加子分类")
                }
                IconButton(onClick = { onDelete(category) }) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "删除", tint = MaterialTheme.colorScheme.expense)
                }
            }
            if (expanded) {
                children.forEach { child ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 40.dp, end = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategoryBadge(child, size = 28.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(child.name, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onEdit(child) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onDelete(child) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.expense,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 分类编辑对话框：名称、图标文字（1~2 字）、圆形底色选择 */
@Composable
private fun CategoryEditDialog(
    category: CategoryEntity?,
    parent: CategoryEntity?,
    type: CategoryType,
    maxSort: Int,
    onSave: (CategoryEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(category?.name ?: "") }
    var iconText by remember { mutableStateOf(category?.iconText ?: "") }
    var color by remember { mutableStateOf(category?.color ?: DefaultDataSeeder.CATEGORY_COLORS.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    category != null -> "编辑分类"
                    parent != null -> "新建子分类（${parent.name} 下）"
                    else -> "新建分类"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (iconText.isEmpty() || category == null && iconText == category?.iconText) {
                            iconText = it.take(2) // 默认取名称前两个字作为图标文字
                        }
                    },
                    label = { Text("分类名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = iconText,
                    onValueChange = { if (it.length <= 2) iconText = it },
                    label = { Text("图标文字（1~2 个字）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("圆形底色", style = MaterialTheme.typography.labelMedium)
                // 两行色板
                DefaultDataSeeder.CATEGORY_COLORS.chunked(8).forEach { rowColors ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowColors.forEach { c ->
                            val selected = color == c
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(c))
                                    .clickable { color = c }
                                    .then(
                                        if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                        else Modifier,
                                    ),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("预览：", style = MaterialTheme.typography.labelMedium)
                    CategoryBadge(
                        CategoryEntity(
                            id = 0, bookId = 0, type = type, parentId = null,
                            name = name, sortOrder = 0, color = color,
                            iconText = iconText.ifEmpty { "？" },
                        ),
                        size = 40.dp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && iconText.isNotBlank(),
                onClick = {
                    onSave(
                        (category ?: CategoryEntity(
                            id = 0, bookId = 0, type = type,
                            parentId = parent?.id, name = "", sortOrder = maxSort,
                            color = color, iconText = "",
                        )).copy(
                            name = name.trim(),
                            iconText = iconText.trim(),
                            color = color,
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
