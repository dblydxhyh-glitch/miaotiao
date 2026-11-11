package com.miaotiao.adskip.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import com.miaotiao.adskip.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.miaotiao.adskip.data.AppSettings
import com.miaotiao.adskip.data.Record
import com.miaotiao.adskip.data.RecordStore
import com.miaotiao.adskip.data.SettingsRepository
import com.miaotiao.adskip.data.ShakeStore
import com.miaotiao.adskip.data.StatsRepository
import com.miaotiao.adskip.data.UsageStats
import com.miaotiao.adskip.service.AutoToggle
import com.miaotiao.adskip.service.SkipAccessibilityService
import com.miaotiao.adskip.util.MyOsPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Blue = Color(0xFF2E6BFF)
private val Green = Color(0xFF22C55E)
private val Gray = Color(0xFF9CA3AF)
private val Orange = Color(0xFFF59E0B)
private val Sub = Color(0xFF6B7280)

fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${SkipAccessibilityService::class.java.name}"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

@Composable
fun HomeScreen(
    settingsRepo: SettingsRepository,
    statsRepo: StatsRepository,
    recordStore: RecordStore
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.flow.collectAsState(initial = AppSettings())
    val stats by statsRepo.flow.collectAsState(initial = UsageStats())
    val records by recordStore.records.collectAsState()

    var serviceEnabled by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    var showPicker by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var showAccelGuide by remember { mutableStateOf(false) }
    var showSuspects by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { ShakeStore.init(context) }
    val shakeSuspects by ShakeStore.suspects.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceEnabled = isAccessibilityEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
        Text("秒跳", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "开屏广告自动跳过 · 摇一摇误触自动返回",
            fontSize = 13.sp,
            color = Sub
        )
        Spacer(Modifier.height(16.dp))

        StatusCard(
            enabled = serviceEnabled,
            onOpenSettings = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            onBackground = {
                val pkgUri = Uri.parse("package:${context.packageName}")
                val pm = context.getSystemService(PowerManager::class.java)
                val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
                if (!ignoring) {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
                        )
                    } catch (_: Throwable) {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
                        )
                    }
                } else {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
                    )
                }
            }
        )
        Spacer(Modifier.height(12.dp))

        SettingsCard(settings) { key, value ->
            scope.launch {
                when (key) {
                    "autoSkip" -> settingsRepo.setAutoSkip(value)
                    "interceptJump" -> settingsRepo.setInterceptJump(value)
                    "vibrate" -> settingsRepo.setVibrate(value)
                    "autoRestore" -> settingsRepo.setAutoRestore(value)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        SpecialCard(
            enabled = settings.hongguoAutoSwipe,
            total = stats.totalAutoSwipes,
            onToggle = { scope.launch { settingsRepo.setHongguoAutoSwipe(it) } }
        )
        Spacer(Modifier.height(12.dp))

        if (BuildConfig.SHAKE_GUARD) {
            ShakeGuardCard(
                enabled = settings.shakeGuard,
                suspects = shakeSuspects.size,
                onToggle = { scope.launch { settingsRepo.setShakeGuard(it) } },
                onOpen = { showAccelGuide = true },
                onShowSuspects = { showSuspects = true }
            )
            Spacer(Modifier.height(12.dp))
        }

        StatsCard(stats)
        Spacer(Modifier.height(12.dp))

        ExcludedCard(
            excluded = settings.excluded,
            onAdd = { showPicker = true }
        )
        Spacer(Modifier.height(12.dp))

        RecordsCard(
            records = records,
            onGrab = {
                val text = records.joinToString("\n") { r ->
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(r.ts))
                    "$time ${r.appLabel} ${r.type} ${r.detail}"
                }
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("秒跳记录", text))
                Toast.makeText(context, "已复制最近记录", Toast.LENGTH_SHORT).show()
            },
            onClear = { showClear = true }
        )
    }
    }

    if (showPicker) {
        AppPickerDialog(
            initial = settings.excluded,
            onDismiss = { showPicker = false },
            onSave = { set ->
                scope.launch { settingsRepo.setExcluded(set) }
                showPicker = false
            }
        )
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("清空记录") },
            text = { Text("清空后无法恢复，确定清空？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { recordStore.clear() }
                    showClear = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClear = false }) { Text("取消") }
            }
        )
    }

    if (showAccelGuide) {
        AlertDialog(
            onDismissRequest = { showAccelGuide = false },
            title = { Text("如何禁止读取加速度") },
            text = {
                Text(
                    "请手动前往：\n\n" +
                        "设置 → 权限和隐私 → 特殊应用权限 → 滑到底部「读取加速度传感器」\n\n" +
                        "找到目标 App，选择「仅开屏时禁止」——只拦启动时的摇一摇，不影响游戏、导航等正常使用。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAccelGuide = false
                    MyOsPermissions.openAccelerometerPermission(context)
                }) { Text("打开设置") }
            },
            dismissButton = {
                TextButton(onClick = { showAccelGuide = false }) { Text("取消") }
            }
        )
    }

    if (showSuspects) {
        AlertDialog(
            onDismissRequest = { showSuspects = false },
            title = { Text("摇一摇嫌疑") },
            text = {
                if (shakeSuspects.isEmpty()) {
                    Text(
                        "暂无嫌疑。\n\n" +
                            "打开带摇一摇广告的 App（如微博），在开屏广告出现时晃动手机；" +
                            "如果它跳转浏览器或商店，这里会自动记录。\n\n" +
                            "同一 App 24 小时内命中 2 次会发通知。"
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(shakeSuspects, key = { it.pkg }) { suspect ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(suspect.label, fontSize = 15.sp)
                                    Text(
                                        "命中 ${suspect.hits} 次 · 跳转 ${suspect.target}",
                                        fontSize = 12.sp,
                                        color = Gray
                                    )
                                }
                                TextButton(onClick = {
                                    showSuspects = false
                                    AutoToggle.start(context, suspect.pkg, suspect.label)
                                }) { Text("去禁用") }
                                TextButton(onClick = { ShakeStore.allow(suspect.pkg) }) {
                                    Text("放行")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSuspects = false }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun StatusCard(
    enabled: Boolean,
    onOpenSettings: () -> Unit,
    onBackground: () -> Unit
) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (enabled) Green else Gray, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text("无障碍服务", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(50), color = if (enabled) Color(0xFFE8F6EE) else Color(0xFFF1F2F4)) {
                    Text(
                        if (enabled) "已开启" else "未开启",
                        Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (enabled) Color(0xFF15803D) else Sub
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (enabled) "正在运行。若发现失效，请确认系统没有把它从后台清理掉。"
                else "尚未开启，开启后才能自动跳过广告。",
                fontSize = 13.sp,
                color = Sub,
                lineHeight = 19.sp
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue),
                    onClick = onOpenSettings
                ) { Text("无障碍设置", fontSize = 16.sp, fontWeight = FontWeight.Medium) }
                Button(
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF1F2F5),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    onClick = onBackground
                ) { Text("后台运行", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun SettingsCard(settings: AppSettings, onChange: (String, Boolean) -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            SwitchRow("自动跳过开屏广告", "识别「跳过 / 关闭广告」按钮并点击", settings.autoSkip) {
                onChange("autoSkip", it)
            }
            SwitchRow("拦截摇一摇跳转", "被广告摇到浏览器 / 商城时自动返回", settings.interceptJump) {
                onChange("interceptJump", it)
            }
            SwitchRow("操作时轻微振动", "跳过或拦截成功时提示一下", settings.vibrate) {
                onChange("vibrate", it)
            }
            SwitchRow("被系统关闭时自动恢复", "收到通知后点击恢复", settings.autoRestore) {
                onChange("autoRestore", it)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 13.sp, color = Sub)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Blue, uncheckedTrackColor = Color(0xFFDFE3EA))
        )
    }
}

@Composable
private fun SpecialCard(enabled: Boolean, total: Int, onToggle: (Boolean) -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text("专属功能", fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("红果短剧 · 倒计时结束自动上滑", fontSize = 16.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "出现「上滑继续看短剧」时自动切集，已切 $total 次",
                        fontSize = 13.sp,
                        color = Sub
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = Blue, uncheckedTrackColor = Color(0xFFDFE3EA))
                )
            }
        }
    }
}

