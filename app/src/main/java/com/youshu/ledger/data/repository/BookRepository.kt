package com.youshu.ledger.data.repository

import com.youshu.ledger.data.local.dao.BookDao
import com.youshu.ledger.data.local.entity.BookEntity
import com.youshu.ledger.data.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 账本仓库。
 * 规则：至少保留一个账本，最后一个不可删除；默认账本全局唯一；
 * 当前选中账本记录在设置中，缺省跟随默认账本。
 */
class BookRepository(
    private val dao: BookDao,
    private val settings: SettingsStore,
) {

    fun observeAll(): Flow<List<BookEntity>> = dao.observeAll()

    /** 当前账本：设置中的选中项 → 否则默认账本 → 否则第一个 */
    val currentBook: Flow<BookEntity?> =
        kotlinx.coroutines.flow.combine(settings.currentBookId, dao.observeAll()) { chosen, books ->
            books.firstOrNull { it.id == chosen }
                ?: books.firstOrNull { it.isDefault }
                ?: books.firstOrNull()
        }

    suspend fun getById(id: Long): BookEntity? = dao.getById(id)

    suspend fun createBook(name: String): Long =
        dao.upsert(BookEntity(name = name, createdAt = System.currentTimeMillis(), isDefault = false))

    suspend fun rename(book: BookEntity, newName: String) = dao.update(book.copy(name = newName))

    suspend fun switchTo(id: Long) = settings.setCurrentBookId(id)

    /** 删除账本；返回错误信息（null 表示成功） */
    suspend fun delete(book: BookEntity): String? {
        if (dao.count() <= 1) return "至少保留一个账本，不能删除最后一个账本"
        dao.delete(book)
        // 若删除的是当前选中账本，回退到默认账本
        return null
    }

    /** 设为默认账本（先清除其他默认标记） */
    suspend fun setDefault(book: BookEntity) {
        dao.clearDefault()
        dao.markDefault(book.id)
    }

    /** 默认账本（首次启动由 seeder 创建） */
    suspend fun defaultBook(): BookEntity? = dao.getDefault()
}
