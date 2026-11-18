package com.youshu.ledger.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.youshu.ledger.data.local.entity.AccountEntity
import com.youshu.ledger.data.local.entity.AttachmentEntity
import com.youshu.ledger.data.local.entity.BookEntity
import com.youshu.ledger.data.local.entity.BudgetEntity
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.CategoryType
import com.youshu.ledger.data.local.entity.ReimburseRecordEntity
import com.youshu.ledger.data.local.entity.RefundRecordEntity
import com.youshu.ledger.data.local.entity.TransactionEntity
import com.youshu.ledger.data.local.entity.TransactionWithDetails
import com.youshu.ledger.data.local.entity.TxnType
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY isDefault DESC, id ASC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY isDefault DESC, id ASC")
    suspend fun getAll(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): BookEntity?

    @Query("SELECT * FROM books WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Delete
    suspend fun delete(book: BookEntity)

    /** 设置默认账本：先将全部账本取消默认，再设置目标（同一事务内调用） */
    @Query("UPDATE books SET isDefault = 0")
    suspend fun clearDefault()

    @Query("UPDATE books SET isDefault = 1 WHERE id = :id")
    suspend fun markDefault(id: Long)

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    @Query("DELETE FROM books")
    suspend fun clearAll()
}

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts WHERE bookId = :bookId ORDER BY sortOrder ASC, id ASC")
    fun observeByBook(bookId: Long): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE bookId = :bookId ORDER BY sortOrder ASC, id ASC")
    suspend fun getByBook(bookId: Long): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity): Long

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query("DELETE FROM accounts")
    suspend fun clearAll()
}

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories WHERE bookId = :bookId ORDER BY sortOrder ASC, id ASC")
    fun observeByBook(bookId: Long): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE bookId = :bookId AND type = :type ORDER BY sortOrder ASC, id ASC")
    suspend fun getByBookAndType(bookId: Long, type: CategoryType): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE bookId = :bookId ORDER BY sortOrder ASC, id ASC")
    suspend fun getByBook(bookId: Long): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<CategoryEntity>): List<Long>

    @Delete
    suspend fun delete(category: CategoryEntity)

    @Query("SELECT COUNT(*) FROM categories WHERE bookId = :bookId")
    suspend fun countByBook(bookId: Long): Int

    @Query("DELETE FROM categories")
    suspend fun clearAll()
}

@Dao
interface TransactionDao {

    // ---------- 查询 ----------

    /** 某账本全部流水（含子表），按业务日期倒序、录入时间倒序 */
    @Transaction
    @Query("SELECT * FROM transactions WHERE bookId = :bookId ORDER BY businessDate DESC, createdAt DESC, id DESC")
    fun observeByBook(bookId: Long): Flow<List<TransactionWithDetails>>

    @Transaction
    @Query("SELECT * FROM transactions WHERE bookId = :bookId ORDER BY businessDate DESC, createdAt DESC, id DESC")
    suspend fun getByBook(bookId: Long): List<TransactionWithDetails>

    @Transaction
    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getWithDetails(id: Long): TransactionWithDetails?

    @Transaction
    @Query("SELECT * FROM transactions WHERE id IN (:ids)")
    suspend fun getWithDetailsByIds(ids: List<Long>): List<TransactionWithDetails>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    /**
     * 按条件筛选流水（账本/账户/分类/类型/备注关键字/日期区间），均传 null 表示不限制。
     * 账户匹配转出或转入（转账双向命中）。
     */
    @Transaction
    @Query(
        """SELECT * FROM transactions WHERE bookId = :bookId
             AND (:accountId IS NULL OR accountId = :accountId OR toAccountId = :accountId)
             AND (:categoryId IS NULL OR categoryId = :categoryId)
             AND (:type IS NULL OR type = :type)
             AND (:keyword IS NULL OR note LIKE '%' || :keyword || '%')
             AND (:startDate IS NULL OR businessDate >= :startDate)
             AND (:endDate IS NULL OR businessDate <= :endDate)
           ORDER BY businessDate DESC, createdAt DESC, id DESC""",
    )
    fun observeFiltered(
        bookId: Long,
        accountId: Long?,
        categoryId: Long?,
        type: TxnType?,
        keyword: String?,
        startDate: Long?,
        endDate: Long?,
    ): Flow<List<TransactionWithDetails>>

