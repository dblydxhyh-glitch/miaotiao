package com.miaotiao.adskip.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.statsStore by preferencesDataStore(name = "stats")

data class UsageStats(
    val todaySkipped: Int = 0,
    val totalSkipped: Int = 0,
    val totalIntercepted: Int = 0,
    val totalAutoSwipes: Int = 0
)

class StatsRepository(private val context: Context) {

    private object Keys {
        val day = longPreferencesKey("day")
        val todaySkipped = intPreferencesKey("today_skipped")
        val totalSkipped = intPreferencesKey("total_skipped")
        val totalIntercepted = intPreferencesKey("total_intercepted")
        val totalAutoSwipes = intPreferencesKey("total_auto_swipes")
    }

    val flow: Flow<UsageStats> = context.statsStore.data.map { p ->
        val today = LocalDate.now().toEpochDay()
        UsageStats(
            todaySkipped = if (p[Keys.day] == today) (p[Keys.todaySkipped] ?: 0) else 0,
            totalSkipped = p[Keys.totalSkipped] ?: 0,
            totalIntercepted = p[Keys.totalIntercepted] ?: 0,
            totalAutoSwipes = p[Keys.totalAutoSwipes] ?: 0
        )
    }

    suspend fun onSkip() = context.statsStore.edit { p ->
        val today = LocalDate.now().toEpochDay()
        if (p[Keys.day] != today) {
            p[Keys.day] = today
            p[Keys.todaySkipped] = 0
        }
        p[Keys.todaySkipped] = (p[Keys.todaySkipped] ?: 0) + 1
        p[Keys.totalSkipped] = (p[Keys.totalSkipped] ?: 0) + 1
    }

    suspend fun onIntercept() = context.statsStore.edit { p ->
        p[Keys.totalIntercepted] = (p[Keys.totalIntercepted] ?: 0) + 1
    }

    suspend fun onAutoSwipe() = context.statsStore.edit { p ->
        p[Keys.totalAutoSwipes] = (p[Keys.totalAutoSwipes] ?: 0) + 1
    }
}
