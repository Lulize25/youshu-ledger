package com.youshu.ledger.data.io

import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.CategoryType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * 分类导出 / 导入的 JSON 传输模型（v1.5.1 起分类全局共享）。
 * 以树形结构导出，父子关系靠嵌套表达（不依赖本机 id），可跨设备导入。
 */

@Serializable
data class CategoryExportFile(
    val formatVersion: Int = 1,
    val app: String = "youshu-ledger-categories",
    val exportedAt: Long,
    val roots: List<CategoryExportNode> = emptyList(),
)

@Serializable
data class CategoryExportNode(
    val type: String,            // EXPENSE / INCOME
    val name: String,
    val iconText: String,
    val color: Long,
    val children: List<CategoryExportNode> = emptyList(),
)

object CategoryTransfer {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** 全部分类 → 嵌套树 JSON 文本 */
    fun buildExport(categories: List<CategoryEntity>, now: Long): String {
        val ofType = { type: CategoryType ->
            val list = categories.filter { it.type == type }.sortedBy { it.sortOrder }
            val roots = list.filter { it.parentId == null }
            fun build(cat: CategoryEntity): CategoryExportNode = CategoryExportNode(
                type = cat.type.name,
                name = cat.name,
                iconText = cat.iconText,
                color = cat.color,
                children = list.filter { it.parentId == cat.id }.map { build(it) },
            )
            roots.map { build(it) }
        }
        val file = CategoryExportFile(
            exportedAt = now,
            roots = CategoryType.entries.flatMap { ofType(it) },
        )
        return json.encodeToString(CategoryExportFile.serializer(), file)
    }

    /** 解析导入文件；格式非法抛出异常 */
    fun parse(text: String): List<CategoryExportNode> {
        val file = json.decodeFromString(CategoryExportFile.serializer(), text)
        require(file.app == "youshu-ledger-categories") { "不是分类导出文件" }
        return file.roots
    }
}
