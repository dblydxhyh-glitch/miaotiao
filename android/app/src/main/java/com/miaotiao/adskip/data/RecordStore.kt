package com.miaotiao.adskip.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class Record(
    val ts: Long,
    val packageName: String,
    val appLabel: String,
    val type: String,
    val detail: String
)

class RecordStore(private val context: Context) {

    companion object {
        private const val MAX_RECORDS = 200
    }

    private val mutex = Mutex()
    private val file = File(context.filesDir, "records.txt")
    private val _records = MutableStateFlow<List<Record>>(emptyList())
    val records: StateFlow<List<Record>> = _records

    init {
        _records.value = readAll()
    }

    private fun readAll(): List<Record> = try {
        if (!file.exists()) emptyList()
        else file.readLines().mapNotNull { line ->
            val parts = line.split('|', limit = 5)
            if (parts.size < 5) null else Record(
                ts = parts[0].toLongOrNull() ?: 0L,
                packageName = parts[1],
                appLabel = parts[2],
                type = parts[3],
                detail = parts[4]
            )
        }.reversed()
    } catch (_: Throwable) {
        emptyList()
    }

    suspend fun add(record: Record) = mutex.withLock {
        try {
            val all = (listOf(record) + _records.value).take(MAX_RECORDS)
            _records.value = all
            file.writeText(
                all.reversed().joinToString("\n") { r ->
                    val detail = r.detail.replace('|', '/').replace('\n', ' ')
                    "${r.ts}|${r.packageName}|${r.appLabel}|${r.type}|$detail"
                }
            )
        } catch (_: Throwable) {
        }
    }

    suspend fun clear() = mutex.withLock {
        try {
            file.writeText("")
        } catch (_: Throwable) {
        }
        _records.value = emptyList()
    }
}
