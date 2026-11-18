package com.youshu.ledger.data.io

import com.youshu.ledger.data.local.entity.AttachmentEntity
import com.youshu.ledger.data.local.entity.CategoryEntity
import com.youshu.ledger.data.local.entity.CategoryType
import com.youshu.ledger.data.local.entity.ReimburseRecordEntity
import com.youshu.ledger.data.local.entity.RefundRecordEntity
import com.youshu.ledger.data.local.entity.TransactionEntity
import com.youshu.ledger.data.local.entity.TransactionWithDetails
import com.youshu.ledger.data.local.entity.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.ZoneId

/** CSV 引擎与备份路径重映射测试 */
class CsvEngineTest {

    private val day = LocalDate.of(2026, 11, 16).atStartOfDay(ZoneId.systemDefault())
        .toInstant().toEpochMilli()

    private fun txn() = TransactionWithDetails(
        transaction = TransactionEntity(
            id = 1, bookId = 1, type = TxnType.EXPENSE, amount = 100_00,
            businessDate = day, createdAt = day, note = "午餐",
            categoryId = 5, accountId = 2, toAccountId = null,
            advanceAmount = 30_00, recoveredAmount = 10_00,
        ),
        refunds = listOf(RefundRecordEntity(1, 1, 20_00, "", day)),
        reimbursements = listOf(ReimburseRecordEntity(1, 1, 10_00, "", day)),
        attachments = listOf(AttachmentEntity(1, 1, "attachments/1/a.jpg")),
    )

    @Test
    fun `export csv has BOM and round-trips through parse`() {
        val csv = CsvEngine.buildCsv(
            listOf(txn()),
            bookNameOf = { "日常账本" },
            accountNameOf = { if (it == 2L) "微信" else "其他" },
            categoryPathOf = { "餐饮/正餐" },
        )
        assertTrue(csv.startsWith("\uFEFF"))
        // 解析（跳过 BOM）
        val parsed = CsvEngine.parse(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))
        assertEquals(1, parsed.rows.size)
        val row = parsed.rows[0]
        assertEquals("日常账本", row.get("账本"))
        assertEquals("支出", row.get("类型"))
        assertEquals("100.00", row.get("金额"))
        assertEquals("餐饮/正餐", row.get("分类（完整路径）"))
        assertEquals("20.00", row.get("退款合计"))
        assertEquals("10.00", row.get("报销合计"))
        assertEquals("30.00", row.get("代付"))
        assertEquals("10.00", row.get("已收回"))
        assertEquals("1", row.get("附件数量"))
    }

    @Test
    fun `guess mapping finds required columns`() {
        val mapping = CsvEngine.guessMapping(CsvEngine.HEADERS)
        assertEquals(0, mapping[CsvEngine.Field.BOOK])
        assertEquals(1, mapping[CsvEngine.Field.TYPE])
        assertEquals(2, mapping[CsvEngine.Field.DATE])
        assertEquals(7, mapping[CsvEngine.Field.AMOUNT])
        assertEquals(5, mapping[CsvEngine.Field.ACCOUNT])
        assertEquals(4, mapping[CsvEngine.Field.CATEGORY])
    }

    @Test
    fun `attachment path remaps to private relative path`() {
        assertEquals(
            "attachments/12/x.jpg",
            remapAttachmentPath("/data/user/0/com.youshu.ledger/files/attachments/12/x.jpg"),
        )
        assertEquals(
            "attachments/3/y.png",
            remapAttachmentPath("attachments/3/y.png"),
        )
    }

    @Test
    fun `category icon text takes up to two chars`() {
        val c = CategoryEntity(
            id = 1, bookId = 1, type = CategoryType.EXPENSE, parentId = null,
            name = "餐饮", sortOrder = 0, color = 0, iconText = "餐饮",
        )
        assertEquals("餐饮", c.iconText.take(2))
    }
}
