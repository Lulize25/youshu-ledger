package com.youshu.ledger.data.repository

import com.youshu.ledger.data.local.dao.BudgetDao
import com.youshu.ledger.data.local.entity.BudgetEntity

/**
 * 预算仓库（按账本）：月度总预算 + 分类级预算，均可选。
 * yearMonth 格式「yyyy-MM」。
 */
class BudgetRepository(private val dao: BudgetDao) {

    /** 指定月的全部预算（总预算 categoryId=null + 分类预算） */
    suspend fun getForMonth(bookId: Long, yearMonth: String): List<BudgetEntity> =
        dao.getByMonth(bookId, yearMonth)

    suspend fun getTotalBudget(bookId: Long, yearMonth: String): BudgetEntity? =
        dao.getTotalBudget(bookId, yearMonth)

    /** 设置/清除月度总预算（amount=null 表示清除） */
    suspend fun setTotalBudget(bookId: Long, yearMonth: String, amount: Long?) {
        if (amount == null) {
            dao.deleteTotal(bookId, yearMonth)
        } else {
            dao.upsert(BudgetEntity(bookId = bookId, categoryId = null, yearMonth = yearMonth, amount = amount))
        }
    }

    /** 设置/清除某分类预算（amount=null 表示清除） */
    suspend fun setCategoryBudget(bookId: Long, yearMonth: String, categoryId: Long, amount: Long?) {
        if (amount == null) {
            dao.deleteCategoryBudget(bookId, yearMonth, categoryId)
        } else {
            dao.upsert(BudgetEntity(bookId = bookId, categoryId = categoryId, yearMonth = yearMonth, amount = amount))
        }
    }
}
