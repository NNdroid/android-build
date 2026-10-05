package io.nndroid.autospeaker

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object RootBackend {
    private const val READ_TIMEOUT_MS = 6000L
    private const val HOLD_MS = 10_000L
    private const val HOLD_CLEANUP_DELAY_MS = HOLD_MS + 4000

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile var lastError: String = ""
        private set

    // The root app_process stays alive after enabling so its per-client audio request
    // is not cleaned up on binder death (see RootAudioProcess).
    @Volatile private var holdProcess: Process? = null

    fun setSpeaker(context: Context, enabled: Boolean, callback: (Boolean, String) -> Unit) {
        AppLog.i(context, "Root", "starting su/app_process speaker enabled=$enabled")
        Thread {
            if (enabled) destroyHoldProcess()

            var process: Process? = null
            val result = runCatching {
                val apk = shellQuote(context.applicationInfo.sourceDir)
                val state = if (enabled) "on" else "off"
                val command = "CLASSPATH=$apk app_process /system/bin io.nndroid.autospeaker.RootAudioProcess $state"
                val proc = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                process = proc
                if (enabled) readEnableResult(proc) else readDisableResult(proc)
            }.getOrElse {
                false to "${it.javaClass.simpleName}: ${it.message}"
            }

            if (result.first && enabled) {
                process?.let { proc ->
                    holdProcess = proc
                    mainHandler.postDelayed({
                        if (holdProcess === proc) destroyHoldProcess()
                    }, HOLD_CLEANUP_DELAY_MS)
                }
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

    fun destroyHoldProcess() {
        holdProcess?.let { runCatching { it.destroyForcibly() } }
        holdProcess = null
    }

    /**
     * Reads until "OK"/"ERROR" instead of EOF, because the root process intentionally
     * stays alive after a successful enable.
     */
    private fun readEnableResult(process: Process): Pair<Boolean, String> {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit(Callable {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var firstLine = ""
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.contains("OK") -> return@Callable true to ""
                        line.startsWith("ERROR") -> return@Callable false to line.removePrefix("ERROR:")
                        else -> if (firstLine.isBlank()) firstLine = line
                    }
                }
                false to firstLine.ifBlank { "root process exited without OK" }
            })
            try {
                future.get(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                process.destroyForcibly()
                false to "root command timed out after ${READ_TIMEOUT_MS}ms"
            } catch (e: Exception) {
                process.destroyForcibly()
                false to "${e.javaClass.simpleName}: ${e.message}"
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun readDisableResult(process: Process): Pair<Boolean, String> {
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }.trim()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        return when {
            !finished -> {
                process.destroyForcibly()
                false to "root command timed out"
            }
            process.exitValue() == 0 && output.contains("OK") -> true to ""
            else -> false to output.ifBlank { "su/app_process failed (${process.exitValue()})" }
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
