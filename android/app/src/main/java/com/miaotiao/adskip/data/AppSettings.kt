package com.miaotiao.adskip.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.miaotiao.adskip.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val autoSkip: Boolean = true,
    val interceptJump: Boolean = true,
    val vibrate: Boolean = true,
    val autoRestore: Boolean = true,
    val hongguoAutoSwipe: Boolean = true,
    val shakeGuard: Boolean = BuildConfig.SHAKE_GUARD,
    val excluded: Set<String> = emptySet()
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val autoSkip = booleanPreferencesKey("auto_skip")
        val interceptJump = booleanPreferencesKey("intercept_jump")
        val vibrate = booleanPreferencesKey("vibrate")
        val autoRestore = booleanPreferencesKey("auto_restore")
        val hongguoAutoSwipe = booleanPreferencesKey("hongguo_auto_swipe")
        val shakeGuard = booleanPreferencesKey("shake_guard")
        val excluded = stringSetPreferencesKey("excluded_packages")
    }

    val flow: Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            autoSkip = p[Keys.autoSkip] ?: true,
            interceptJump = p[Keys.interceptJump] ?: true,
            vibrate = p[Keys.vibrate] ?: true,
            autoRestore = p[Keys.autoRestore] ?: true,
            hongguoAutoSwipe = p[Keys.hongguoAutoSwipe] ?: true,
            shakeGuard = p[Keys.shakeGuard] ?: BuildConfig.SHAKE_GUARD,
            excluded = p[Keys.excluded] ?: emptySet()
        )
    }

    suspend fun setAutoSkip(value: Boolean) = context.settingsStore.edit { it[Keys.autoSkip] = value }
    suspend fun setInterceptJump(value: Boolean) = context.settingsStore.edit { it[Keys.interceptJump] = value }
    suspend fun setVibrate(value: Boolean) = context.settingsStore.edit { it[Keys.vibrate] = value }
    suspend fun setAutoRestore(value: Boolean) = context.settingsStore.edit { it[Keys.autoRestore] = value }
    suspend fun setHongguoAutoSwipe(value: Boolean) = context.settingsStore.edit { it[Keys.hongguoAutoSwipe] = value }
    suspend fun setShakeGuard(value: Boolean) = context.settingsStore.edit { it[Keys.shakeGuard] = value }
    suspend fun setExcluded(value: Set<String>) = context.settingsStore.edit { it[Keys.excluded] = value }
}
