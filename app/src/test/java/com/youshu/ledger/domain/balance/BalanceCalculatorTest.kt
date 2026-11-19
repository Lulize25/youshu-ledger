package com.youshu.ledger.domain.balance

import com.youshu.ledger.data.local.entity.AccountEntity
import com.youshu.ledger.data.local.entity.AttachmentEntity
import com.youshu.ledger.data.local.entity.ReimburseRecordEntity
import com.youshu.ledger.data.local.entity.RefundRecordEntity
import com.youshu.ledger.data.local.entity.TransactionEntity
import com.youshu.ledger.data.local.entity.TransactionWithDetails
import com.youshu.ledger.data.local.entity.TxnType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * 余额与补录核心规则单元测试。
 * 对应需求文档验收用例 1~6 的余额部分。
 *
 * 时间设定：「今天」= 2026-11-16；账户启用日 = 2026-11-01。
 */
class BalanceCalculatorTest {

    private val today: Long = LocalDate.of(2026, 11, 16).atStartOfDay(java.time.ZoneId.systemDefault())
        .toInstant().toEpochMilli()
    private val enableDate: Long = LocalDate.of(2026, 11, 1).atStartOfDay(java.time.ZoneId.systemDefault())
        .toInstant().toEpochMilli()
    private val backfillDate: Long = LocalDate.of(2025, 6, 1).atStartOfDay(java.time.ZoneId.systemDefault())
        .toInstant().toEpochMilli()

    private val account = AccountEntity(
        id = 1L, bookId = 1L, name = "微信", iconKey = "wechat",
        initialBalance = 500_00, enableDate = enableDate, sortOrder = 0, archived = false,
    )

    private fun expense(
        id: Long,
        amount: Long,
        date: Long = today,
        advance: Long = 0,
        recovered: Long = 0,
        accountId: Long = 1L,
    ) = TransactionEntity(
        id = id, bookId = 1L, type = TxnType.EXPENSE, amount = amount,
        businessDate = date, createdAt = today, note = "", categoryId = 10L,
        accountId = accountId, toAccountId = null,
        advanceAmount = advance, recoveredAmount = recovered,
    )

    private fun income(id: Long, amount: Long, date: Long = today, accountId: Long = 1L) =
        TransactionEntity(
            id = id, bookId = 1L, type = TxnType.INCOME, amount = amount,
            businessDate = date, createdAt = today, note = "", categoryId = 20L,
            accountId = accountId, toAccountId = null,
        )

    private fun transfer(id: Long, amount: Long, fee: Long = 0, out: Long = 1L, into: Long = 2L) =
        TransactionEntity(
            id = id, bookId = 1L, type = TxnType.TRANSFER, amount = amount,
            businessDate = today, createdAt = today, note = "",
            categoryId = null, accountId = out, toAccountId = into, fee = fee,
        )

    private fun detail(
        txn: TransactionEntity,
        refunds: List<RefundRecordEntity> = emptyList(),
        reimbursements: List<ReimburseRecordEntity> = emptyList(),
    ) = TransactionWithDetails(txn, refunds, reimbursements, emptyList(), emptyList<AttachmentEntity>())

    private fun balance(vararg items: TransactionWithDetails) =
        BalanceCalculator.computeBalance(account, items.toList())

    /** 验收用例 1：期初 500，支出 100 → 400，收入 200 → 600 */
    @Test
    fun `case1 basic expense and income affect balance`() {
        assertEquals(400_00, balance(detail(expense(1, 100_00))))
        assertEquals(600_00, balance(detail(expense(1, 100_00)), detail(income(2, 200_00))))
    }

    /** 验收用例 2：补录支出 300 不改变余额（500 期初 + 200 收入 = 700） */
    @Test
    fun `case2 backfill transaction does not affect balance`() {
        val items = arrayOf(
            detail(income(1, 200_00)),
            detail(expense(2, 300_00, date = backfillDate)),
        )
        assertEquals(700_00, balance(*items))
    }

