package com.youshu.ledger.data.repository

import com.youshu.ledger.data.local.dao.CategoryDao
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.CategoryType
import kotlinx.coroutines.flow.Flow

/** 分类仓库（按账本隔离，树状多级） */
class CategoryRepository(private val dao: CategoryDao) {

    fun observeByBook(bookId: Long): Flow<List<CategoryEntity>> = dao.observeByBook(bookId)

    suspend fun getByBook(bookId: Long): List<CategoryEntity> = dao.getByBook(bookId)

    suspend fun getById(id: Long): CategoryEntity? = dao.getById(id)

    suspend fun getByIds(ids: List<Long>): List<CategoryEntity> = dao.getByIds(ids)

    suspend fun upsert(category: CategoryEntity): Long = dao.upsert(category)

    suspend fun delete(category: CategoryEntity) = dao.delete(category)

    /**
     * 从其他账本复制分类到目标账本（含子分类，生成新 id，互不影响）。
     * 返回新增分类数量。
     */
    suspend fun copyFromBook(fromBookId: Long, toBookId: Long): Int {
        val source = dao.getByBook(fromBookId)
        if (source.isEmpty()) return 0
        val idMap = HashMap<Long, Long>()
        var order = dao.countByBook(toBookId)
        // 先建父分类，再建子分类（保证 parentId 可映射）
        val sorted = source.sortedBy { it.parentId == null }
        val newList = sorted.map { c ->
            val newParent = c.parentId?.let { idMap[it] }
            val copy = c.copy(id = 0, bookId = toBookId, parentId = newParent, sortOrder = order++)
            val newId = dao.upsert(copy)
            idMap[c.id] = newId
            copy.copy(id = newId)
        }
        return newList.size
    }

    /** 按类型组织成两层树（UI 网格展示用） */
    fun buildTree(
        categories: List<CategoryEntity>,
        type: CategoryType,
    ): List<Pair<CategoryEntity, List<CategoryEntity>>> {
        val ofType = categories.filter { it.type == type }.sortedBy { it.sortOrder }
        val roots = ofType.filter { it.parentId == null }
        return roots.map { root ->
            root to ofType.filter { it.parentId == root.id }
        }
    }

    /** 分类完整路径（如「餐饮/外卖」），CSV 导出用 */
    suspend fun fullPath(categoryId: Long, all: List<CategoryEntity>? = null): String {
        val list = all ?: getByBook(getById(categoryId)?.bookId ?: return "")
        val byId = list.associateBy { it.id }
        val parts = mutableListOf<String>()
        var cur: CategoryEntity? = byId[categoryId]
        var guard = 0
        while (cur != null && guard++ < 10) {
            parts.add(0, cur.name)
            cur = cur.parentId?.let { byId[it] }
        }
        return parts.joinToString("/")
    }
}
