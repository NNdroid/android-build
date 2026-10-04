package io.nndroid.autospeaker

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object RootBackend {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile var lastError: String = ""
        private set

    fun setSpeaker(context: Context, enabled: Boolean, callback: (Boolean, String) -> Unit) {
        AppLog.i(context, "Root", "starting su/app_process speaker enabled=$enabled")
        Thread {
            val result = runCatching {
                val apk = shellQuote(context.applicationInfo.sourceDir)
                val state = if (enabled) "on" else "off"
                val command = "CLASSPATH=$apk app_process /system/bin io.nndroid.autospeaker.RootAudioProcess $state"
                val process = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }.trim()
                val finished = process.waitFor(5, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    false to "root command timed out"
                } else if (process.exitValue() == 0 && output.contains("OK")) {
                    true to ""
                } else {
                    false to output.ifBlank { "su/app_process failed (${process.exitValue()})" }
                }
            }.getOrElse {
                false to "${it.javaClass.simpleName}: ${it.message}"
            }

            if (!result.first) {
                lastError = result.second
                AppLog.w(context, "Root", "route failed: ${result.second}")
            } else {
                lastError = ""
                AppLog.i(context, "Root", "speaker route succeeded")
            }
            mainHandler.post { callback(result.first, result.second) }
        }.start()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
