package com.romaster.launcher.data

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Lightweight file logger for on-device debugging (no PC required).
 *
 * Switch: [ENABLED] = true → write logs under Documents/romaster_logs (or app files fallback).
 *          [ENABLED] = false → no files created, no-op.
 */
object FileLogger {

    /** Master switch: 1/true = log to disk, 0/false = disabled */
    @JvmField
    var ENABLED: Boolean = true

    private val io = Executors.newSingleThreadExecutor()
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    @Volatile
    private var logDir: File? = null

    fun init(context: Context) {
        if (!ENABLED) return
        io.execute {
            try {
                val dir = resolveDir(context)
                if (!dir.exists()) dir.mkdirs()
                logDir = dir
                // Session marker
                appendLine(dir, "----- session ${timeFmt.format(Date())} pkg=${context.packageName} -----")
            } catch (e: Exception) {
                try {
                    android.util.Log.e("RomasterFileLogger", "init failed", e)
                } catch (_: Exception) {}
            }
        }
    }

    private fun resolveDir(context: Context): File {
        // Prefer public Documents/romaster_logs as requested
        try {
            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val preferred = File(docs, "romaster_logs")
            if (!preferred.exists()) preferred.mkdirs()
            if (preferred.exists() && preferred.canWrite()) return preferred
            // probe write
            val probe = File(preferred, ".probe")
            probe.writeText("ok")
            probe.delete()
            return preferred
        } catch (_: Exception) {}
        // Fallback: app-specific external dir (always writable, no permission)
        val fallback = File(context.getExternalFilesDir(null), "romaster_logs")
        if (!fallback.exists()) fallback.mkdirs()
        return fallback
    }

    fun log(tag: String, message: String, t: Throwable? = null) {
        if (!ENABLED) return
        val line = buildString {
            append(timeFmt.format(Date()))
            append(" [")
            append(tag)
            append("] ")
            append(message)
            if (t != null) {
                append(" | ")
                append(t.javaClass.simpleName)
                append(": ")
                append(t.message ?: "")
            }
        }
        try {
            android.util.Log.d("Romaster/$tag", message, t)
        } catch (_: Exception) {}
        io.execute {
            try {
                val dir = logDir
                if (dir != null) appendLine(dir, line)
            } catch (_: Exception) {}
        }
    }

    fun w(tag: String, message: String, t: Throwable? = null) = log(tag, "W $message", t)
    fun e(tag: String, message: String, t: Throwable? = null) = log(tag, "E $message", t)

    private fun appendLine(dir: File, line: String) {
        val file = File(dir, "romaster_${dayFmt.format(Date())}.log")
        file.appendText(line + "\n")
    }
}
