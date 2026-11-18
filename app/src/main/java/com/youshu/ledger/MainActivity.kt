package com.youshu.ledger

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.youshu.ledger.data.AppContainer
import com.youshu.ledger.ui.accounts.AccountsScreen
import com.youshu.ledger.ui.navigation.Routes
import com.youshu.ledger.ui.record.RecordScreen
import com.youshu.ledger.ui.stats.StatsScreen
import com.youshu.ledger.ui.theme.YoushuTheme
import com.youshu.ledger.ui.txns.TxnsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private lateinit var container: AppContainer

    companion object {
        /** 小组件「一键记一笔」跳转动作 */
        const val ACTION_QUICK_ADD = "com.youshu.ledger.action.QUICK_ADD"
    }

    private val quickAddSignal = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** 解锁状态：null=校验中，false=已锁定，true=已解锁 */
    private val unlocked = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
    private var promptShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as LedgerApp).container
        handleIntent(intent)
        // 初次启动：根据开关与设备能力决定是否需要验证
        lifecycleScope.launch {
            val enabled = container.settingsStore.biometricEnabled.first()
            if (!enabled || !com.youshu.ledger.ui.security.AppLocker.canAuthenticate(this@MainActivity)) {
                unlocked.value = true
            } else {
                unlocked.value = false
                promptUnlock()
            }
        }
        setContent {
            YoushuTheme {
                val isUnlocked by unlocked.collectAsState()
                when (isUnlocked) {
                    true -> LedgerNavHost(container, quickAddSignal)
                    false -> com.youshu.ledger.ui.security.LockScreen(
                        onUnlockClick = { promptUnlock() },
                        onExit = { finish() },
                    )
                    null -> Unit // 校验中，显示空白
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 启动及每次从后台返回时：若开启生物识别且设备可用，则要求重新验证
        if (unlocked.value == true) {
            lifecycleScope.launch {
                val enabled = container.settingsStore.biometricEnabled.first()
                if (enabled && com.youshu.ledger.ui.security.AppLocker.canAuthenticate(this@MainActivity)) {
                    unlocked.value = false
                    promptUnlock()
                }
            }
        }
    }

    private fun promptUnlock() {
        if (promptShowing) return
        promptShowing = true
        com.youshu.ledger.ui.security.AppLocker.requestUnlock(
            activity = this,
            onUnlocked = {
                promptShowing = false
                unlocked.value = true
            },
            onFailed = {
                promptShowing = false
                finish()
            },
        )
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.action == ACTION_QUICK_ADD) {
            quickAddSignal.value = true
        }
    }
}

private data class BottomItem(val route: String, val label: String, val icon: ImageVector)

private val bottomItems = listOf(
    BottomItem(Routes.TXNS, "流水", Icons.AutoMirrored.Filled.List),
    BottomItem(Routes.ACCOUNTS, "账户", Icons.Default.Wallet),
    BottomItem(Routes.STATS, "统计", Icons.Default.BarChart),
    BottomItem(Routes.SETTINGS, "设置", Icons.Default.Settings),
)

@Composable
fun LedgerNavHost(
    container: AppContainer,
    quickAddSignal: kotlinx.coroutines.flow.MutableStateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
) {
    val nav: NavHostController = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // 小组件「一键记一笔」：收到信号后直达记一笔页面
    val quickAdd by quickAddSignal.collectAsState()
    androidx.compose.runtime.LaunchedEffect(quickAdd) {
        if (quickAdd) {
            quickAddSignal.value = false
            nav.navigate(Routes.RECORD)
        }
    }

    // 只有主页面显示底部导航与记一笔 FAB
    val showBottomBar = currentRoute in bottomItems.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                nav.navigate(item.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (showBottomBar) {
                FloatingActionButton(onClick = { nav.navigate(Routes.RECORD) }) {
                    Icon(Icons.Default.Add, contentDescription = "记一笔")
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.TXNS,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.TXNS) {
                TxnsScreen(container, onAdd = { nav.navigate(Routes.RECORD) }, onEdit = { nav.navigate(Routes.recordEdit(it)) })
            }
            composable(Routes.RECORD_EDIT) { entry ->
                RecordScreen(
                    container = container,
                    txnId = entry.arguments?.getString("txnId")?.toLongOrNull(),
                    onDone = { nav.popBackStack() },
                )
            }
            composable(Routes.ACCOUNTS) {
                AccountsScreen(container, onOpen = { nav.navigate(Routes.accountDetail(it)) })
            }
            composable(Routes.ACCOUNT_DETAIL) { entry ->
                val id = entry.arguments?.getString("accountId")?.toLongOrNull() ?: 0L
                com.youshu.ledger.ui.accounts.AccountDetailScreen(
                    container = container,
                    accountId = id,
                    onEdit = { nav.navigate(Routes.recordEdit(it)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.STATS) { StatsScreen(container) }
            composable(Routes.SETTINGS) {
                com.youshu.ledger.ui.settings.SettingsScreen(
                    container = container,
                    onBooks = { nav.navigate(Routes.BOOKS) },
                    onCategories = { nav.navigate(Routes.CATEGORIES) },
                    onBackup = { nav.navigate(Routes.BACKUP) },
                )
            }
            composable(Routes.BOOKS) {
                com.youshu.ledger.ui.books.BooksScreen(container, onBack = { nav.popBackStack() })
            }
            composable(Routes.CATEGORIES) {
                com.youshu.ledger.ui.categories.CategoriesScreen(container, onBack = { nav.popBackStack() })
            }
            composable(Routes.BACKUP) {
                com.youshu.ledger.ui.backup.BackupScreen(
                    container = container,
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
