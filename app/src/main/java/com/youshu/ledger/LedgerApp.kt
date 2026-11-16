package com.youshu.ledger

import android.app.Application
import com.youshu.ledger.data.AppContainer

/**
 * Application 入口：构建应用级依赖容器（数据库、DAO、Repository、设置存储）。
 * 使用手工依赖注入（ServiceLocator 风格），保持单模块工程简洁，不引入 Hilt。
 */
class LedgerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
