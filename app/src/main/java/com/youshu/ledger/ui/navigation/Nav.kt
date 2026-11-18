package com.youshu.ledger.ui.navigation

/** 应用内路由常量 */
object Routes {
    const val TXNS = "txns"
    const val RECORD = "record"
    const val RECORD_EDIT = "record?txnId={txnId}"
    const val ACCOUNTS = "accounts"
    const val ACCOUNT_DETAIL = "account/{accountId}"
    const val STATS = "stats"
    const val BOOKS = "books"
    const val CATEGORIES = "categories"
    const val SETTINGS = "settings"
    const val BACKUP = "backup"

    fun recordEdit(txnId: Long) = "record?txnId=$txnId"
    fun accountDetail(accountId: Long) = "account/$accountId"
}
