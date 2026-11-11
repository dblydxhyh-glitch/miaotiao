package com.miaotiao.adskip.util

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * MyOS 特殊权限跳转。
 *
 * 实测：
 * - 系统设置可以用 `com.android.settings.MF_SPECIAL_ACCESS_SETTINGS` 直达
 *   「读取加速度传感器」页；
 * - 但普通第三方 App 启动该页面会被系统拦截（未导出/受保护），
 *   只能退回到设置主页，由用户手动导航。
 */
object MyOsPermissions {

    private const val MYOS_ACCEL_ACTION = "com.android.settings.MF_SPECIAL_ACCESS_SETTINGS"
    private const val HEARTY_ACTION =
        "com.zte.heartyservice.intent.action.startActivity.PERMISSION_FOR_ALL_APPS"

    enum class OpenResult { DIRECT, SETTINGS, FAILED }

    fun openAccelerometerPermission(context: Context): OpenResult {
        for (action in listOf(MYOS_ACCEL_ACTION, HEARTY_ACTION)) {
            try {
                context.startActivity(
                    Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return OpenResult.DIRECT
            } catch (_: Throwable) {
                // 第三方无权启动，继续尝试
            }
        }
        return try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            OpenResult.SETTINGS
        } catch (_: Throwable) {
            OpenResult.FAILED
        }
    }
}
