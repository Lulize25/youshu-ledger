package com.youshu.ledger.data.local.seed

import com.youshu.ledger.data.local.dao.BookDao
import com.youshu.ledger.data.local.dao.CategoryDao
import com.youshu.ledger.data.local.entity.BookEntity
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.CategoryType

/**
 * 预置默认数据。
 * 首次启动自动创建默认账本「日常账本」，并写入预置分类（用户可增删改）。
 * 支出：餐饮（正餐/外卖/零食饮料）、交通（公交地铁/打车/加油停车）、购物（日用/服饰/数码）、
 *      居住（房租/水电物业）、娱乐（电影游戏/旅行）、医疗、教育、人情往来、宠物、其他
 * 收入：工资、奖金、兼职、理财收益、红包、其他
 */
object DefaultDataSeeder {

    /** 主色板：分类圆形底色可选色（ARGB） */
    val CATEGORY_COLORS: List<Long> = listOf(
        0xFFE57373, 0xFFF06292, 0xFFBA68C8, 0xFF9575CD,
        0xFF7986CB, 0xFF64B5F6, 0xFF4FC3F7, 0xFF4DD0E1,
        0xFF4DB6AC, 0xFF81C784, 0xFFAED581, 0xFFFFD54F,
        0xFFFFB74D, 0xFFFF8A65, 0xFF90A4AE, 0xFF8D6E63,
    )

    /** (分类名, 子分类名列表) */
    private val EXPENSE_TREE: List<Pair<String, List<String>>> = listOf(
        "餐饮" to listOf("正餐", "外卖", "零食饮料"),
        "交通" to listOf("公交地铁", "打车", "加油停车"),
        "购物" to listOf("日用", "服饰", "数码"),
        "居住" to listOf("房租", "水电物业"),
        "娱乐" to listOf("电影游戏", "旅行"),
        "医疗" to emptyList(),
        "教育" to emptyList(),
        "人情往来" to emptyList(),
        "宠物" to emptyList(),
        "其他" to emptyList(),
    )

    private val INCOME_TREE: List<Pair<String, List<String>>> = listOf(
        "工资" to emptyList(),
        "奖金" to emptyList(),
        "兼职" to emptyList(),
        "理财收益" to emptyList(),
        "红包" to emptyList(),
        "其他" to emptyList(),
    )

    /**
     * 若数据库为空则创建默认账本与预置分类。
     * 返回默认账本 id（已存在则直接返回已有默认账本 id）。
     */
    suspend fun seedIfEmpty(bookDao: BookDao, categoryDao: CategoryDao, now: Long): Long {
        val existingDefault = bookDao.getDefault()
        if (existingDefault != null) return existingDefault.id

        val bookId = bookDao.upsert(
            BookEntity(name = "日常账本", createdAt = now, isDefault = true),
        )
        insertCategoriesForBook(categoryDao, bookId)
        return bookId
    }

    /** 将预置分类写入指定账本（用于新建账本时「从其他账本复制分类」之外的初始填充） */
    suspend fun insertCategoriesForBook(categoryDao: CategoryDao, bookId: Long) {
        var order = 0
        var colorIndex = 0
        fun nextColor(): Long = CATEGORY_COLORS[colorIndex++ % CATEGORY_COLORS.size]

        CategoryType.entries.forEach { type ->
            val tree = if (type == CategoryType.EXPENSE) EXPENSE_TREE else INCOME_TREE
            tree.forEach { (name, children) ->
                val parentId = categoryDao.upsert(
                    CategoryEntity(
                        bookId = bookId,
                        type = type,
                        parentId = null,
                        name = name,
                        sortOrder = order++,
                        color = nextColor(),
                        iconText = name.take(2),
                    ),
                )
                children.forEach { child ->
                    categoryDao.upsert(
                        CategoryEntity(
                            bookId = bookId,
                            type = type,
                            parentId = parentId,
                            name = child,
                            sortOrder = order++,
                            color = nextColor(),
                            iconText = child.take(2),
                        ),
                    )
                }
            }
        }
    }
}