    /** 验收用例 3：今日支出 100，退款 40 → 余额 +40（净支出 60） */
    @Test
    fun `case3 refund adds to balance immediately`() {
        val refunded = detail(
            expense(1, 100_00),
            refunds = listOf(
                RefundRecordEntity(id = 1, transactionId = 1, amount = 40_00, accountId = 1, timeMillis = today, note = "", createdAt = today),
            ),
        )
        assertEquals(440_00, balance(refunded))
        // 统计口径：净支出 = 100 - 40 = 60
        assertEquals(60_00, BalanceCalculator.netExpense(refunded.transaction, 40_00, 0))
    }

    /** 验收用例 4：支出 100 代付 40、收回 40 → 余额先 −100 再 +40，应收归 0，个人实际支出仍为 60 */
    @Test
    fun `case4 advance and recovery`() {
        val settled = detail(expense(1, 100_00, advance = 40_00, recovered = 40_00))
        assertEquals(440_00, balance(settled))
        assertEquals(0, BalanceCalculator.receivableOf(settled.transaction))
        assertEquals(60_00, BalanceCalculator.personalExpense(settled.transaction, 0, 0))
    }

    /** 验收用例 5：账户 A 向 B 转账 100（手续费 0）→ A−100、B+100；手续费 5 时 A−105 */
    @Test
    fun `case5 transfer between accounts`() {
        val accountB = account.copy(id = 2L, name = "银行卡", initialBalance = 0, enableDate = enableDate)
        val t = transfer(1, 100_00)
        assertEquals(400_00, BalanceCalculator.computeBalance(account, listOf(detail(t))))
        assertEquals(100_00, BalanceCalculator.computeBalance(accountB, listOf(detail(t))))

        val tWithFee = transfer(2, 100_00, fee = 5_00)
        assertEquals(395_00, BalanceCalculator.computeBalance(account, listOf(detail(tWithFee))))
    }

    /** 验收用例 6：删除启用日之后的流水，余额正确回滚（派生计算天然保证） */
    @Test
    fun `case6 deleting transaction rolls back balance`() {
        val withBoth = listOf(detail(expense(1, 100_00)), detail(income(2, 200_00)))
        val afterDelete = withBoth.drop(1) // 模拟删除支出流水
        assertEquals(700_00, BalanceCalculator.computeBalance(account, afterDelete))
    }

    /** 转账手续费从转出账户扣除、不影响转入账户 */
    @Test
    fun `transfer fee only deducted from source account`() {
        val accountB = account.copy(id = 2L, initialBalance = 0)
        val t = transfer(1, 100_00, fee = 5_00)
        assertEquals(395_00, BalanceCalculator.computeBalance(account, listOf(detail(t))))
        assertEquals(100_00, BalanceCalculator.computeBalance(accountB, listOf(detail(t))))
    }

    /** 补录流水上的退款仍计入余额（发生即计入），但补录支出本身不计 */
    @Test
    fun `refund on backfill counts but expense does not`() {
        val backfillRefunded = detail(
            expense(1, 300_00, date = backfillDate),
            refunds = listOf(
                RefundRecordEntity(id = 1, transactionId = 1, amount = 50_00, accountId = 1, timeMillis = today, note = "", createdAt = today),
            ),
        )
        assertEquals(550_00, balance(backfillRefunded))
    }

    /** 补录判断精确到分钟：账户今天 14:00 启用，今天 09:00 的支出为补录不计余额，15:00 的支出计入 */
    @Test
    fun `backfill precise to minute against enable time`() {
        val acc = account.copy(enableDate = today, enableTimeOfDay = 14 * 60)
        val morning = expense(1, 100_00, date = today).copy(timeOfDay = 9 * 60)
        val afternoon = expense(2, 100_00, date = today).copy(timeOfDay = 15 * 60)
        assertEquals(500_00, BalanceCalculator.computeBalance(acc, listOf(detail(morning))))
        assertEquals(400_00, BalanceCalculator.computeBalance(acc, listOf(detail(afternoon))))
    }

