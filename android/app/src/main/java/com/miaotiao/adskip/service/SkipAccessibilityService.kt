package com.miaotiao.adskip.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.miaotiao.adskip.BuildConfig
import com.miaotiao.adskip.R
import com.miaotiao.adskip.data.AppSettings
import com.miaotiao.adskip.data.Record
import com.miaotiao.adskip.data.RecordStore
import com.miaotiao.adskip.data.SettingsRepository
import com.miaotiao.adskip.data.ShakeStore
import com.miaotiao.adskip.data.StatsRepository
import com.miaotiao.adskip.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class SkipAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MiaoTiao"
        private const val PACKAGE_HONGGUO = "com.phoenix.read"
        private const val POLL_MS = 500L
        private const val HEARTBEAT_MS = 15000L
        private const val SWIPE_COOLDOWN_MS = 2_000L
        private const val AD_SIGNAL_TIMEOUT_MS = 5_000L
        private const val OCR_INTERVAL_MS = 1_200L
        private const val OCR_PROBE_MS = 5_000L
        private const val OCR_IDLE_PROBE_MS = 15_000L
        private const val SKIP_COOLDOWN_MS = 8_000L

        @Volatile
        var connected = false
            private set

        internal fun markConnected(value: Boolean) {
            connected = value
        }

        private val BROWSER_ONLY = setOf(
            "com.android.chrome", "com.UCMobile", "com.tencent.mtt", "com.baidu.searchbox",
            "com.quark.browser", "com.android.browser", "com.miui.browser", "com.vivo.browser",
            "com.coloros.browser", "com.heytap.browser", "com.zte.mifavor.browser",
            "com.huawei.browser", "com.sec.android.app.sbrowser", "org.mozilla.firefox",
            "com.ume.browser"
        )

        private val STORE_PACKAGES = setOf(
            "com.taobao.taobao", "com.tmall.wireless", "com.jingdong.app.mall",
            "com.xunmeng.pinduoduo", "com.suning.mobile.ebuy", "com.taobao.idlefish",
            "com.achievo.vipshop", "com.kaola", "com.jd.jrapp"
        )

        private val BROWSER_OR_STORE = BROWSER_ONLY + STORE_PACKAGES
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ocrRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val settingsRepo by lazy { SettingsRepository(applicationContext) }
    private val statsRepo by lazy { StatsRepository(applicationContext) }
    private val recordStore by lazy { RecordStore(applicationContext) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var currentSettings = AppSettings()

    private var lastSignature = ""
    private var lastHeartbeat = 0L
    private var lastLogAt = 0L
    private var lastSwipeAt = 0L
    private var lastAdSignalAt = 0L
    private var adSeen = false
    private var swipeInFlight = false
    private var hintClearedSinceSwipe = true
    private var ocrHintPresent = false
    private var lastBlockedLog = 0L
    private var lastAdCtxLog = 0L
    private var lastWindowSig = ""
    private var capturing = false
    private var lastCaptureAt = 0L
    private var lastOcrText = ""
    private var inPlayer = false

    // OCR 按需探测 + 倒计时本地计时
    private var ocrProbeUntil = 0L
    private var pendingSwipeAt = 0L
    private var pendingSwipeText = ""
    private var pendingEpisodeNumber = ""
    private var lastEpisodeTitle = ""
    private var lastIdleProbeAt = 0L

    // 通用跳过 / 拦截
    private var lastForegroundPkg = ""
    private var lastForegroundAt = 0L
    private var lastInterceptAt = 0L
    private var lastSkipAt = 0L
    private var lastSkipPkg = ""
    private val shakeDetector by lazy { ShakeDetector(applicationContext) }
    private var lastClickAt = 0L
    private var lastClickPkg = ""
    private var lastShakeEvalAt = 0L
    private var lastShakeEvalKey = ""

    private var receiverRegistered = false
    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.miaotiao.adskip.action.TEST_AUTO_TOGGLE") {
                val pkg = intent.getStringExtra("pkg") ?: return
                val label = appLabel(pkg)
                AutoToggle.start(applicationContext, pkg, label)
                return
            }
            val seconds = intent?.getIntExtra("seconds", 5) ?: 5
            if (!inPlayer) {
                DebugLog.log(applicationContext, "TEST ignored, not in player")
                return
            }
            pendingSwipeAt = System.currentTimeMillis() + seconds * 1000L + 500L
            pendingSwipeText = "debug-test"
            pendingEpisodeNumber = episodeNumberOf(lastEpisodeTitle)
            ocrProbeUntil = 0
            DebugLog.log(applicationContext, "TEST countdown ${seconds}s armed")
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                scan()
            } catch (t: Throwable) {
                DebugLog.log(applicationContext, "scan error: ${t.javaClass.simpleName} ${t.message}")
            } finally {
                handler.postDelayed(this, POLL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        ShakeStore.init(applicationContext)
        DebugLog.log(applicationContext, "service connected")
        Log.i(TAG, "accessibility service connected")
        KeepAliveService.start(applicationContext)
        serviceScope.launch {
            settingsRepo.flow.collect { currentSettings = it }
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                testReceiver,
                IntentFilter().apply {
                    addAction("com.miaotiao.adskip.action.TEST_COUNTDOWN")
                    addAction("com.miaotiao.adskip.action.TEST_AUTO_TOGGLE")
                },
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        }
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleForegroundChange(pkg, System.currentTimeMillis())
                if (pkg == PACKAGE_HONGGUO) {
                    val className = e.className?.toString().orEmpty()
                    inPlayer = className.contains("ShortSeriesActivity")
                    if (!inPlayer) {
                        pendingSwipeAt = 0
                        pendingSwipeText = ""
                        pendingEpisodeNumber = ""
                    }
                    ocrProbeUntil = if (inPlayer) {
                        System.currentTimeMillis() + OCR_PROBE_MS
                    } else {
                        0
                    }
                } else {
                    inPlayer = false
                    scheduleSkipScan(pkg)
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (pkg == PACKAGE_HONGGUO && inPlayer) {
                    ocrProbeUntil = System.currentTimeMillis() + OCR_PROBE_MS
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                lastClickAt = System.currentTimeMillis()
                lastClickPkg = pkg
            }
        }
    }

    // ---------------- 摇一摇拦截 ----------------

    private fun handleForegroundChange(pkg: String, now: Long) {
        if (pkg == lastForegroundPkg) return
        // 输入法窗口不是前台应用，忽略它的出现/消失，避免「输入法 -> 商城」误拦截
        if (isImePackage(pkg)) return
        val previous = lastForegroundPkg

        // 摇一摇广告证据采集：普通 App → 浏览器/商店，8 秒内
        if (isBrowserOrStore(pkg) &&
            previous.isNotEmpty() &&
            !isBrowserOrStore(previous) &&
            !isSystemPkg(previous) &&
            now - lastForegroundAt < 8_000
        ) {
            evaluateShakeJump(previous, pkg, now)
        }

        val previousNoClick = lastClickPkg != previous || now - lastClickAt > 1_500
        val isJumpAd = isBrowser(pkg) || (isStore(pkg) && previousNoClick)

        if (currentSettings.interceptJump &&
            isJumpAd &&
            previous.isNotEmpty() &&
            !isBrowserOrStore(previous) &&
            !isSystemPkg(previous) &&
            previous !in currentSettings.excluded &&
            now - lastForegroundAt < 8_000 &&
            now - lastInterceptAt > 5_000
        ) {
            val ok = performGlobalAction(GLOBAL_ACTION_BACK)
            if (ok) {
                lastInterceptAt = now
                DebugLog.log(applicationContext, "INTERCEPT $previous -> $pkg")
                recordAction(pkg, "INTERCEPT", "from $previous")
                vibrateIfNeeded()
                // 浏览器可能把返回当成「上一页」，延迟重试最多两次
                scheduleBackRetry(pkg, 0)
                // 仍退不出去（如浏览器主页不响应返回），直接把原 App 拉到前台
                handler.postDelayed({
                    if (rootInActiveWindow?.packageName?.toString() == pkg) {
                        bringToFront(previous)
                    }
                }, 2_500)
                return
            }
        }
        lastForegroundPkg = pkg
        lastForegroundAt = now
        if (BuildConfig.SHAKE_GUARD &&
            currentSettings.shakeGuard &&
            pkg != packageName &&
            !isBrowserOrStore(pkg) &&
            !isSystemPkg(pkg)
        ) {
            shakeDetector.start(10_000)
        }
    }

    private fun evaluateShakeJump(source: String, target: String, now: Long) {
        if (!BuildConfig.SHAKE_GUARD) return
        if (!currentSettings.shakeGuard) return
        if (source == packageName || source in currentSettings.excluded || isSystemPkg(source)) return
        val key = "$source->$target"
        if (key == lastShakeEvalKey && now - lastShakeEvalAt < 5_000) return
        lastShakeEvalKey = key
        lastShakeEvalAt = now
        val elapsed = now - lastForegroundAt
        if (elapsed > 8_000) return

        val features = shakeDetector.features()
        val noClick = lastClickPkg != source || now - lastClickAt > 1_500
        var score = 40
        if (elapsed <= 8_000) score += 15
        if (features.isShake) score += 25
        if (noClick) score += 10
        val previousHits = ShakeStore.suspects.value.firstOrNull { it.pkg == source }?.hits ?: 0
        if (previousHits >= 2) score += 10

        val label = appLabel(source)
        DebugLog.log(
            applicationContext,
            "SHAKE src=$source dst=$target score=$score peak=${features.peak} " +
                "var=${features.variance} rev=${features.reversals} noClick=$noClick"
        )

        if (score >= 70) {
            val hits = ShakeStore.addHit(source, label, target, score)
            serviceScope.launch {
                recordStore.add(
                    Record(
                        ts = now,
                        packageName = source,
                        appLabel = label,
                        type = "SHAKE",
                        detail = "dst=$target score=$score hits=$hits"
                    )
                )
            }
            if (hits >= 2) {
                notifyShakeSuspect(source, label)
            }
        } else if (score >= 50) {
            DebugLog.log(applicationContext, "SHAKE log-only $source score=$score")
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    }.getOrDefault(pkg)

    private fun notifyShakeSuspect(pkg: String, label: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        "shake_alerts",
                        "摇一摇提醒",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val contentIntent = PendingIntent.getActivity(
                this,
                1,
                Intent(this, com.miaotiao.adskip.MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, "shake_alerts")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("发现摇一摇广告")
                .setContentText("$label 开屏后跳转广告，建议禁止它读取加速度")
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()
            manager.notify(pkg.hashCode(), notification)
        } catch (_: Throwable) {
        }
    }

    // ---------------- 通用开屏跳过 ----------------

    private fun scheduleBackRetry(pkg: String, attempt: Int) {
        if (attempt >= 2) return
        handler.postDelayed({
            val current = rootInActiveWindow?.packageName?.toString()
            if (current == null || current == pkg) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                DebugLog.log(applicationContext, "INTERCEPT back retry ${attempt + 1} -> $pkg")
                scheduleBackRetry(pkg, attempt + 1)
            }
        }, 700)
    }

    private fun bringToFront(pkg: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            startActivity(intent)
            DebugLog.log(applicationContext, "INTERCEPT bring $pkg to front")
        } catch (t: Throwable) {
            DebugLog.log(applicationContext, "INTERCEPT bringToFront failed: ${t.message}")
        }
    }

    private fun scheduleSkipScan(pkg: String) {
        if (!currentSettings.autoSkip) return
        if (pkg == PACKAGE_HONGGUO || pkg == packageName) return
        if (pkg in currentSettings.excluded) return
        if (isSystemPkg(pkg)) return
        val target = pkg
        // 开屏广告可能延迟出现，持续扫描 8 秒；命中后有 8 秒冷却
        var delay = 300L
        while (delay <= 8_000L) {
            handler.postDelayed({ trySkip(target) }, delay)
            delay += 500L
        }
        // 图片/视频广告无障碍树里没有「跳过」节点，用 OCR 兜底
        for (d in listOf(1_500L, 2_800L, 4_100L)) {
            handler.postDelayed({ trySkipByOcr(target) }, d)
        }
    }

    private var skipOcrBusy = false
    private var lastOcrSkipAt = 0L

    private fun trySkipByOcr(pkg: String) {
        if (!currentSettings.autoSkip) return
        if (pkg == PACKAGE_HONGGUO || pkg == packageName) return
        if (pkg in currentSettings.excluded) return
        if (isSystemPkg(pkg)) return
        if (lastForegroundPkg != pkg) return
        if (System.currentTimeMillis() - lastForegroundAt > 8_000) return
        if (skipOcrBusy) return
        val now = System.currentTimeMillis()
        if (lastSkipPkg == pkg && now - lastSkipAt < SKIP_COOLDOWN_MS) return
        if (now - lastOcrSkipAt < 1_000) return
        lastOcrSkipAt = now
        skipOcrBusy = true
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val full = try {
                            Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )?.copy(Bitmap.Config.ARGB_8888, false)
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }
                        if (full == null) {
                            skipOcrBusy = false
                            return
                        }
                        ocrSkipRegions(full, 0, pkg)
                    }

                    override fun onFailure(errorCode: Int) {
                        skipOcrBusy = false
                        DebugLog.log(applicationContext, "skip screenshot fail $errorCode")
                    }
                }
            )
        } catch (t: Throwable) {
            skipOcrBusy = false
            DebugLog.log(applicationContext, "skip screenshot error ${t.message}")
        }
    }

    private fun ocrSkipBitmap(
        bitmap: Bitmap,
        offsetX: Int,
        offsetY: Int,
        scale: Float,
        pkg: String,
        onDone: (Boolean) -> Unit
    ) {
        ocrRecognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val t = line.text.replace(Regex("\\s+"), "")
                        if (t.contains("跳过")) {
                            val box = line.boundingBox
                            if (box != null) {
                                tapPoint(
                                    offsetX + box.centerX() / scale,
                                    offsetY + box.centerY() / scale
                                )
                                lastSkipAt = System.currentTimeMillis()
                                lastSkipPkg = pkg
                                DebugLog.log(applicationContext, "SKIP $pkg [ocr:$t]")
                                recordAction(pkg, "SKIP", "ocr:$t")
                                vibrateIfNeeded()
                                onDone(true)
                                return@addOnSuccessListener
                            }
                        }
                    }
                }
                onDone(false)
            }
            .addOnFailureListener { e ->
                DebugLog.log(applicationContext, "skip ocr fail ${e.message}")
                onDone(false)
            }
    }

    private data class OcrRegion(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val scale: Float
    )

    private val SKIP_REGIONS = listOf(
        OcrRegion(0.45f, 0f, 1f, 0.26f, 2f),   // 右上角（携程、全民K歌）
        OcrRegion(0.40f, 0.72f, 1f, 1f, 2f),   // 右下角（微博）
        OcrRegion(0f, 0f, 1f, 0.16f, 1.5f),    // 顶部整条（左上/中上）
        OcrRegion(0f, 0.90f, 1f, 1f, 2f),      // 底栏
        OcrRegion(0f, 0f, 1f, 1f, 1f)          // 整屏兜底
    )

    private fun ocrSkipRegions(full: Bitmap, index: Int, pkg: String) {
        if (index >= SKIP_REGIONS.size) {
            full.recycle()
            skipOcrBusy = false
            return
        }
        val region = SKIP_REGIONS[index]
        if (region.scale == 1f && region.left == 0f && region.top == 0f) {
            ocrSkipBitmap(full, 0, 0, 1f, pkg) { found ->
                if (found) {
                    full.recycle()
                    skipOcrBusy = false
                } else {
                    ocrSkipRegions(full, index + 1, pkg)
                }
            }
            return
        }
        try {
            val left = (full.width * region.left).toInt()
            val top = (full.height * region.top).toInt()
            val w = (full.width * region.right).toInt() - left
            val h = (full.height * region.bottom).toInt() - top
            if (w <= 0 || h <= 0) {
                ocrSkipRegions(full, index + 1, pkg)
                return
            }
            val base = Bitmap.createBitmap(full, left, top, w, h)
            val scaled = Bitmap.createScaledBitmap(
                base,
                (w * region.scale).toInt(),
                (h * region.scale).toInt(),
                true
            )
            base.recycle()
            ocrSkipBitmap(scaled, left, top, region.scale, pkg) { found ->
                scaled.recycle()
                if (found) {
                    full.recycle()
                    skipOcrBusy = false
                } else {
                    ocrSkipRegions(full, index + 1, pkg)
                }
            }
        } catch (t: Throwable) {
            DebugLog.log(applicationContext, "skip crop error ${t.message}")
            ocrSkipRegions(full, index + 1, pkg)
        }
    }

    private fun tapPoint(x: Float, y: Float) {
        try {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 0.5f, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (_: Throwable) {
        }
    }

    private fun trySkip(pkg: String) {
        if (!currentSettings.autoSkip) return
        if (lastForegroundPkg != pkg) return
        if (System.currentTimeMillis() - lastForegroundAt > 8_000) return
        val now = System.currentTimeMillis()
        if (now - lastSkipAt < SKIP_COOLDOWN_MS && lastSkipPkg == pkg) return
        val root = rootInActiveWindow ?: return
        val node = findSkipNode(root, 0) ?: return
        val text = node.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
            ?: node.contentDescription?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
            ?: node.viewIdResourceName?.substringAfterLast('/')
            ?: "skip"
        var clickable: AccessibilityNodeInfo? = node
        var guard = 0
        while (clickable != null && !clickable.isClickable && guard < 5) {
            clickable = clickable.parent
            guard++
        }
        val clicked = (clickable ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            lastSkipAt = now
            lastSkipPkg = pkg
            DebugLog.log(applicationContext, "SKIP $pkg [$text]")
            recordAction(pkg, "SKIP", text)
            vibrateIfNeeded()
        }
    }

    private fun findSkipNode(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 30) return null
        if (isSkipNode(node)) return node
        for (i in 0 until node.childCount) {
            val found = findSkipNode(node.getChild(i), depth + 1)
            if (found != null) return found
        }
        return null
    }

    private val SKIP_ID = Regex("(?i)(skip|splash_skip|ad_skip|close_ad)")
    private val CLOSE_ID = Regex("(?i)(^|[/_])(close|iv_close|btn_close)")

    private fun isSkipNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled) return false
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val id = node.viewIdResourceName.orEmpty()
        if (text.isNotEmpty() && isSkipText(text)) return true
        if (desc.isNotEmpty() && isSkipText(desc)) return true
        if (id.isNotEmpty() && SKIP_ID.containsMatchIn(id)) return true
        // 只对「右上/右下」的 close 图标放宽，避免误关弹窗
        if (id.isNotEmpty() && CLOSE_ID.containsMatchIn(id) && isTopOrRight(node)) {
            return node.isClickable
        }
        return false
    }

    private fun isSkipText(text: String): Boolean {
        val t = text.replace(Regex("\\s+"), "")
        if (t.isEmpty() || t.length > 14) return false
        if (t.contains("跳过")) return true
        if (t.contains("关闭广告")) return true
        if (t.equals("skip", true)) return true
        return false
    }

    private fun isTopOrRight(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val metrics = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val cx = bounds.centerX().toFloat()
        val cy = bounds.centerY().toFloat()
        return cx > metrics.width() * 0.5f || cy < metrics.height() * 0.30f
    }

    // ---------------- 红果短剧自动化 ----------------

    private fun scan() {
        val roots = collectRoots()
        if (roots.isEmpty()) return

        val now = System.currentTimeMillis()
        val activeRoot = rootInActiveWindow ?: roots.firstOrNull()
        val activePkg = activeRoot?.packageName?.toString().orEmpty()
        if (activePkg.isNotEmpty()) {
            handleForegroundChange(activePkg, now)
        }

        val packageSet = roots.mapNotNull { it.packageName?.toString() }.toSet()

        val texts = ArrayList<String>(160)
        roots.forEach { collectTexts(it, texts, 0) }

        if (AutoToggle.active) {
            activeRoot?.let { AutoToggle.tick(this, it, texts) }
            return
        }
        if (texts.isEmpty()) return

        // 信息流有「首页 / 推荐」标签；播放页没有。防止服务重启后 inPlayer 状态丢失
        val looksLikeFeed = texts.any { it == "首页" || it == "推荐" }
        if (looksLikeFeed) {
            inPlayer = false
        } else if (texts.any { Regex("第\\d+集").containsMatchIn(it) }) {
            inPlayer = true
        }

        val joined = texts.joinToString("")
        val nodeHints = texts.filter { isSwipeHint(it) }
        val hasHint = nodeHints.isNotEmpty() || (joined.contains("上滑") && joined.contains("短剧"))

        if (PACKAGE_HONGGUO !in packageSet) return

        val episode = texts.firstOrNull { Regex("第\\d+集").containsMatchIn(it) } ?: ""
        if (inPlayer && episode.isNotEmpty() && episode != lastEpisodeTitle) {
            lastEpisodeTitle = episode
            ocrProbeUntil = now + OCR_PROBE_MS
        }

        val counting = nodeHints.any { isCountdown(it) } ||
            Regex("\\d+\\s*(s|秒)\\s*后").containsMatchIn(joined) ||
            joined.contains("倒计时结束后")
        val ready = hasHint && !counting
        val markers = texts.filter { isAdMarker(it) }

        val windowSig = roots.joinToString(",") { "${it.packageName}#${it.windowId}" }
        if (windowSig != lastWindowSig) {
            lastWindowSig = windowSig
            DebugLog.log(applicationContext, "WINDOWS $windowSig")
        }

        if (counting || markers.isNotEmpty()) {
            adSeen = true
            lastAdSignalAt = now
        }
        if (counting || (!hasHint && !ocrHintPresent)) {
            hintClearedSinceSwipe = true
        }

        // 倒计时计时到点：确认还在同一集再滑
        if (pendingSwipeAt > 0 && now >= pendingSwipeAt) {
            val currentTitle = texts.firstOrNull { Regex("第\\d+集").containsMatchIn(it) } ?: ""
            val currentNumber = episodeNumberOf(currentTitle)
            val sameEpisode = pendingEpisodeNumber.isEmpty() ||
                currentNumber.isEmpty() ||
                currentNumber == pendingEpisodeNumber
            if (sameEpisode) {
                attemptSwipe(now, pendingSwipeText, "timer")
            } else {
                DebugLog.log(
                    applicationContext,
                    "TIMER cancelled, episode $pendingEpisodeNumber -> $currentNumber"
                )
            }
            pendingSwipeAt = 0
            pendingSwipeText = ""
            pendingEpisodeNumber = ""
        }

        if (ready) {
            attemptSwipe(now, nodeHints.firstOrNull() ?: joined, "a11y")
        }
        if (!hasHint && markers.isEmpty() && now - lastAdSignalAt > AD_SIGNAL_TIMEOUT_MS) {
            adSeen = false
        }

        // 播放页内持续 OCR（信息流不跑）；倒计时等待期间关闭 OCR
        if (PACKAGE_HONGGUO in packageSet && inPlayer && !hasHint && pendingSwipeAt == 0L) {
            maybeCapture(now)
        }

        val signature = "$hasHint|$counting|${nodeHints.joinToString("|")}#${markers.joinToString("|")}"
        if (signature != lastSignature && now - lastLogAt >= 300) {
            lastSignature = signature
            lastLogAt = now
            DebugLog.log(
                applicationContext,
                "sig hasHint=$hasHint counting=$counting markers=[${markers.joinToString(" | ")}] " +
                    "nodeHints=[${nodeHints.joinToString(" | ")}] adSeen=$adSeen"
            )
        }

        if (now - lastHeartbeat >= HEARTBEAT_MS) {
            lastHeartbeat = now
            DebugLog.log(
                applicationContext,
                "scan texts=${texts.size} adSeen=$adSeen probe=${now < ocrProbeUntil} pending=$pendingSwipeAt"
            )
        }

        if (markers.isNotEmpty() && now - lastAdCtxLog > 2000) {
            lastAdCtxLog = now
            val candidates = texts.filter {
                it.contains("短剧") || it.contains("上滑") || it.contains("滑") ||
                    it.contains("秒") || it.contains("广告") || it.contains("倒计时")
            }
            DebugLog.log(
                applicationContext,
                "ADCTX n=${texts.size} cand=[${candidates.take(8).joinToString(" | ")}]"
            )
        }
    }

    private fun maybeCapture(now: Long) {
        if (Build.VERSION.SDK_INT < 30 || capturing || now - lastCaptureAt < OCR_INTERVAL_MS) return
        capturing = true
        lastCaptureAt = now
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val full = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )?.copy(Bitmap.Config.ARGB_8888, false)
                            screenshot.hardwareBuffer.close()
                            capturing = false
                            if (full != null) {
                                runOcr(full)
                                full.recycle()
                            }
                        } catch (t: Throwable) {
                            capturing = false
                            DebugLog.log(applicationContext, "screenshot decode error ${t.message}")
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        capturing = false
                        DebugLog.log(applicationContext, "screenshot fail $errorCode")
                    }
                }
            )
        } catch (t: Throwable) {
            capturing = false
            DebugLog.log(applicationContext, "screenshot error ${t.message}")
        }
    }

    private fun runOcr(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val top = (height * 0.80).toInt()
        val bottom = (height * 0.98).toInt()
        val strip = Bitmap.createBitmap(bitmap, 0, top, width, bottom - top)
        val image = InputImage.fromBitmap(strip, 0)
        ocrRecognizer.process(image)
            .addOnSuccessListener { result ->
                val raw = result.text.replace(Regex("\\s+"), "")
                if (raw.isNotEmpty() && raw != lastOcrText) {
                    lastOcrText = raw
                    DebugLog.log(applicationContext, "OCR [$raw]")
                }
                handleOcrText(raw)
                strip.recycle()
            }
            .addOnFailureListener { e ->
                DebugLog.log(applicationContext, "ocr fail ${e.message}")
                strip.recycle()
            }
    }

    private fun handleOcrText(raw: String) {
        val hasHint = raw.contains("上滑") && raw.contains("短剧")
        ocrHintPresent = hasHint
        if (!hasHint) {
            hintClearedSinceSwipe = true
            return
        }
        val match = Regex("(\\d+)\\s*(s|秒)\\s*后").find(raw)
        if (match != null) {
            adSeen = true
            lastAdSignalAt = System.currentTimeMillis()
            hintClearedSinceSwipe = true
            val seconds = match.groupValues[1].toIntOrNull() ?: 0
            if (seconds in 1..120) {
                pendingSwipeAt = System.currentTimeMillis() + seconds * 1000L + 500L
                pendingSwipeText = raw
                pendingEpisodeNumber = episodeNumberOf(lastEpisodeTitle)
                ocrProbeUntil = 0
                DebugLog.log(applicationContext, "COUNTDOWN ${seconds}s, timer armed")
            }
            return
        }
        if (raw.contains("倒计时")) {
            return
        }
        attemptSwipe(System.currentTimeMillis(), raw, "ocr")
    }

    private fun attemptSwipe(now: Long, text: String, source: String) {
        if (!currentSettings.hongguoAutoSwipe) return
        if (swipeInFlight) return
        if (now - lastSwipeAt < SWIPE_COOLDOWN_MS) {
            if (now - lastBlockedLog > 1000) {
                lastBlockedLog = now
                DebugLog.log(applicationContext, "READY blocked($source) cooldown text=[$text]")
            }
            return
        }
        if (!hintClearedSinceSwipe) {
            if (now - lastBlockedLog > 1000) {
                lastBlockedLog = now
                DebugLog.log(applicationContext, "READY blocked($source) hint-not-cleared text=[$text]")
            }
            return
        }
        hintClearedSinceSwipe = false
        lastSwipeAt = now
        ocrProbeUntil = 0
        DebugLog.log(applicationContext, "READY($source) text=[$text] -> swipe")
        swipeUp(text)
        vibrateIfNeeded()
        serviceScope.launch {
            statsRepo.onAutoSwipe()
            recordStore.add(
                Record(
                    ts = System.currentTimeMillis(),
                    packageName = PACKAGE_HONGGUO,
                    appLabel = "红果短剧",
                    type = "AUTO_SWIPE",
                    detail = text.take(60)
                )
            )
        }
    }

    private fun collectRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>(4)
        try {
            rootInActiveWindow?.let { roots.add(it) }
            windows?.forEach { window ->
                val root = window.root ?: return@forEach
                val exists = roots.any {
                    it.windowId == root.windowId && it.packageName == root.packageName
                }
                if (!exists) roots.add(root)
            }
        } catch (_: Throwable) {
        }
        return roots
    }

    private fun swipeUp(readyText: String) {
        swipeInFlight = true
        val metrics = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val width = metrics.width().toFloat()
        val height = metrics.height().toFloat()
        val path = Path().apply {
            moveTo(width * 0.5f, height * 0.75f)
            lineTo(width * 0.5f, height * 0.30f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 300L))
            .build()
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    swipeInFlight = false
                    DebugLog.log(applicationContext, "SWIPE completed ($readyText)")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    swipeInFlight = false
                    DebugLog.log(applicationContext, "SWIPE cancelled ($readyText)")
                }
            },
            null
        )
        DebugLog.log(applicationContext, "SWIPE dispatch=$dispatched ($readyText)")
    }

    private fun recordAction(pkg: String, type: String, detail: String) {
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        serviceScope.launch {
            when (type) {
                "SKIP" -> statsRepo.onSkip()
                "INTERCEPT" -> statsRepo.onIntercept()
                else -> statsRepo.onAutoSwipe()
            }
            recordStore.add(Record(System.currentTimeMillis(), pkg, label, type, detail))
        }
    }

    private fun vibrateIfNeeded() {
        if (!currentSettings.vibrate) return
        try {
            val vibrator = getSystemService(Vibrator::class.java) ?: return
            vibrator.vibrate(
                VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } catch (_: Throwable) {
        }
    }

    private fun isBrowserOrStore(pkg: String): Boolean = pkg in BROWSER_OR_STORE

    private fun isBrowser(pkg: String): Boolean = pkg in BROWSER_ONLY

    private fun isStore(pkg: String): Boolean = pkg in STORE_PACKAGES

    private val imePackages: Set<String> by lazy {
        try {
            getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.mapNotNull { it.packageName }
                ?.toSet()
                ?: emptySet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private fun isImePackage(pkg: String): Boolean = pkg in imePackages

    private fun isSystemPkg(pkg: String): Boolean =
        pkg == "android" || pkg.startsWith("com.android.") || pkg.startsWith("com.zte.") ||
            pkg.startsWith("com.google.android.") || pkg == packageName

    private fun normalize(text: String): String = text.replace(Regex("\\s+"), "")

    private fun episodeNumberOf(text: String): String =
        Regex("第(\\d+)集").find(text)?.groupValues?.get(1) ?: ""

    private fun isSwipeHint(text: String): Boolean {
        val t = normalize(text)
        return t.contains("上滑") && t.contains("短剧")
    }

    private fun isCountdown(text: String): Boolean {
        val t = normalize(text)
        return Regex("\\d+\\s*(s|秒)").containsMatchIn(t) || t.contains("倒计时")
    }

    private fun isAdMarker(text: String): Boolean {
        val t = normalize(text)
        return t == "广告" ||
            t.contains("点击进入直播间") ||
            t.contains("倒计时结束后即可滑动") ||
            t.contains("查看详情") ||
            t.contains("立即领取")
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 30 || out.size >= 300) return
        node.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        for (i in 0 until node.childCount) {
            collectTexts(node.getChild(i), out, depth + 1)
        }
    }

    override fun onInterrupt() {
        DebugLog.log(applicationContext, "interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        DebugLog.log(applicationContext, "onUnbind")
        notifyDisconnected()
        return super.onUnbind(intent)
    }

    private fun notifyDisconnected() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        "alerts",
                        "状态提醒",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val contentIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, "alerts")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("无障碍服务已断开")
                .setContentText("点击重新开启，否则无法自动跳过")
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()
            manager.notify(2001, notification)
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        connected = false
        shakeDetector.stop()
        handler.removeCallbacks(pollRunnable)
        if (receiverRegistered) {
            try {
                unregisterReceiver(testReceiver)
            } catch (_: Throwable) {
            }
            receiverRegistered = false
        }
        serviceScope.cancel()
        DebugLog.log(applicationContext, "service destroyed")
        super.onDestroy()
    }
}
