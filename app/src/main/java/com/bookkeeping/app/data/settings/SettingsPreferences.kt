package com.bookkeeping.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

// 顶层属性：app 全局只有一份 settings DataStore
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings_prefs")

// 本地设置项；外观和通知辅助记账开关不影响账本数据。
@Singleton
class SettingsPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun observeAutoCaptureEnabled(): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_AUTO_CAPTURE] ?: false }

    fun observeThemeMode(): Flow<ThemeMode> = context.settingsDataStore.data.map { preferences ->
        runCatching { ThemeMode.valueOf(preferences[KEY_THEME_MODE] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setAutoCaptureEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTO_CAPTURE] = enabled }
    }

    fun observeScreenProtection() = context.settingsDataStore.data.map { it[KEY_SCREEN_PROTECTION] ?: false }
    fun observeAppLock() = context.settingsDataStore.data.map { it[KEY_APP_LOCK] ?: false }
    fun observeLastBackup() = context.settingsDataStore.data.map { it[KEY_LAST_BACKUP]?.toLongOrNull() }
    suspend fun setScreenProtection(value: Boolean) { context.settingsDataStore.edit { it[KEY_SCREEN_PROTECTION] = value } }
    suspend fun setAppLock(value: Boolean) { context.settingsDataStore.edit { it[KEY_APP_LOCK] = value } }
    suspend fun recordBackup() { context.settingsDataStore.edit { it[KEY_LAST_BACKUP] = System.currentTimeMillis().toString() } }

    fun observeRecentCategory() = context.settingsDataStore.data.map { it[KEY_RECENT_CATEGORY] ?: "FOOD" }
    fun observeDefaultAccount() = context.settingsDataStore.data.map { preferences ->
        preferences[KEY_DEFAULT_ACCOUNT_ID]?.toLongOrNull()?.let { id ->
            Triple(id, preferences[KEY_DEFAULT_ACCOUNT_NAME].orEmpty(), preferences[KEY_DEFAULT_ACCOUNT_KIND].orEmpty())
        }
    }
    suspend fun setRecentCategory(value: String) { context.settingsDataStore.edit { it[KEY_RECENT_CATEGORY] = value } }
    suspend fun setDefaultAccount(account: com.bookkeeping.app.data.local.entity.AccountEntity?) {
        context.settingsDataStore.edit {
            if (account == null) { it.remove(KEY_DEFAULT_ACCOUNT_ID); it.remove(KEY_DEFAULT_ACCOUNT_NAME); it.remove(KEY_DEFAULT_ACCOUNT_KIND) }
            else { it[KEY_DEFAULT_ACCOUNT_ID] = account.id.toString(); it[KEY_DEFAULT_ACCOUNT_NAME] = account.name; it[KEY_DEFAULT_ACCOUNT_KIND] = account.kind }
        }
    }
    companion object {
        private val KEY_RECENT_CATEGORY = stringPreferencesKey("recent_category")
        private val KEY_DEFAULT_ACCOUNT_ID = stringPreferencesKey("default_account_id")
        private val KEY_DEFAULT_ACCOUNT_NAME = stringPreferencesKey("default_account_name")
        private val KEY_DEFAULT_ACCOUNT_KIND = stringPreferencesKey("default_account_kind")
        private val KEY_SCREEN_PROTECTION = booleanPreferencesKey("screen_protection")
        private val KEY_APP_LOCK = booleanPreferencesKey("app_lock")
        private val KEY_LAST_BACKUP = stringPreferencesKey("last_verified_backup")
        private val KEY_AUTO_CAPTURE = booleanPreferencesKey("auto_capture_enabled")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }
