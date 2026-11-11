package com.miaotiao.adskip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.miaotiao.adskip.MainActivity
import com.miaotiao.adskip.R
import com.miaotiao.adskip.util.DebugLog

/**
 * 前台服务保活 + 看门狗：
 * 每 30 秒检查无障碍服务是否存活；若被系统杀掉，尝试用 WRITE_SECURE_SETTINGS
 * 重新绑定；没有权限时发通知提醒用户手动恢复。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "keep_alive"
        private const val ALERT_CHANNEL_ID = "alerts"
        private const val NOTIFICATION_ID = 1001
        private const val ALERT_ID = 2001

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, KeepAliveService::class.java)
                )
            } catch (_: Throwable) {
                // 后台启动受限时忽略，用户打开 App 时会再次启动
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastAlertAt = 0L

    private val watchdog = object : Runnable {
        override fun run() {
            try {
                val enabled = android.provider.Settings.Secure.getInt(
                    contentResolver,
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                ) == 1
                val listed = AccessibilityRestorer.isListed(this@KeepAliveService)
                DebugLog.log(
                    this@KeepAliveService,
                    "watchdog tick enabled=$enabled listed=$listed connected=${SkipAccessibilityService.connected}"
                )
                if (!enabled || !listed || !SkipAccessibilityService.connected) {
                    val rebind = AccessibilityRestorer.forceRebind(this@KeepAliveService)
                    DebugLog.log(
                        this@KeepAliveService,
                        "watchdog: restoring, rebind=$rebind"
                    )
                    if (!rebind && System.currentTimeMillis() - lastAlertAt > 300_000) {
                        lastAlertAt = System.currentTimeMillis()
                        notifyStopped()
                    }
                }
            } catch (_: Throwable) {
            }
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIFICATION_ID, buildNotification())
        handler.postDelayed(watchdog, 8_000)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "常驻运行", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    description = "保持秒跳的无障碍服务运行"
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL_ID, "状态提醒", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("秒跳")
            .setContentText("无障碍服务运行中，正在守护广告跳过")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun notifyStopped() {
        try {
            val contentIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("秒跳服务已停止")
                .setContentText("点击重新开启，否则无法自动跳过")
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()
            getSystemService(NotificationManager::class.java).notify(ALERT_ID, notification)
        } catch (_: Throwable) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
