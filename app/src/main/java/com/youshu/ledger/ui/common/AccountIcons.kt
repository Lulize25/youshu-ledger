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

/*
 * 账户图标（v1.4.0 起）：用户自定义 1~2 个文字，渲染为彩色圆角方块 + 白色文字。
 * AccountEntity.iconKey 字段直接保存图标文字（不再使用预置 key）。
 * 颜色按文字内容稳定散列，同一账户每次显示一致。
 */

/** 图标底色候选（白字均清晰可读） */
private val PALETTE: List<Long> = listOf(
    0xFF07C160, // 绿
    0xFF1677FF, // 蓝
    0xFF2E7D32, // 深绿
    0xFF1565C0, // 深蓝
    0xFF6A1B9A, // 紫
    0xFFEF6C00, // 橙
    0xFF00838F, // 青
    0xFFAD1457, // 玫红
    0xFF4E342E, // 棕
    0xFF546E7A, // 蓝灰
)

/** 文字 → 稳定底色（同一文字每次得到同一种颜色） */
fun accountIconColor(text: String): Long =
    PALETTE[(text.hashCode() and Int.MAX_VALUE) % PALETTE.size]

/** 图标文字规整：取前 1~2 个非空白字符；空白时退回「账」 */
fun normalizeIconText(text: String?): String {
    val t = text.orEmpty().trim().replace(Regex("\\s+"), "")
    if (t.isEmpty()) return "账"
    return t.take(2)
}

/** 旧版账户类型（v1 及更早备份）→ 图标文字映射 */
fun iconTextFromLegacyType(type: String?): String = when (type) {
    "WECHAT" -> "微信"
    "ALIPAY" -> "支付"
    "BANK_CARD" -> "银行"
    "CREDIT_CARD" -> "信用"
    "CASH" -> "现金"
    else -> "账"
}

/** v2~v4 预置图标 key → 图标文字；已是自定义文字则原样返回 */
fun iconTextFromLegacyKey(key: String): String = when (key) {
    "wechat" -> "微信"
    "alipay" -> "支付"
    "cash" -> "现金"
    "bank" -> "银行"
    "credit" -> "信用"
    "wallet" -> "钱包"
    "transit" -> "公交"
    "campus" -> "校园"
    "meal" -> "饭卡"
    "other" -> "其他"
    else -> key
}

/** 账户图标：圆角色块 + 白色文字（1~2 字），尺寸可调 */
@Composable
fun AccountIcon(iconText: String?, size: Dp = 40.dp) {
    val text = normalizeIconText(iconText)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(Color(accountIconColor(text))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = (size.value * if (text.length > 1) 0.32 else 0.42).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
