package com.miaotiao.adskip.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.miaotiao.adskip.util.DebugLog

/**
 * 无障碍自动操作 MyOS 加速度权限：
 * 设置内搜索「读取加速度传感器」→ 打开应用列表 → 目标 App → 仅开屏时禁止。
 *
 * 只做 UI 点击/输入，不写系统设置；失败会停下并记录日志。
 */
object AutoToggle {

    @Volatile
    var active: Boolean = false
        private set

    private var targetPkg = ""
    private var targetLabel = ""
    private var mode = "仅开屏时禁止"
    private var startedAt = 0L
    private var lastTick = 0L
    private var scrolls = 0
    private var queryTyped = false
    private var settleUntil = 0L
    private var searchAttempts = 0
    private var imeHidden = false

    fun start(context: Context, pkg: String, label: String, mode: String = "仅开屏时禁止") {
        targetPkg = pkg
        targetLabel = label
        this.mode = mode
        active = true
        startedAt = System.currentTimeMillis()
        lastTick = 0L
        scrolls = 0
        queryTyped = false
        settleUntil = System.currentTimeMillis() + 1500
        searchAttempts = 0
        imeHidden = false
        DebugLog.log(context, "AUTO start $label ($pkg) mode=$mode")
        try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            DebugLog.log(context, "AUTO settings launch failed: ${t.message}")
        }
    }

    fun stop(context: Context, reason: String) {
        if (!active) return
        active = false
        scrolls = 0
        DebugLog.log(context, "AUTO stop: $reason")
    }

    fun tick(service: AccessibilityService, root: AccessibilityNodeInfo, texts: List<String>) {
        if (!active) return
        val now = System.currentTimeMillis()
        if (now - startedAt > 90_000) {
            stop(service, "timeout")
            return
        }
        if (now - lastTick < 700) return
        lastTick = now
        if (now < settleUntil) return

        val pkg = root.packageName?.toString().orEmpty()

        when {
            // 特殊应用权限列表：点「读取加速度传感器」进入应用列表
            pkg == "com.android.settings" &&
                texts.any { it == "读取加速度传感器" } &&
                texts.none { it == "允许" } -> {
                clickText(service, root, "读取加速度传感器")
            }

            // 加速度权限应用列表：点中目标 App 后立即停止，页面交给用户
            texts.any { it == "允许" } -> {
                if (clickText(service, root, targetLabel)) {
                    DebugLog.log(service, "AUTO clicked $targetLabel")
                    stop(service, "clicked app")
                } else {
                    scroll(service, root)
                }
            }

            // 设置搜索页：输入关键词
            pkg == "com.android.settings.intelligence" && !queryTyped -> {
                setQuery(service, root)
            }

            // 设置搜索页：先收起输入法，再点结果
            pkg == "com.android.settings.intelligence" && queryTyped -> {
                if (!imeHidden) {
                    val imeVisible = try {
                        service.windows.any {
                            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                        }
                    } catch (_: Throwable) {
                        false
                    }
                    if (imeVisible) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        DebugLog.log(service, "AUTO hide IME")
                        imeHidden = true
                        return
                    }
                    imeHidden = true
                }
                if (!clickResult(service, root, "读取加速度传感器")) {
                    scroll(service, root)
                }
            }

            // 设置首页：点搜索框左侧放大镜
            pkg == "com.android.settings" && findEditable(root) != null -> {
                val editable = findEditable(root)!!
                when (searchAttempts) {
                    0 -> tapNode(service, editable, 24f)
                    1 -> {
                        val parent = editable.parent
                        (parent ?: editable).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    else -> clickNode(editable)
                }
                searchAttempts++
                DebugLog.log(service, "AUTO search attempt=$searchAttempts")
            }

            else -> scroll(service, root)
        }
    }

    private fun tapNode(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        xFromLeft: Float = -1f
    ) {
        try {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val x = if (xFromLeft >= 0) rect.left + xFromLeft else rect.centerX().toFloat()
            val y = rect.centerY().toFloat()
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 1f, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
                .build()
            service.dispatchGesture(gesture, null, null)
            DebugLog.log(service, "AUTO tap at $x,$y")
        } catch (_: Throwable) {
        }
    }

    private fun setQuery(service: AccessibilityService, root: AccessibilityNodeInfo) {
        val editable = findEditable(root) ?: return
        fun args(value: String) = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value
            )
        }
        // 先清空再写入，强制触发一次搜索
        editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args(""))
        var ok = editable.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            args("读取加速度传感器")
        )
        if (!ok) {
            editable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args(""))
            ok = editable.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                args("读取加速度传感器")
            )
        }
        queryTyped = ok
        DebugLog.log(service, "AUTO query typed=$ok")
    }

    private fun findText(
        node: AccessibilityNodeInfo?,
        text: String,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) {
            return node
        }
        for (i in 0 until node.childCount) {
            findText(node.getChild(i), text, depth + 1)?.let { return it }
        }
        return null
    }

    private fun findResult(
        node: AccessibilityNodeInfo?,
        text: String,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        val isEditable = node.isEditable ||
            node.className?.toString()?.contains("EditText") == true
        if (!isEditable &&
            (node.text?.toString() == text || node.contentDescription?.toString() == text)
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            findResult(node.getChild(i), text, depth + 1)?.let { return it }
        }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 30) return null
        val cls = node.className?.toString().orEmpty()
        if (node.isEditable || cls.contains("EditText") || cls.contains("AutoCompleteTextView")) {
            return node
        }
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }

    private fun clickText(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
        text: String
    ): Boolean {
        val node = findText(root, text) ?: return false
        val ok = clickNode(node)
        if (!ok) {
            // 控件不可点或点击无效时，用坐标点击兜底
            tapNode(service, node)
        }
        return true
    }

    private fun clickResult(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
        text: String
    ): Boolean {
        val node = findResult(root, text) ?: return false
        return clickNode(node)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var clickable: AccessibilityNodeInfo? = node
        var guard = 0
        while (clickable != null && !clickable.isClickable && guard < 6) {
            clickable = clickable.parent
            guard++
        }
        return (clickable ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun scroll(service: AccessibilityService, root: AccessibilityNodeInfo) {
        if (scrolls >= 12) {
            stop(service, "scroll limit")
            return
        }
        scrolls++
        try {
            val metrics = service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            val w = metrics.width().toFloat()
            val h = metrics.height().toFloat()
            val path = Path().apply {
                moveTo(w * 0.5f, h * 0.75f)
                lineTo(w * 0.5f, h * 0.30f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 400L))
                .build()
            service.dispatchGesture(gesture, null, null)
        } catch (_: Throwable) {
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 30) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            findScrollable(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }
}
