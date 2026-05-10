package com.spoolcheck.app.core

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app diagnostic log. Ring buffer of recent operations so a user
 * (or someone helping them remotely) can see what the importer parsed,
 * what the matcher decided, and why the off-list path fired — without
 * needing adb or a debugger.
 *
 * Entries are also forwarded to Logcat so a developer attached via
 * Android Studio sees the same stream live.
 *
 * Categories used:
 *   - "IMPORT"   parsing transport-list photos / xlsx
 *   - "MATCH"    matcher decisions per scan frame
 *   - "SCAN"     scanner UI events (commit, off-list, dialog)
 *   - "DB"       database actions (delivery created, item upserted)
 */
object DebugLog {
    private const val TAG = "SpoolCheck"
    private const val MAX = 300

    data class Entry(val time: Long, val category: String, val message: String)

    val entries = mutableStateListOf<Entry>()

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(category: String, message: String) {
        val now = System.currentTimeMillis()
        // Trim long messages so a stray dump can't blow up the buffer.
        val msg = if (message.length > 500) message.take(500) + "…" else message
        entries.add(0, Entry(now, category, msg))
        while (entries.size > MAX) entries.removeAt(entries.size - 1)
        Log.d(TAG, "[$category] $msg")
    }

    fun clear() {
        entries.clear()
        Log.d(TAG, "log cleared")
    }

    /** Format the whole buffer as a single shareable string. */
    fun dump(): String = buildString {
        appendLine("Spool Check debug log — ${entries.size} entries")
        appendLine("=".repeat(60))
        // Iterate in chronological order for readability when shared.
        entries.asReversed().forEach { e ->
            appendLine("${timeFmt.format(Date(e.time))}  [${e.category}] ${e.message}")
        }
    }
}
