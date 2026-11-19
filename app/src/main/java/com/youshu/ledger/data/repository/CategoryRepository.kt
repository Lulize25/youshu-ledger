package com.youshu.ledger.data.repository

import com.youshu.ledger.data.local.dao.CategoryDao
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.CategoryType
import kotlinx.coroutines.flow.Flow

/** 分类仓库（v1.5.1 起全局共享，不再按账本隔离；树状多级） */
class CategoryRepository(private val dao: CategoryDao) {

    fun observeAll(): Flow<List<CategoryEntity>> = dao.observeAll()

    suspend fun getAll(): List<CategoryEntity> = dao.getAll()

    suspend fun getByType(type: CategoryType): List<CategoryEntity> = dao.getByType(type)

    suspend fun getById(id: Long): CategoryEntity? = dao.getById(id)

    suspend fun getByIds(ids: List<Long>): List<CategoryEntity> = dao.getByIds(ids)

    suspend fun upsert(category: CategoryEntity): Long = dao.upsert(category.copy(bookId = 0))

    suspend fun delete(category: CategoryEntity) = dao.delete(category)

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
        val list = all ?: getAll()
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

    /**
     * 导入分类树：按「类型 + 父分类 + 名称」去重，已存在的跳过、缺失的创建。
     * 返回新增分类数量。
     */
    suspend fun importTree(nodes: List<com.youshu.ledger.data.io.CategoryExportNode>): Int {
        if (nodes.isEmpty()) return 0
        var order = dao.countAll()
        var created = 0

        // 栈实现先父后子遍历：父分类先创建，子分类才能挂 parentId
        data class Frame(val node: com.youshu.ledger.data.io.CategoryExportNode, val parentId: Long?, val type: CategoryType)

        val stack = ArrayDeque<Frame>()
        nodes.forEach { node ->
            val type = runCatching { CategoryType.valueOf(node.type) }.getOrNull() ?: return@forEach
            stack.addLast(Frame(node, null, type))
        }
        while (stack.isNotEmpty()) {
            val (node, parentId, type) = stack.removeLast()
            val existing = dao.getAll().firstOrNull {
                it.type == type && it.parentId == parentId && it.name == node.name
            }
            val id = if (existing != null) {
                existing.id
            } else {
                created++
                dao.upsert(
                    CategoryEntity(
                        bookId = 0, type = type, parentId = parentId,
                        name = node.name, sortOrder = order++,
                        color = node.color, iconText = node.iconText.ifBlank { node.name.take(2) },
                    ),
                )
            }
            // 逆序压栈保证创建顺序与文件一致
            node.children.asReversed().forEach { child ->
                stack.addLast(Frame(child, id, type))
            }
        }
        return created
    }
}
