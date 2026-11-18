package com.youshu.ledger.domain.balance

import com.youshu.ledger.data.local.entity.AccountEntity
import com.youshu.ledger.data.local.entity.TransactionEntity
import com.youshu.ledger.data.local.entity.TransactionWithDetails
import com.youshu.ledger.data.local.entity.TxnType
import com.youshu.ledger.data.local.entity.effectiveEnableMillis
import com.youshu.ledger.data.local.entity.effectiveTimeMillis

/**
 * 余额与补录核心规则（纯函数，可单元测试）。
 *
 * 【余额计算】
 * 账户当前余额 = 期初余额
 *   + Σ（生效时刻 ≥ 启用时刻 的流水影响）           —— 补录流水不参与（精确到分钟）
 *   + Σ（已记录的退款 + 报销 + 收回代付）           —— 发生即计入余额，与是否补录无关
 *
 * 流水影响：
 * - 支出：账户 −金额（全额扣减，含代付部分，资金实际流出）
 * - 收入：账户 +金额
 * - 转账：转出 −金额（−手续费），转入 +金额
 *
 * 【补录规则】
 * 补录 = 生效时刻（业务日期+时间，精确到分钟）早于账户启用时刻的流水：
 * 允许录入、计入历史统计，但绝不改变余额；
 * 未记录时间的流水按当天 23:59、未设置启用时间的账户按当天 00:00 处理（旧数据行为不变）。
 * 由于余额为「派生计算」而非增量记账，修改 / 删除任何流水天然正确回滚余额。
 *
 * 【统计口径】（退款/报销/收回在统计上归属原支出的业务日期）
 * - 净支出 = 金额 − 退款合计 − 报销合计
 * - 个人实际支出 = 金额 − 代付金额 − 退款合计 − 报销合计
 * - 应收（别人欠我）= 代付金额 − 已收回金额
 */
object BalanceCalculator {

    /**
     * 单笔流水对指定账户的余额影响（分）。
     * 与流水无关的账户返回 0；调用方需自行判断业务日期是否 ≥ 启用日期（补录过滤）。
     */
    fun txnImpact(txn: TransactionEntity, accountId: Long): Long = when (txn.type) {
        TxnType.EXPENSE ->
            if (txn.accountId == accountId) -txn.amount else 0L

        TxnType.INCOME ->
            if (txn.accountId == accountId) txn.amount else 0L

        TxnType.TRANSFER -> when (accountId) {
            // 转出账户：扣金额与手续费
            txn.accountId -> -(txn.amount + txn.fee)
            // 转入账户：加金额（手续费不从转入方扣）
            txn.toAccountId -> txn.amount
            else -> 0L
        }
    }

    /**
     * 计算账户余额（核心入口）。
     * @param details 该账户涉及的全部流水（含补录）及其退款/报销/附件子表
     * @param asOfMillis 截至时刻（epoch 毫秒）：只统计生效时刻 ≤ asOf 的流水与记录；
     *                   默认 [Long.MAX_VALUE] 即「当前余额」。
     */
    fun computeBalance(
        account: AccountEntity,
        details: List<TransactionWithDetails>,
        asOfMillis: Long = Long.MAX_VALUE,
    ): Long {
        var balance = account.initialBalance
        for (item in details) {
            val t = item.transaction
            val involves =
                t.accountId == account.id || t.toAccountId == account.id
            if (!involves) continue

            // 1) 退款 / 报销到账：发生即计入余额（不记为收入流水），含补录流水上的记录；
            //    二者有独立发生时间（createdAt），仅统计截至 asOf 的部分
            balance += item.refunds.filter { it.createdAt <= asOfMillis }.sumOf { it.amount }
            balance += item.reimbursements.filter { it.createdAt <= asOfMillis }.sumOf { it.amount }
            // 收回代付无独立时间戳，随流水生效时刻一并截止；只发生在支出账户（资金原路回到代付方）
            if (t.accountId == account.id && t.effectiveTimeMillis() <= asOfMillis) {
                balance += t.recoveredAmount
            }

            // 2) 流水影响：仅启用时刻 ≤ 生效时刻 ≤ asOf 才计入 —— 补录流水绝不改变余额
            val effective = t.effectiveTimeMillis()
            if (effective >= account.effectiveEnableMillis() && effective <= asOfMillis) {
                balance += txnImpact(t, account.id)
            }
        }
        return balance
    }

    /** 净支出 = 金额 − 退款合计 − 报销合计 */
    fun netExpense(t: TransactionEntity, refundSum: Long, reimburseSum: Long): Long =
        t.amount - refundSum - reimburseSum

    /** 个人实际支出 = 金额 − 代付金额 − 退款合计 − 报销合计 */
    fun personalExpense(t: TransactionEntity, refundSum: Long, reimburseSum: Long): Long =
        netExpense(t, refundSum, reimburseSum) - t.advanceAmount

    /** 应收（别人欠我）= 代付金额 − 已收回金额 */
    fun receivableOf(t: TransactionEntity): Long = t.advanceAmount - t.recoveredAmount
}
