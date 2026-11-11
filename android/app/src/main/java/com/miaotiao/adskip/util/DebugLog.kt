package com.miaotiao.adskip.util

import android.content.Context
import java.io.File

/** 调试用：把关键信息写到应用私有目录，adb run-as 直接读。 */
object DebugLog {
    private const val MAX_BYTES = 400_000L

    fun log(context: Context, message: String) {
        try {
            val file = File(context.filesDir, "debug.log")
            if (file.length() > MAX_BYTES) file.delete()
            file.appendText("${System.currentTimeMillis()} $message\n")
        } catch (_: Throwable) {
        }
    }
}
