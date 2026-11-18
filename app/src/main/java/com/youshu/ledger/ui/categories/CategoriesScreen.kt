package com.youshu.ledger.ui.categories

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import com.youshu.ledger.data.local.entity.BookEntity
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.CategoryType
import com.youshu.ledger.data.local.seed.DefaultDataSeeder
import com.youshu.ledger.ui.common.CategoryBadge
import com.youshu.ledger.ui.common.ConfirmDialog
import com.youshu.ledger.ui.common.LedgerTopBar
import com.youshu.ledger.ui.theme.expense
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CategoriesUiState(
    val bookId: Long = 0,
    val bookName: String = "",
    val categories: List<CategoryEntity> = emptyList(),
    val books: List<BookEntity> = emptyList(),
    val typeTab: CategoryType = CategoryType.EXPENSE,
)

class CategoriesViewModel(container: AppContainer) : ViewModel() {

    private val bookRepo = container.bookRepository
    private val categoryRepo = container.categoryRepository

    private val typeTab = kotlinx.coroutines.flow.MutableStateFlow(CategoryType.EXPENSE)

    val uiState: StateFlow<CategoriesUiState> =
        combine(
            bookRepo.currentBook,
            bookRepo.observeAll(),
            typeTab,
        ) { book, books, tab ->
            CategoriesUiState(
                bookId = book?.id ?: 0,
                bookName = book?.name ?: "",
                categories = book?.let { categoryRepo.getByBook(it.id) } ?: emptyList(),
                books = books,
                typeTab = tab,
            )
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

    /** 跨账本导入分类（从 sourceBookId 复制到当前账本） */
    fun importFrom(sourceBookId: Long, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val current = bookRepo.currentBook.stateFirst() ?: return@launch
            val n = categoryRepo.copyFromBook(sourceBookId, current.id)
            onResult("已导入 $n 个分类")
        }
    }

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.stateFirst(): T? = this.firstOrNull()

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { CategoriesViewModel(container) }
        }
    }
}

/**
 * 分类管理：树状展示（支出/收入分 tab）、增删改、
 * 图标编辑（圆形底色 + 1~2 字）、跨账本导入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: CategoriesViewModel = viewModel(factory = CategoriesViewModel.factory(container))
    val state by vm.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var creatingParent by remember { mutableStateOf<CategoryEntity?>(null) }
    var showCreateRoot by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CategoryEntity?>(null) }
    var showImport by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { LedgerTopBar(title = "分类管理 · ${state.bookName}", onBack = onBack) },
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
            OutlinedButton(
                onClick = { showImport = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) { Text("从其他账本导入分类") }

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
                vm.save(cat.copy(bookId = state.bookId))
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
    if (showImport) {
        ImportCategoriesDialog(
            books = state.books.filter { it.name != state.bookName },
            onImport = { fromId ->
                vm.importFrom(fromId) { msg ->
                    scope.launch { snackbar.showSnackbar(msg) }
                }
                showImport = false
            },
            onDismiss = { showImport = false },
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
                            id = 0, bookId = parent?.bookId ?: 0, type = type,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportCategoriesDialog(
    books: List<BookEntity>,
    onImport: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    if (books.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("导入分类") },
            text = { Text("没有其他账本可复制分类。") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        )
        return
    }
    var menu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(books.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从其他账本导入分类") },
        text = {
            ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = it }) {
                OutlinedTextField(
                    value = selected.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("来源账本") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    books.forEach { b ->
                        DropdownMenuItem(text = { Text(b.name) }, onClick = { selected = b; menu = false })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onImport(selected.id) }) { Text("导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
