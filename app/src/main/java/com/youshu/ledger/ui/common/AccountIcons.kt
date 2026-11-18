package com.youshu.ledger.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 预置账户图标：彩色圆角方块 + 单字。key 持久化在 AccountEntity.iconKey。 */
data class AccountIconSpec(
    val key: String,
    val label: String,
    val char: String,
    val color: Long, // ARGB
)

object AccountIcons {
    val all: List<AccountIconSpec> = listOf(
        AccountIconSpec("wechat", "微信", "微", 0xFF07C160),
        AccountIconSpec("alipay", "支付宝", "支", 0xFF1677FF),
        AccountIconSpec("cash", "现金", "现", 0xFF2E7D32),
        AccountIconSpec("bank", "银行卡", "银", 0xFF1565C0),
        AccountIconSpec("credit", "信用卡", "信", 0xFF6A1B9A),
        AccountIconSpec("wallet", "钱包", "包", 0xFF4E342E),
        AccountIconSpec("transit", "公交卡", "交", 0xFFEF6C00),
        AccountIconSpec("campus", "校园卡", "校", 0xFF00838F),
        AccountIconSpec("meal", "饭卡", "饭", 0xFFAD1457),
        AccountIconSpec("other", "其他", "其", 0xFF757575),
    )

    val defaultKey: String = "cash"

    fun of(key: String?): AccountIconSpec =
        all.firstOrNull { it.key == key } ?: all.first { it.key == defaultKey }

    /** 旧版账户类型（v1 及更早备份）→ 图标 key 映射 */
    fun fromLegacyType(type: String?): String = when (type) {
        "WECHAT" -> "wechat"
        "ALIPAY" -> "alipay"
        "BANK_CARD" -> "bank"
        "CREDIT_CARD" -> "credit"
        "CASH" -> "cash"
        else -> "other"
    }
}

/** 账户图标：圆角色块 + 白色单字，尺寸可调 */
@Composable
fun AccountIcon(iconKey: String?, size: Dp = 40.dp) {
    val spec = AccountIcons.of(iconKey)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(Color(spec.color)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            spec.char,
            color = Color.White,
            fontSize = (size.value * 0.42).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