    /** 未记录时间的流水按当天 23:59 判定：启用日当天无时间流水仍计入余额（旧数据行为不变） */
    @Test
    fun `txn without time on enable day counts`() {
        val acc = account.copy(enableDate = today, enableTimeOfDay = 14 * 60)
        val noTime = expense(1, 100_00, date = today) // timeOfDay 默认 -1
        assertEquals(400_00, BalanceCalculator.computeBalance(acc, listOf(detail(noTime))))
    }

    /** 未设置启用时间的账户按当天 00:00 判定：启用日当天任何流水都计入（旧账户行为不变） */
    @Test
    fun `account without enable time counts whole day`() {
        val acc = account.copy(enableDate = today, enableTimeOfDay = -1)
        val early = expense(1, 100_00, date = today).copy(timeOfDay = 0)
        assertEquals(400_00, BalanceCalculator.computeBalance(acc, listOf(detail(early))))
    }

    /** 截至时刻（asOf）：只统计生效时刻 ≤ asOf 的流水，之后的流水不计入 */
    @Test
    fun `asOf cutoff excludes later transactions`() {
        val asOf = today + 12 * 60 * 60_000L // 今天 12:00
        val before = expense(1, 100_00, date = today).copy(timeOfDay = 9 * 60)
        val after = expense(2, 100_00, date = today).copy(timeOfDay = 15 * 60)
        // 12:00 时点上：只有 9:00 的支出已发生 → 500 - 100 = 400
        assertEquals(
            400_00,
            BalanceCalculator.computeBalance(account, listOf(detail(before), detail(after)), asOf),
        )
        // 不传 asOf = 当前余额：两笔都计入
        assertEquals(
            300_00,
            BalanceCalculator.computeBalance(account, listOf(detail(before), detail(after))),
        )
    }

    /** 截至时刻对退款同样生效：asOf 之后才发生的退款不计入 */
    @Test
    fun `asOf cutoff excludes later refunds`() {
        val asOf = today + 12 * 60 * 60_000L
        val refunded = detail(
            expense(1, 100_00, date = today).copy(timeOfDay = 9 * 60),
            refunds = listOf(
                RefundRecordEntity(id = 1, transactionId = 1, amount = 40_00, accountId = 1, timeMillis = today + 15 * 60 * 60_000L, note = "", createdAt = today),
            ),
        )
        // 退款发生在 15:00（asOf 之后）→ 12:00 时点上余额 = 500 - 100 = 400
        assertEquals(400_00, BalanceCalculator.computeBalance(account, listOf(refunded), asOf))
    }

    /** v1.5.0：退款计入到账账户而非流水账户（微信支出、退到银行卡 → 微信 −100、银行卡 +40） */
    @Test
    fun `refund goes to its own account`() {
        val bank = account.copy(id = 2L, name = "银行卡", initialBalance = 0)
        val refunded = detail(
            expense(1, 100_00, accountId = 1),
            refunds = listOf(
                RefundRecordEntity(id = 1, transactionId = 1, amount = 40_00, accountId = 2, timeMillis = today, note = "", createdAt = today),
            ),
        )
        assertEquals(400_00, BalanceCalculator.computeBalance(account, listOf(refunded)))
        assertEquals(40_00, BalanceCalculator.computeBalance(bank, listOf(refunded)))
    }

    /** v1.5.0：退款/报销的 asOf 判定使用「发生时间」而非录入时间（createdAt） */
    @Test
    fun `asOf filters refunds by occurrence time not created time`() {
        val asOf = today + 12 * 60 * 60_000L
        // 退款实际发生在 10:00（应计入），但晚于 asOf 才录入（createdAt = 15:00）
        val refunded = detail(
            expense(1, 100_00, date = today).copy(timeOfDay = 9 * 60),
            refunds = listOf(
                RefundRecordEntity(id = 1, transactionId = 1, amount = 40_00, accountId = 1, timeMillis = today + 10 * 60 * 60_000L, note = "", createdAt = today + 15 * 60 * 60_000L),
            ),
        )
        assertEquals(440_00, BalanceCalculator.computeBalance(account, listOf(refunded), asOf))
    }
}
