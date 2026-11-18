package com.youshu.ledger.ui.accounts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.youshu.ledger.data.AppContainer

/** 账户页（第五部分实现完整功能，此处为占位） */
@Composable
fun AccountsScreen(container: AppContainer, onOpen: (Long) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("账户页（第五部分实现）")
    }
}