    /** 指定账户、且业务日期 >= 启用日期的流水（余额计算专用） */
    @Query(
        """SELECT * FROM transactions WHERE
             (accountId = :accountId OR toAccountId = :accountId) AND businessDate >= :enableDate""",
    )
    suspend fun getBalanceRelevant(accountId: Long, enableDate: Long): List<TransactionEntity>

    /** 某账户的全部流水（含补录）：余额计算中退款/报销/收回「发生即计入」，需遍历全部流水 */
    @Query("SELECT * FROM transactions WHERE accountId = :accountId OR toAccountId = :accountId")
    suspend fun getAllTouchingAccount(accountId: Long): List<TransactionEntity>

    /** 某账户全部流水（用于删除账户前检查 / 单账户流水页，含补录） */
    @Transaction
    @Query("SELECT * FROM transactions WHERE accountId = :accountId OR toAccountId = :accountId ORDER BY businessDate DESC, createdAt DESC")
    fun observeByAccount(accountId: Long): Flow<List<TransactionWithDetails>>

    @Query("SELECT COUNT(*) FROM transactions WHERE accountId = :accountId OR toAccountId = :accountId")
    suspend fun countByAccount(accountId: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE bookId = :bookId")
    suspend fun countByBook(bookId: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId")
    suspend fun countByCategory(categoryId: Long): Int

    /** CSV 导入的重复检测：同一账本内 业务日期+金额+账户+类型 相同视为重复 */
    @Query(
        """SELECT * FROM transactions WHERE bookId = :bookId AND type = :type
             AND amount = :amount AND businessDate = :businessDate
             AND accountId = :accountId""",
    )
    suspend fun findDuplicate(
        bookId: Long,
        type: TxnType,
        amount: Long,
        businessDate: Long,
        accountId: Long,
    ): List<TransactionEntity>

    // ---------- 统计（均按业务日期归档） ----------

    @Query(
        """SELECT COALESCE(SUM(amount), 0) FROM transactions
             WHERE bookId = :bookId AND type = :type AND businessDate >= :start AND businessDate <= :end""",
    )
    suspend fun sumByType(bookId: Long, type: TxnType, start: Long, end: Long): Long

    @Query("SELECT COALESCE(SUM(amount), 0) FROM refund_records WHERE transactionId = :txnId")
    suspend fun sumRefunds(txnId: Long): Long?

    @Query("SELECT COALESCE(SUM(amount), 0) FROM reimburse_records WHERE transactionId = :txnId")
    suspend fun sumReimbursements(txnId: Long): Long

    /** 区间内的支出，按分类汇总（金额合计；退款/报销通过子表另行汇总） */
    @Query(
        """SELECT categoryId AS categoryId, COALESCE(SUM(amount), 0) AS total
             FROM transactions
             WHERE bookId = :bookId AND type = 'EXPENSE' AND businessDate >= :start AND businessDate <= :end
               AND categoryId IS NOT NULL
             GROUP BY categoryId""",
    )
    suspend fun sumExpenseByCategory(bookId: Long, start: Long, end: Long): List<CategorySum>

    /** 区间内所有支出流水的 id（用于逐笔汇总退款/报销/代付） */
    @Query(
        """SELECT * FROM transactions
             WHERE bookId = :bookId AND type = 'EXPENSE' AND businessDate >= :start AND businessDate <= :end""",
    )
    suspend fun getExpensesInRange(bookId: Long, start: Long, end: Long): List<TransactionEntity>

    /** 按月汇总支出（趋势柱状图），start/end 限定在若干月内 */
    @Query(
        """SELECT businessDate AS day, COALESCE(SUM(amount), 0) AS total
             FROM transactions
             WHERE bookId = :bookId AND type = :type AND businessDate >= :start AND businessDate <= :end
             GROUP BY businessDate ORDER BY businessDate ASC""",
    )
    suspend fun sumByDay(bookId: Long, type: TxnType, start: Long, end: Long): List<DaySum>

    /** 区间内退款合计（归属原支出的业务日期） */
    @Query(
        """SELECT COALESCE(SUM(r.amount), 0) FROM refund_records r
             INNER JOIN transactions t ON t.id = r.transactionId
             WHERE t.bookId = :bookId AND t.type = 'EXPENSE'
               AND t.businessDate >= :start AND t.businessDate <= :end""",
    )
    suspend fun sumRefundsInRange(bookId: Long, start: Long, end: Long): Long

    /** 区间内报销合计（归属原支出的业务日期） */
    @Query(
        """SELECT COALESCE(SUM(r.amount), 0) FROM reimburse_records r
             INNER JOIN transactions t ON t.id = r.transactionId
             WHERE t.bookId = :bookId AND t.type = 'EXPENSE'
               AND t.businessDate >= :start AND t.businessDate <= :end""",
    )
    suspend fun sumReimbursementsInRange(bookId: Long, start: Long, end: Long): Long

    /** 区间内代付/已收回合计 */
    @Query(
        """SELECT COALESCE(SUM(advanceAmount), 0) FROM transactions
             WHERE bookId = :bookId AND type = 'EXPENSE' AND businessDate >= :start AND businessDate <= :end""",
    )
    suspend fun sumAdvanceInRange(bookId: Long, start: Long, end: Long): Long

    @Query(
        """SELECT COALESCE(SUM(recoveredAmount), 0) FROM transactions
             WHERE bookId = :bookId AND type = 'EXPENSE' AND businessDate >= :start AND businessDate <= :end""",
    )
    suspend fun sumRecoveredInRange(bookId: Long, start: Long, end: Long): Long

    /** 应收总额（别人欠我）= Σ(代付 − 已收回)，全账本累计 */
    @Query(
        """SELECT COALESCE(SUM(advanceAmount - recoveredAmount), 0) FROM transactions
             WHERE bookId = :bookId AND type = 'EXPENSE'""",
    )
    suspend fun receivableTotal(bookId: Long): Long

    // ---------- 写入 ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(txn: TransactionEntity): Long

    @Update
    suspend fun update(txn: TransactionEntity)

    @Delete
    suspend fun delete(txn: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM transactions")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRefund(record: RefundRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReimburse(record: ReimburseRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttachment(attachment: AttachmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttachments(attachments: List<AttachmentEntity>)

    @Delete
    suspend fun deleteRefund(record: RefundRecordEntity)

    @Delete
    suspend fun deleteReimburse(record: ReimburseRecordEntity)

    @Delete
    suspend fun deleteAttachment(attachment: AttachmentEntity)
}

@Dao
interface BudgetDao {

    @Query("SELECT * FROM budgets WHERE bookId = :bookId AND yearMonth = :yearMonth")
    suspend fun getByMonth(bookId: Long, yearMonth: String): List<BudgetEntity>

    @Query("SELECT * FROM budgets WHERE bookId = :bookId AND yearMonth = :yearMonth AND categoryId IS NULL LIMIT 1")
    suspend fun getTotalBudget(bookId: Long, yearMonth: String): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE bookId = :bookId AND yearMonth = :yearMonth AND categoryId IS NOT NULL")
    suspend fun getCategoryBudgets(bookId: Long, yearMonth: String): List<BudgetEntity>

    @Query("SELECT * FROM budgets WHERE bookId = :bookId")
    suspend fun getAllByBook(bookId: Long): List<BudgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: BudgetEntity): Long

    @Delete
    suspend fun delete(budget: BudgetEntity)

    @Query("DELETE FROM budgets WHERE bookId = :bookId AND yearMonth = :yearMonth AND categoryId IS NULL")
    suspend fun deleteTotal(bookId: Long, yearMonth: String)

    @Query("DELETE FROM budgets WHERE bookId = :bookId AND yearMonth = :yearMonth AND categoryId = :categoryId")
    suspend fun deleteCategoryBudget(bookId: Long, yearMonth: String, categoryId: Long)

    @Query("SELECT * FROM budgets WHERE bookId = :bookId AND yearMonth = :yearMonth AND categoryId = :categoryId LIMIT 1")
    suspend fun getCategoryBudget(bookId: Long, yearMonth: String, categoryId: Long): BudgetEntity?

    @Query("DELETE FROM budgets")
    suspend fun clearAll()
}

data class CategorySum(val categoryId: Long, val total: Long)
data class DaySum(val day: Long, val total: Long)
