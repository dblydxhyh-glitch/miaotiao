package com.miaotiao.adskip.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * 无障碍服务被系统杀死/静默停用后，利用 adb 授予的 WRITE_SECURE_SETTINGS
 * 重新写入 enabled_accessibility_services，强制系统重新绑定。
 */
object AccessibilityRestorer {

    fun component(context: Context): String =
        "${context.packageName}/${SkipAccessibilityService::class.java.name}"

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isListed(context: Context): Boolean {
        val comp = component(context)
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return current.split(':').any { it == comp }
    }

    /** 先移除再写回，触发系统重新绑定；返回是否已触发。 */
    fun forceRebind(context: Context): Boolean {
        if (!hasPermission(context)) return false
        val comp = component(context)
        return try {
            val resolver = context.contentResolver
            val current = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val without = current.split(':')
                .filter { it.isNotBlank() && it != comp }
                .joinToString(":")
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                without
            )
            Settings.Secure.putInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                if (without.isBlank()) 0 else 1
            )
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val now = Settings.Secure.getString(
                        resolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    ).orEmpty()
                    val with = if (now.isBlank()) comp else "$now:$comp"
                    Settings.Secure.putString(
                        resolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        with
                    )
                    Settings.Secure.putInt(
                        resolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        1
                    )
                } catch (_: Throwable) {
                }
            }, 500)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
