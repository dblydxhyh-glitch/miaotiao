package com.miaotiao.adskip

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.miaotiao.adskip.data.RecordStore
import com.miaotiao.adskip.data.SettingsRepository
import com.miaotiao.adskip.data.StatsRepository
import com.miaotiao.adskip.service.KeepAliveService
import com.miaotiao.adskip.ui.HomeScreen
import com.miaotiao.adskip.ui.theme.MiaoTiaoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeepAliveService.start(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        val settingsRepo = SettingsRepository(applicationContext)
        val statsRepo = StatsRepository(applicationContext)
        val recordStore = RecordStore(applicationContext)
        setContent {
            MiaoTiaoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen(settingsRepo, statsRepo, recordStore)
                }
            }
        }
    }
}
