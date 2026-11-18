package com.youshu.ledger.ui.books

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.youshu.ledger.data.AppContainer
import com.youshu.ledger.data.local.entity.BookEntity
import com.youshu.ledger.ui.common.ConfirmDialog
import com.youshu.ledger.ui.theme.expense
import com.youshu.ledger.ui.common.LedgerTopBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BooksUiState(
    val books: List<BookEntity> = emptyList(),
    val currentBookId: Long = 0,
)

class BooksViewModel(container: AppContainer) : ViewModel() {

    private val bookRepo = container.bookRepository
    private val categoryRepo = container.categoryRepository

    val uiState: StateFlow<BooksUiState> =
        combine(bookRepo.observeAll(), bookRepo.currentBook) { books, current ->
            BooksUiState(books = books, currentBookId = current?.id ?: 0)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BooksUiState())

    /** 新建账本（分类默认为空，可选择从其他账本复制分类） */
    fun createBook(name: String, copyFromBookId: Long?, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val id = bookRepo.createBook(name.trim())
            if (copyFromBookId != null) {
                categoryRepo.copyFromBook(copyFromBookId, id)
            }
            bookRepo.switchTo(id)
            onDone(null)
        }
    }

    fun rename(book: BookEntity, newName: String) {
        viewModelScope.launch { bookRepo.rename(book, newName.trim()) }
    }

    /** 删除账本（最后一个不可删；有数据需 UI 二次确认） */
    fun delete(book: BookEntity, onResult: (String?) -> Unit) {
        viewModelScope.launch { onResult(bookRepo.delete(book)) }
    }

    fun setDefault(book: BookEntity) {
        viewModelScope.launch { bookRepo.setDefault(book) }
    }

    fun switch(book: BookEntity) {
        viewModelScope.launch { bookRepo.switchTo(book.id) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { BooksViewModel(container) }
        }
    }
}

/**
 * 账本管理：新建/重命名/切换/删除（有数据需二次确认）、设置默认账本、
 * 新建时可选择「从其他账本复制分类」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BooksScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: BooksViewModel = viewModel(factory = BooksViewModel.factory(container))
    val state by vm.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<BookEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<BookEntity?>(null) }

    Scaffold(
        topBar = { LedgerTopBar(title = "账本管理", onBack = onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "新建账本")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            items(state.books, key = { it.id }) { book ->
                val isCurrent = book.id == state.currentBookId
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable { vm.switch(book) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(book.name, style = MaterialTheme.typography.titleMedium)
                                if (book.isDefault) {
                                    Spacer(modifier = Modifier.padding(4.dp))
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = "默认账本",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(0.dp),
                                    )
                                }
                            }
                            Text(
                                if (isCurrent) "当前使用 · 点击切换" else "点击切换到此账本",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                            )
                        }
                        if (isCurrent) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { renaming = book }) {
                            Icon(Icons.Default.Edit, contentDescription = "重命名")
                        }
                        TextButton(onClick = { vm.setDefault(book) }) { Text("设为默认") }
                        IconButton(onClick = { pendingDelete = book }) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.expense,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "提示：分类、预算、账户均按账本隔离；最后一个账本不可删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (showCreate) {
        CreateBookDialog(
            existingBooks = state.books,
            onCreate = { name, copyFrom ->
                vm.createBook(name, copyFrom) { err ->
                    scope.launch { snackbar.showSnackbar(err ?: "账本已创建") }
                }
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }

    renaming?.let { book ->
        RenameBookDialog(
            book = book,
            onRename = { newName ->
                vm.rename(book, newName)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    pendingDelete?.let { book ->
        ConfirmDialog(
            title = "删除账本",
            text = "删除账本「${book.name}」将同时删除其下的账户、分类、流水和预算，且不可恢复。确定删除？",
            confirmText = "删除",
            onConfirm = {
                vm.delete(book) { err ->
                    scope.launch { snackbar.showSnackbar(err ?: "已删除账本") }
                }
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateBookDialog(
    existingBooks: List<BookEntity>,
    onCreate: (name: String, copyFromBookId: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var copyFrom by remember { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建账本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("账本名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (existingBooks.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = copyFrom != null, onCheckedChange = { checked ->
                            copyFrom = if (checked) existingBooks.first().id else null
                        })
                        Text("从其他账本复制分类")
                    }
                    if (copyFrom != null) {
                        ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = it }) {
                            OutlinedTextField(
                                value = existingBooks.first { it.id == copyFrom }.name,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("来源账本") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
                            )
                            ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                existingBooks.forEach { b ->
                                    DropdownMenuItem(text = { Text(b.name) }, onClick = {
                                        copyFrom = b.id; menu = false
                                    })
                                }
                            }
                        }
                    }
                }
                Text(
                    "不复制分类时，新账本分类为空，可稍后在分类管理中导入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name, copyFrom) },
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RenameBookDialog(
    book: BookEntity,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(book.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名账本") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("账本名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onRename(name) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
