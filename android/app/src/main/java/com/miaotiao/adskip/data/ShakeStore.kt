package com.miaotiao.adskip.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ShakeSuspect(
    val pkg: String,
    val label: String,
    val hits: Int,
    val lastAt: Long,
    val target: String,
    val score: Int
)

object ShakeStore {
    private const val FILE_NAME = "shake_hits.json"
    private const val DAY_MS = 86_400_000L
    private const val MAX_AGE_MS = 7 * DAY_MS

    private lateinit var appContext: Context
    private val _suspects = MutableStateFlow<List<ShakeSuspect>>(emptyList())
    val suspects: StateFlow<List<ShakeSuspect>> = _suspects

    private data class Event(
        val pkg: String,
        val label: String,
        val ts: Long,
        val target: String,
        val score: Int
    )

    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            aggregate(readEvents())
        }
    }

    private fun file() = File(appContext.filesDir, FILE_NAME)

    @Synchronized
    fun addHit(pkg: String, label: String, target: String, score: Int): Int {
        val now = System.currentTimeMillis()
        val events = readEvents().filter { now - it.ts <= MAX_AGE_MS }.toMutableList()
        events.add(Event(pkg, label, now, target, score))
        writeEvents(events)
        aggregate(events)
        return events.count { it.pkg == pkg && now - it.ts <= DAY_MS }
    }

    @Synchronized
    fun allow(pkg: String) {
        val events = readEvents().filter { it.pkg != pkg }
        writeEvents(events)
        aggregate(events)
    }

    @Synchronized
    fun clear() {
        try {
            file().delete()
        } catch (_: Throwable) {
        }
        _suspects.value = emptyList()
    }

    private fun readEvents(): List<Event> = try {
        if (!file().exists()) {
            emptyList()
        } else {
            val arr = JSONArray(file().readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Event(
                    o.optString("pkg"),
                    o.optString("label"),
                    o.optLong("ts"),
                    o.optString("target"),
                    o.optInt("score")
                )
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun writeEvents(events: List<Event>) {
        try {
            val arr = JSONArray()
            events.forEach { e ->
                arr.put(JSONObject().apply {
                    put("pkg", e.pkg)
                    put("label", e.label)
                    put("ts", e.ts)
                    put("target", e.target)
                    put("score", e.score)
                })
            }
            file().writeText(arr.toString())
        } catch (_: Throwable) {
        }
    }

    private fun aggregate(events: List<Event>) {
        val now = System.currentTimeMillis()
        val recent = events.filter { now - it.ts <= MAX_AGE_MS }
        val byPkg = recent.groupBy { it.pkg }
        _suspects.value = byPkg.map { (pkg, evs) ->
            val latest = evs.maxByOrNull { it.ts }!!
            ShakeSuspect(
                pkg = pkg,
                label = latest.label,
                hits = evs.count { now - it.ts <= DAY_MS },
                lastAt = latest.ts,
                target = latest.target,
                score = latest.score
            )
        }.sortedByDescending { it.lastAt }
    }
}
