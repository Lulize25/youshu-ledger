package com.youshu.ledger.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// DataStore 文件名为 settings
private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * 应用设置存储（DataStore Preferences）。
 * 保存：生物识别开关、当前选中账本、小组件刷新信号等。
 */
class SettingsStore(private val context: Context) {

    private val keyBiometric = booleanPreferencesKey("biometric_enabled")
    private val keyCurrentBook = longPreferencesKey("current_book_id")
    private val keyDataVersion = longPreferencesKey("data_version")

    /** 生物识别开关（默认开启） */
    val biometricEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[keyBiometric] ?: true }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { it[keyBiometric] = enabled }
    }

    /** 当前选中账本（null 表示跟随默认账本） */
    val currentBookId: Flow<Long?> =
        context.dataStore.data.map { it[keyCurrentBook] }

    suspend fun setCurrentBookId(id: Long) {
        context.dataStore.edit { it[keyCurrentBook] = id }
    }

    /**
     * 数据版本号：任何账本数据变更（记账/编辑/删除/恢复）都会 +1，
     * 小组件监听该值刷新，保证桌面组件与 APP 内实时一致。
     */
    val dataVersion: Flow<Long> =
        context.dataStore.data.map { it[keyDataVersion] ?: 0L }

    suspend fun bumpDataVersion() {
        context.dataStore.edit { it[keyDataVersion] = (it[keyDataVersion] ?: 0L) + 1 }
    }

    suspend fun currentDataVersion(): Long = dataVersion.first()
}
