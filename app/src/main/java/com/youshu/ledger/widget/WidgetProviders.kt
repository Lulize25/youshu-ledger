package com.youshu.ledger.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.youshu.ledger.MainActivity
import com.youshu.ledger.R
import com.youshu.ledger.data.local.db.LedgerDatabase
import com.youshu.ledger.data.local.entity.TxnType
import com.youshu.ledger.data.settings.SettingsStore
import com.youshu.ledger.domain.util.currentYearMonth
import com.youshu.ledger.domain.util.toEpochMillis
import com.youshu.ledger.domain.util.toYuan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/** 打开「记一笔」页面的 PendingIntent（两个小组件共用） */
fun quickAddPendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
        .setAction(MainActivity.ACTION_QUICK_ADD)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(
        context, 1, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * 4×2 小组件：本月支出、预算进度（若有预算）、一键记一笔按钮。
 * 数据与 APP 内实时一致：App 内任何数据变更都会 bump 数据版本号，
 * AppContainer 监听版本号后调用 [LedgerWidgetProvider.updateAll] 刷新组件。
 */
class LedgerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> manager.updateAppWidget(id, buildViews(context)) }
    }

    companion object {
        fun buildViews(context: Context): RemoteViews = RemoteViews(
            context.packageName,
            R.layout.widget_ledger,
        ).apply {
            val data = runBlocking(kotlinx.coroutines.Dispatchers.IO) { loadMonthData(context) }
            setTextViewText(R.id.widget_month_label, "${data.monthLabel}支出")
            setTextViewText(R.id.widget_month_amount, "¥ ${data.expense.toYuan()}")
            if (data.budget != null) {
                val ratio = (data.expense.toFloat() / data.budget).coerceIn(0f, 1f)
                setViewVisibility(R.id.widget_budget_bar, View.VISIBLE)
                setViewVisibility(R.id.widget_budget_text, View.VISIBLE)
                setProgressBar(R.id.widget_budget_bar, 100, (ratio * 100).toInt(), false)
                setTextViewText(
                    R.id.widget_budget_text,
                    "预算 ¥ ${data.budget.toYuan()} · 已用 ${(data.expense.toFloat() / data.budget * 100).toInt()}%" +
                        if (data.expense > data.budget) "（超支）" else "",
                )
            } else {
                setViewVisibility(R.id.widget_budget_bar, View.GONE)
                setViewVisibility(R.id.widget_budget_text, View.GONE)
            }
            setOnClickPendingIntent(R.id.widget_btn_add, quickAddPendingIntent(context))
            // 点击金额区域打开 APP
            setOnClickPendingIntent(
                R.id.widget_month_amount,
                PendingIntent.getActivity(
                    context, 2,
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }

        private data class MonthData(val monthLabel: String, val expense: Long, val budget: Long?)

        private suspend fun loadMonthData(context: Context): MonthData {
            val db = LedgerDatabase.getInstance(context)
            val settings = SettingsStore(context)
            val books = db.bookDao().getAll()
            val bookId = settings.currentBookId.first()
                ?: books.firstOrNull { it.isDefault }?.id ?: books.firstOrNull()?.id
                ?: return MonthData("本月", 0, null)
            val now = LocalDate.now()
            val monthStart = now.withDayOfMonth(1).toEpochMillis()
            val monthEnd = now.withDayOfMonth(now.lengthOfMonth()).toEpochMillis()
            val expense = db.transactionDao().sumByType(bookId, TxnType.EXPENSE, monthStart, monthEnd)
            val budget = db.budgetDao().getTotalBudget(bookId, currentYearMonth())?.amount
            return MonthData("${now.monthValue}月", expense, budget)
        }

        /** 刷新全部 4×2 小组件（数据版本号变化时由 AppContainer 调用） */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, LedgerWidgetProvider::class.java),
            )
            if (ids.isEmpty()) return
            ids.forEach { id -> manager.updateAppWidget(id, buildViews(context)) }
        }
    }
}

/** 2×2 小组件：单击一键记一笔直达记一笔页面 */
class QuickAddWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_add).apply {
            setOnClickPendingIntent(R.id.widget_quick_add_label, quickAddPendingIntent(context))
            setOnClickPendingIntent(R.id.widget_quick_add_text, quickAddPendingIntent(context))
        }
        ids.forEach { id -> manager.updateAppWidget(id, views) }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, QuickAddWidgetProvider::class.java),
            )
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_quick_add).apply {
                setOnClickPendingIntent(R.id.widget_quick_add_label, quickAddPendingIntent(context))
                setOnClickPendingIntent(R.id.widget_quick_add_text, quickAddPendingIntent(context))
            }
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }
    }
}
