package com.example.minimalapk.log

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class LogLevel(val letter: Char, val priority: Int) {
    INFO('I', Log.INFO),
    WARN('W', Log.WARN),
    ERROR('E', Log.ERROR),
}

internal class LogEntry(
    val time: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/**
 * In-app log buffer backing the 日志 screen. Native backends log to logcat only, so the events that
 * matter for diagnosis (backend probing, camera errors) are mirrored here explicitly.
 */
internal object AppLog {
    private const val MAX_ENTRIES = 1000
    private const val ANDROID_TAG = "MinimalApk"
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = mutableStateListOf<LogEntry>()

    val entries: List<LogEntry> get() = buffer

    fun i(tag: String, message: String) = add(LogLevel.INFO, tag, message)

    fun w(tag: String, message: String, error: Throwable? = null) =
        add(LogLevel.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) =
        add(LogLevel.ERROR, tag, message, error)

    fun clear() {
        synchronized(buffer) { buffer.clear() }
    }

    /** Immutable snapshot; safe to iterate while background threads append. */
    fun snapshot(): List<LogEntry> = buffer.toList()

    fun format(entry: LogEntry): String =
        "${entry.time} ${entry.level.letter} [${entry.tag}] ${entry.message}"

    private fun add(level: LogLevel, tag: String, message: String, error: Throwable? = null) {
        val text = if (error == null) message else "$message\n${Log.getStackTraceString(error)}"
        val time = synchronized(timeFormat) { timeFormat.format(Date()) }
        Log.println(level.priority, "$ANDROID_TAG/$tag", text)
        synchronized(buffer) {
            buffer.add(LogEntry(time, level, tag, text))
            while (buffer.size > MAX_ENTRIES) buffer.removeAt(0)
        }
    }
}
