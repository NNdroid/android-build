package io.nndroid.autospeaker

import android.content.Context
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Keep
class ShizukuAudioService() : IPrivilegedAudioService.Stub() {
    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    override fun setSpeakerphone(enabled: Boolean): Boolean =
        PrivilegedAudioRouter.setSpeakerphone(enabled)

    override fun isSpeakerphoneOn(): Boolean =
        PrivilegedAudioRouter.isSpeakerphoneOn()

    override fun getLastError(): String = PrivilegedAudioRouter.lastError

    override fun dumpUi(): String =
        withTimeout(DUMP_TIMEOUT_MS) {
            // Fast path: an instrumentation that grabs the tree immediately. `uiautomator
            // dump` waits for UI idle, which never happens on animated in-call screens.
            runCatching { fastInstrumentationDump() }.getOrNull()
                ?.takeIf { it.startsWith("OK:") }
                ?: legacyUiautomatorDump()
        } ?: "ERR:dump timed out"

    private fun fastInstrumentationDump(): String {
        val outFile = File("/data/data/${BuildConfig.APPLICATION_ID}/files/${DumpInstrumentation.DUMP_FILE}")
        outFile.delete()
        val process = ProcessBuilder(
            "am", "instrument", "-w",
            "${BuildConfig.APPLICATION_ID}/${DumpInstrumentation::class.java.name}"
        ).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText()
        if (!process.waitFor(FAST_WAIT_SEC, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return "ERR:fast dump instrumentation timed out"
        }
        if (!outFile.exists()) return "ERR:fast dump produced no file (app data unreadable from this uid?)"
        val xml = outFile.readText()
        if (xml.startsWith("<ERROR")) {
            return "ERR:fast dump failed: ${xml.removePrefix("<ERROR:").removeSuffix(">")}"
        }
        if (!xml.contains("<node")) return "ERR:fast dump empty"
        return "OK:$xml"
    }

    private fun legacyUiautomatorDump(): String {
        val out = File(UI_DUMP_PATH)
        out.delete()
        val process = ProcessBuilder("uiautomator", "dump", out.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(DUMP_WAIT_MS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return "ERR:uiautomator dump timed out"
        }
        if (process.exitValue() != 0 || !out.exists()) {
            return "ERR:uiautomator dump failed exit=${process.exitValue()} output=$output"
        }
        return "OK:" + out.readText()
    }

    override fun inputTap(x: Int, y: Int): Boolean =
        withTimeout(TAP_TIMEOUT_MS) {
            val process = ProcessBuilder("input", "tap", x.toString(), y.toString())
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().readText()
            if (!process.waitFor(TAP_WAIT_MS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@withTimeout false
            }
            process.exitValue() == 0
        } ?: false

    override fun currentWindowPackage(): String =
        withTimeout(WINDOW_TIMEOUT_MS) {
            val process = ProcessBuilder("dumpsys", "window", "windows")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(WINDOW_WAIT_MS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@withTimeout ""
            }
            // mCurrentFocus=Window{7c4f2b u0 com.vivo.phone/com.vivo.phone.call.InCallActivity}
            CURRENT_WINDOW_REGEX.find(output)?.groupValues?.get(1).orEmpty()
        } ?: ""

    private fun <T> withTimeout(timeoutMs: Long, block: () -> T): T? {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit(Callable { block() })
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                null
            }
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        private const val UI_DUMP_PATH = "/data/local/tmp/autospeaker_uidump.xml"
        private const val DUMP_TIMEOUT_MS = 13_000L
        private const val DUMP_WAIT_MS = 8L
        private const val FAST_WAIT_SEC = 4L
        private const val TAP_TIMEOUT_MS = 6_000L
        private const val TAP_WAIT_MS = 5L
        private const val WINDOW_TIMEOUT_MS = 4_000L
        private const val WINDOW_WAIT_MS = 3L
        private val CURRENT_WINDOW_REGEX = Regex("mCurrentFocus=Window\\{[^}]*\\s(\\S+)/")
    }
}