@Composable
private fun ShakeGuardCard(
    enabled: Boolean,
    suspects: Int,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onShowSuspects: () -> Unit
) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text("摇一摇防护", fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("禁止摇一摇广告读取加速度", fontSize = 16.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "检测到摇一摇跳转后，引导加入 MyOS 加速度权限名单",
                        fontSize = 13.sp,
                        color = Sub
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Blue,
                        uncheckedTrackColor = Color(0xFFDFE3EA)
                    )
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "打开加速度权限页",
                    fontSize = 15.sp,
                    color = Blue,
                    modifier = Modifier.clickable { onOpen() }
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (suspects > 0) "嫌疑 $suspects 个 · 查看" else "嫌疑名单",
                    fontSize = 14.sp,
                    color = if (suspects > 0) Orange else Blue,
                    modifier = Modifier.clickable { onShowSuspects() }
                )
            }
        }
    }
}

@Composable
private fun StatsCard(stats: UsageStats) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(stats.todaySkipped, "今日跳过")
            StatItem(stats.totalSkipped, "累计跳过")
            StatItem(stats.totalIntercepted, "拦截跳转")
        }
    }
}

@Composable
private fun StatItem(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 13.sp, color = Sub)
    }
}

@Composable
private fun ExcludedCard(excluded: Set<String>, onAdd: () -> Unit) {
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("不处理的 App", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    "+ 添加",
                    fontSize = 15.sp,
                    color = Blue,
                    modifier = Modifier.clickable { onAdd() }
                )
            }
            Spacer(Modifier.height(6.dp))
            Text("列表里的应用不会被自动跳过或拦截", fontSize = 13.sp, color = Sub)
            Spacer(Modifier.height(8.dp))
            if (excluded.isEmpty()) {
                Text("暂无，默认处理所有应用", fontSize = 13.sp, color = Gray)
            } else {
                val names = excluded.take(3).map { labelOf(context, it) }
                Text(
                    names.joinToString("、") + if (excluded.size > 3) " 等 ${excluded.size} 个" else "",
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun RecordsCard(records: List<Record>, onGrab: () -> Unit, onClear: () -> Unit) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("最近记录", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("抓取", fontSize = 15.sp, color = Blue, modifier = Modifier.clickable { onGrab() })
                Spacer(Modifier.width(14.dp))
                Text("清空", fontSize = 15.sp, color = Gray, modifier = Modifier.clickable { onClear() })
            }
            Spacer(Modifier.height(8.dp))
            if (records.isEmpty()) {
                Text("还没有记录，去打开一个 App 试试", fontSize = 13.sp, color = Gray)
            } else {
                records.take(10).forEach { record ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            timeFormat.format(Date(record.ts)),
                            fontSize = 13.sp,
                            color = Gray,
                            modifier = Modifier.width(72.dp)
                        )
                        Text(record.appLabel, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        val (label, color) = when (record.type) {
                            "AUTO_SWIPE" -> "自动切集" to Blue
                            "INTERCEPT" -> "拦截跳转" to Orange
                            "SHAKE" -> "摇一摇嫌疑" to Orange
                            else -> "跳过广告" to Blue
                        }
                        Text(label, fontSize = 13.sp, color = color)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPickerDialog(initial: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(context) }
    }

    val filtered = apps.filter {
        it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择不处理的 App") },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("搜索应用名或包名") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(filtered, key = { it.pkg }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (app.pkg in selected) selected - app.pkg else selected + app.pkg
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = app.pkg in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + app.pkg else selected - app.pkg
                                }
                            )
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(app.label, fontSize = 15.sp)
                                Text(app.pkg, fontSize = 11.sp, color = Gray)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("已选 ${selected.size} 个", fontSize = 12.sp, color = Sub)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private data class AppEntry(val pkg: String, val label: String)

private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    return pm.getInstalledApplications(0)
        .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
        .map {
            AppEntry(
                it.packageName,
                runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName)
            )
        }
        .sortedBy { it.label.lowercase(Locale.getDefault()) }
}

private fun labelOf(context: Context, pkg: String): String = runCatching {
    context.packageManager.getApplicationLabel(
        context.packageManager.getApplicationInfo(pkg, 0)
    ).toString()
}.getOrDefault(pkg)
