package io.nndroid.autospeaker

import android.content.Context
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.TimeUnit

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
        withTimeout(DUMP_TIMEOUT_MS) { legacyUiautomatorDump() } ?: "ERR:dump timed out"

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

    override fun startTouchCapture(): Boolean {
        val error = TouchCapture.start()
        if (error.isNotBlank()) android.util.Log.w("AutoSpeaker", "touch capture start failed: $error")
        return error.isBlank()
    }

    override fun pollTouchCapture(): String = TouchCapture.poll()

    override fun stopTouchCapture() {
        TouchCapture.stop()
    }

    private fun <T> withTimeout(timeoutMs: Long, block: () -> T): T? {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit(java.util.concurrent.Callable { block() })
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
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
        private const val TAP_TIMEOUT_MS = 6_000L
        private const val TAP_WAIT_MS = 5L
        private const val WINDOW_TIMEOUT_MS = 4_000L
        private const val WINDOW_WAIT_MS = 3L
        private val CURRENT_WINDOW_REGEX = Regex("mCurrentFocus=Window\\{[^}]*\\s(\\S+)/")
    }
}

/**
 * Reads raw touchscreen events so the app can learn the speaker button coordinates from the
 * user's own manual tap — no accessibility service, no window dump. Runs in the daemon
 * process (root/shell), which is allowed to read /dev/input.
 */
object TouchCapture {
    @Volatile private var reader: Process? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var tapCount = 0
    @Volatile private var lastX = -1
    @Volatile private var lastY = -1

    /** Returns "" on success or the failure reason. */
    fun start(): String {
        if (reader != null) {
            // Already running from an earlier call; restart the tap bookkeeping.
            tapCount = 0
            lastX = -1
            lastY = -1
            return ""
        }
        val device = findTouchDevice() ?: return "no touchscreen input device found"
        val screen = screenSize() ?: return "screen size unavailable"
        val process = runCatching {
            ProcessBuilder("getevent", "-q", device.first)
                .redirectErrorStream(true)
                .start()
        }.getOrElse { return "getevent failed: ${it.message}" }

        reader = process
        tapCount = 0
        lastX = -1
        lastY = -1
        val (screenW, screenH) = screen
        val (xMax, yMax) = device.second to device.third
        thread = Thread {
            var pending = false
            var rawX = -1
            var rawY = -1
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 3) return@forEachLine
                    val type = parts[0].toIntOrNull(16) ?: return@forEachLine
                    val code = parts[1].toIntOrNull(16) ?: return@forEachLine
                    val value = parts[2].toLongOrNull(16) ?: return@forEachLine
                    when {
                        type == 1 && code == 0x014a -> { // BTN_TOUCH
                            pending = value == 1L
                            if (!pending) {
                                rawX = -1
                                rawY = -1
                            }
                        }
                        type == 3 && code == 0x035 -> rawX = value.toInt() // ABS_MT_POSITION_X
                        type == 3 && code == 0x036 -> rawY = value.toInt() // ABS_MT_POSITION_Y
                        type == 0 && code == 0 -> { // SYN_REPORT
                            if (pending && rawX >= 0 && rawY >= 0) {
                                lastX = ((rawX.toLong() * screenW) / xMax).toInt()
                                lastY = ((rawY.toLong() * screenH) / yMax).toInt()
                                tapCount++
                                pending = false
                                rawX = -1
                                rawY = -1
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                // Stream ended or process killed — stop() handles state.
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
        return ""
    }

    /** "<tapCount>:<screenX>,<screenY>" of the latest tap. */
    fun poll(): String = "$tapCount:$lastX,$lastY"

    fun stop() {
        runCatching { reader?.destroy() }
        reader = null
        thread = null
    }

    /** Returns device path plus the ABS_MT x/y maxima used to map raw to screen coordinates. */
    private fun findTouchDevice(): Triple<String, Int, Int>? = runCatching {
        val process = ProcessBuilder("getevent", "-p")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(3, TimeUnit.SECONDS)

        var path = ""
        var xMax = -1
        var yMax = -1
        var hasBtnTouch = false
        var best: Triple<String, Int, Int>? = null
        for (line in output.lines()) {
            Regex("add device \\d+:\\s*(/dev/input/\\S+)").find(line)?.let {
                if (best == null && xMax > 0 && yMax > 0 && hasBtnTouch) best = Triple(path, xMax, yMax)
                path = it.groupValues[1]
                xMax = -1
                yMax = -1
                hasBtnTouch = false
                return@let
            }
            Regex("ABS_MT_POSITION_X\\s*:.*max\\s+(\\d+)").find(line)?.let { xMax = it.groupValues[1].toInt() }
            Regex("ABS_MT_POSITION_Y\\s*:.*max\\s+(\\d+)").find(line)?.let { yMax = it.groupValues[1].toInt() }
            if (line.contains("BTN_TOUCH")) hasBtnTouch = true
        }
        if (best == null && xMax > 0 && yMax > 0 && hasBtnTouch) best = Triple(path, xMax, yMax)
        best
    }.getOrNull()

    private fun screenSize(): Pair<Int, Int>? = runCatching {
        val process = ProcessBuilder("wm", "size")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(3, TimeUnit.SECONDS)
        // Prefer an override size (split screen / resolution changes) over the physical one.
        (Regex("Override size:\\s*(\\d+)x(\\d+)").find(output)
            ?: Regex("Physical size:\\s*(\\d+)x(\\d+)").find(output))
            ?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    }.getOrNull()
}
