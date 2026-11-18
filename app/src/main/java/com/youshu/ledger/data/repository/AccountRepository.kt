package com.youshu.ledger.data.repository

import com.youshu.ledger.data.local.dao.AccountDao
import com.youshu.ledger.data.local.dao.TransactionDao
import com.youshu.ledger.data.local.entity.AccountEntity
import kotlinx.coroutines.flow.Flow

/** 账户仓库（按账本隔离）。支持负余额；归档后不再出现在选择器中。 */
class AccountRepository(
    private val dao: AccountDao,
    private val txnDao: TransactionDao,
) {

    fun observeByBook(bookId: Long): Flow<List<AccountEntity>> = dao.observeByBook(bookId)

    suspend fun getByBook(bookId: Long): List<AccountEntity> = dao.getByBook(bookId)

    suspend fun getById(id: Long): AccountEntity? = dao.getById(id)

    suspend fun upsert(account: AccountEntity): Long = dao.upsert(account)

    /** 删除账户（有流水的账户禁止删除，UI 先提示归档） */
    suspend fun delete(account: AccountEntity): Boolean {
        if (txnDao.countByAccount(account.id) > 0) return false
        dao.delete(account)
        return true
    }

    suspend fun setArchived(account: AccountEntity, archived: Boolean) =
        dao.upsert(account.copy(archived = archived))
}
