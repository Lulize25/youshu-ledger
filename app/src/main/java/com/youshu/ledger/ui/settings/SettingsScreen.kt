package com.youshu.ledger.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.youshu.ledger.data.AppContainer
import com.youshu.ledger.ui.common.LedgerTopBar
import kotlinx.coroutines.launch

/**
 * 设置页：账本管理、分类管理、数据备份与恢复（第七部分）、生物识别（第九部分）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBooks: () -> Unit,
    onCategories: () -> Unit,
    onBackup: () -> Unit,
) {
    val biometric by container.settingsStore.biometricEnabled.collectAsState(initial = true)
    val scope = rememberCoroutineScope()

    Scaffold(topBar = { LedgerTopBar(title = "设置") }) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            SettingItem(title = "账本管理", subtitle = "新建/切换/默认账本，分类按账本隔离") { onBooks() }
            HorizontalDivider()
            SettingItem(title = "分类管理", subtitle = "树状分类、图标编辑、跨账本导入") { onCategories() }
            HorizontalDivider()
            SettingItem(title = "数据备份与恢复", subtitle = "JSON 全量备份、CSV/ZIP 导出、CSV 导入") { onBackup() }
            HorizontalDivider()

            // 生物识别开关（第九部分接入解锁页，开关先可用）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("生物识别解锁", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "启动及从后台返回时需指纹/面部验证（默认开启）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Switch(
                    checked = biometric,
                    onCheckedChange = { enabled ->
                        scope.launch { container.settingsStore.setBiometricEnabled(enabled) }
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingItem(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
