package io.nndroid.autospeaker

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val TAG = "AutoSpeaker"
    private const val FILE_NAME = "autospeaker.log"
    private const val MAX_BYTES = 256 * 1024L
    private const val MAX_UI_LINES = 300
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun i(context: Context, area: String, message: String) = write(context, "I", area, message, null)
    fun w(context: Context, area: String, message: String) = write(context, "W", area, message, null)
    fun e(context: Context, area: String, message: String, error: Throwable? = null) = write(context, "E", area, message, error)

    private fun write(context: Context, level: String, area: String, message: String, error: Throwable?) {
        val line = synchronized(lock) {
            val ts = formatter.format(Date())
            buildString {
                append(ts).append(' ').append(level).append('/').append(area).append(": ").append(message)
                if (error != null) append(" | ").append(error.javaClass.simpleName).append(": ").append(error.message)
                append('\n')
            }
        }

        when (level) {
            "E" -> Log.e(TAG, "[$area] $message", error)
            "W" -> Log.w(TAG, "[$area] $message", error)
            else -> Log.i(TAG, "[$area] $message")
        }

        runCatching {
            synchronized(lock) {
                val file = File(context.applicationContext.filesDir, FILE_NAME)
                if (file.exists() && file.length() >= MAX_BYTES) {
                    val old = File(context.applicationContext.filesDir, "$FILE_NAME.1")
                    if (old.exists()) old.delete()
                    file.renameTo(old)
                }
                file.appendText(line)
            }
        }
    }

    fun read(context: Context): String = runCatching {
        synchronized(lock) {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            if (!file.exists()) return@synchronized "暂无日志"
            val lines = file.readLines()
            lines.takeLast(MAX_UI_LINES).joinToString("\n")
        }
    }.getOrElse { "读取日志失败：${it.javaClass.simpleName}: ${it.message}" }

    fun clear(context: Context) {
        runCatching {
            synchronized(lock) {
                File(context.applicationContext.filesDir, FILE_NAME).delete()
                File(context.applicationContext.filesDir, "$FILE_NAME.1").delete()
            }
        }
    }
}
