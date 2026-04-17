package com.prism.launcher

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global Terminal-style Logger for Prism.
 * Handles persistence to file, crash interception, and live UI updates.
 */
object PrismLogger {
    private const val TAG = "PrismLogger"
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // Live UI feed (replays last 50 for when the UI opens)
    val logFlow = MutableSharedFlow<LogEntry>(replay = 50, extraBufferCapacity = 100)

    private var logFile: File? = null
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    data class LogEntry(
        val timestamp: String,
        val level: Level,
        val tag: String,
        val message: String
    )

    enum class Level { INFO, WARN, ERROR, SUCCESS }

    fun init(context: Context) {
        val storage = Environment.getExternalStorageDirectory()
        val prismDir = File(storage, "Prism")
        if (!prismDir.exists()) prismDir.mkdirs()
        
        logFile = File(prismDir, "diagnostics.log")
        
        // Intercept Crashes globally
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val threadInfo = "Name: ${thread.name}, ID: ${thread.id}"
            logError("CRASH", "Uncaught exception in thread [$threadInfo]", throwable)
            // Still write to standard Logcat for system collectors
            Log.e(TAG, "FATAL EXCEPTION in thread [$threadInfo]", throwable)
            oldHandler?.uncaughtException(thread, throwable)
        }
        
        logInfo(TAG, "Prism Diagnostics Initialized. Logging to ${logFile?.absolutePath}")
    }

    fun logInfo(tag: String, message: String) {
        append(Level.INFO, tag, message)
        Log.i(tag, message)
    }

    fun logWarning(tag: String, message: String) {
        append(Level.WARN, tag, message)
        Log.w(tag, message)
    }

    fun logSuccess(tag: String, message: String) {
        append(Level.SUCCESS, tag, message)
        Log.d(tag, "SUCCESS: $message")
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else message
        append(Level.ERROR, tag, fullMessage)
        Log.e(tag, fullMessage)
    }

    private fun append(level: Level, tag: String, message: String) {
        val entry = LogEntry(getTimestamp(), level, tag, message)
        scope.launch {
            logFlow.emit(entry)
            writeToFile(entry)
        }
    }

    private fun writeToFile(entry: LogEntry) {
        try {
            val storage = Environment.getExternalStorageDirectory()
            val prismDir = File(storage, "Prism")
            if (!prismDir.exists()) prismDir.mkdirs()
            logFile?.let { file ->
                FileWriter(file, true).use { writer ->
                    writer.write("[${entry.timestamp}] [${entry.level}] [${entry.tag}] ${entry.message}\n")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file", e)
        }
    }

    private fun getTimestamp(): String = sdf.format(Date())
}
