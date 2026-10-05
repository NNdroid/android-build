package io.nndroid.autospeaker

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executor

object SpeakerController {
    private const val TAG = "Router"

    // Waiting for the MODE_IN_CALL event before starting the chain anyway.
    private const val MODE_WAIT_TIMEOUT_MS = 2500L
    // Event-driven window after the chain starts; retries and the reset watch stay inside it.
    private const val ROUTE_WINDOW_MS = 5000L
    private const val TICKER_INTERVAL_MS = 400L
    private const val RETRY_INTERVAL_MS = 1000L
    private const val RETRY_DEBOUNCE_MS = 350L
    private const val VERIFY_DELAY_MS = 450L
    private const val MAX_AUDIO_ATTEMPTS = 3
    private const val ACCESSIBILITY_HANDOFF_WAIT_MS = 2200L
    private const val UI_TAP_VERIFY_DELAY_MS = 700L
    // How long to wait for the user's manual speaker tap before moving on to audio backends.
    private const val LEARN_WINDOW_MS = 12_000L

    private enum class Phase { IDLE, WATCHING, HANDOFF, DONE }

    private enum class Backend { ACCESSIBILITY, MODE_OWNER, SHIZUKU_UI, SHIZUKU_AUDIO, ROOT_AUDIO }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { mainHandler.post(it) }

    @Volatile private var phase = Phase.IDLE
    @Volatile private var appContext: Context? = null
    private var audio: AudioManager? = null
    private var audioAttempts = 0
    private var lastAttemptElapsed = Long.MIN_VALUE
    private var chainStartElapsed = 0L
    private var chainStarted = false
    @Volatile private var routeEstablished = false
    @Volatile private var retryPosted = false
    private var privilegedBackendIndex = 0
    @Volatile private var rootAudioUsed = false
    @Volatile private var resetObserved = false
    @Volatile private var modeOwnerUsed = false
    @Volatile private var touchCaptureStarted = false

    // Cached uiautomator dump for the current call (main-thread confined). Pre-warmed at
    // handoff start so the slow dump overlaps the rest of the chain. Token guards against a
    // stale in-flight dump from the previous call polluting this call's cache.
    private var uiDumpCache: String? = null
    private var uiDumpInFlight = false
    private var uiDumpToken = 0L
    private val uiDumpWaiters = mutableListOf<(String?) -> Unit>()

    private val modeWaitRunnable = Runnable {
        if (phase == Phase.WATCHING && !chainStarted) {
            context()?.let {
                AppLog.w(it, TAG, "MODE_IN_CALL did not arrive within ${MODE_WAIT_TIMEOUT_MS}ms; starting route chain anyway")
            }
            startRouteChain()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (phase != Phase.WATCHING) return
            val elapsed = SystemClock.elapsedRealtime() - chainStartElapsed

            val speakerNow = routeIsSpeaker()
            if (speakerNow && !routeEstablished) {
                routeEstablished = true
                markSuccess("AudioManager")
                context()?.let { AppLog.i(it, TAG, "speaker route established; watching until window end") }
            } else if (!speakerNow && routeEstablished) {
                routeEstablished = false
                resetObserved = true
                context()?.let { AppLog.w(it, TAG, "established speaker route was reset; retrying within window") }
            }

            // Once the mode owner has provably reset the route, further app-process attempts
            // are almost always no-ops; keep at most one retry and hand off sooner.
            val attemptCap = if (resetObserved) 2 else MAX_AUDIO_ATTEMPTS
            val handoffNow = when {
                elapsed >= ROUTE_WINDOW_MS -> !routeEstablished
                !routeEstablished && audioAttempts >= attemptCap &&
                    elapsed - lastAttemptElapsed >= VERIFY_DELAY_MS -> true
                else -> false
            }
            if (handoffNow) {
                startPrivilegedHandoff()
                return
            }

            if (!routeEstablished && audioAttempts < attemptCap &&
                elapsed - lastAttemptElapsed >= RETRY_INTERVAL_MS
            ) {
                attemptAudioRoute("window-retry")
            }

            mainHandler.postDelayed(this, TICKER_INTERVAL_MS)
        }
    }

    private val modeChangedListener = AudioManager.OnModeChangedListener { mode ->
        if (mode == AudioManager.MODE_IN_CALL && phase == Phase.WATCHING && !chainStarted) {
            context()?.let { AppLog.i(it, TAG, "MODE_IN_CALL observed; starting route chain") }
            startRouteChain()
        }
    }

    private val commDeviceListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        if (phase != Phase.WATCHING) return@OnCommunicationDeviceChangedListener
        val ctx = context() ?: return@OnCommunicationDeviceChangedListener
        when {
            device != null && device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> {
                if (!routeEstablished) {
                    routeEstablished = true
                    markSuccess("AudioManager")
                    AppLog.i(ctx, TAG, "communication device event: speaker")
                }
            }
            isExternalUserRoute(device) -> {
                AppLog.i(ctx, TAG, "external call audio device connected device=${describeDevice(device)}; stopping auto route")
                CallState.lastBackend = "保持外部音频设备"
                CallState.lastError = "检测到外部通话音频设备，停止自动切换"
                finishChain("external audio device selected")
            }
            else -> {
                AppLog.w(ctx, TAG, "communication device event: route moved away from speaker device=${describeDevice(device)}")
                routeEstablished = false
                resetObserved = true
                // The app-process path has provably lost; start the slow window dump now so
                // it overlaps the rest of the chain instead of starting at handoff.
                if (ShizukuBridge.isReady()) prewarmUiDump(ctx)
                val attemptCap = if (resetObserved) 2 else MAX_AUDIO_ATTEMPTS
                if (audioAttempts < attemptCap && !retryPosted) {
                    retryPosted = true
                    mainHandler.postDelayed({
                        retryPosted = false
                        if (phase == Phase.WATCHING && !routeEstablished && audioAttempts < attemptCap) {
                            attemptAudioRoute("event-retry")
                        }
                    }, RETRY_DEBOUNCE_MS)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Entry points
    // ---------------------------------------------------------------------

    fun onIncomingCallAnswered(context: Context) {
        val snapshot = CallState.current
        if (!snapshot.active) return
        if (!CallState.markSpeakerAttempted()) {
            AppLog.i(context, TAG, "skip route chain active=${snapshot.active} attempted=${snapshot.speakerAttempted}")
            return
        }

        mainHandler.removeCallbacksAndMessages(null)
        appContext = context.applicationContext
        audio = context.getSystemService(AudioManager::class.java)
        phase = Phase.WATCHING
        chainStarted = false
        routeEstablished = false
        retryPosted = false
        audioAttempts = 0
        lastAttemptElapsed = Long.MIN_VALUE
        privilegedBackendIndex = 0
        rootAudioUsed = false
        resetObserved = false
        modeOwnerUsed = false
        uiDumpCache = null
        uiDumpInFlight = false
        uiDumpToken = 0L
        uiDumpWaiters.clear()
        CallState.lastBackend = "AudioManager"
        CallState.lastError = ""
        AppLog.i(context, TAG, "starting event-driven route chain mode=${SettingsStore.routeMode(context)}")

        registerRouteListeners()

        val currentMode = runCatching { audio?.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        if (currentMode == AudioManager.MODE_IN_CALL) {
            startRouteChain()
        } else {
            AppLog.i(context, TAG, "waiting for MODE_IN_CALL (current mode=$currentMode)")
            mainHandler.postDelayed(modeWaitRunnable, MODE_WAIT_TIMEOUT_MS)
        }
    }

    fun onCallEnded(context: Context) {
        val wasActive = phase == Phase.WATCHING || phase == Phase.HANDOFF
        phase = Phase.DONE
        mainHandler.removeCallbacksAndMessages(null)
        unregisterRouteListeners()
        stopTouchCaptureIfNeeded()
        if (wasActive) AppLog.i(context, TAG, "route chain stopped: call ended")

        RootBackend.destroyHoldProcess()
        if (rootAudioUsed) {
            rootAudioUsed = false
            AppLog.i(context, TAG, "cleaning up root speaker force after call end")
            RootBackend.setSpeaker(context, false) { _, error ->
                if (error.isNotBlank()) AppLog.w(context, TAG, "root off cleanup failed: $error")
            }
        }
        if (modeOwnerUsed) {
            modeOwnerUsed = false
            audio?.let { a ->
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) a.clearCommunicationDevice()
                }
                runCatching { a.mode = AudioManager.MODE_NORMAL }
                AppLog.i(context, TAG, "mode-owner cleanup: communication device cleared, mode restored to normal")
            }
        }
    }

    // ---------------------------------------------------------------------
    // Watching phase: event-driven audio route attempts
    // ---------------------------------------------------------------------

    private fun startRouteChain() {
        if (chainStarted || phase != Phase.WATCHING) return
        chainStarted = true
        chainStartElapsed = SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(modeWaitRunnable)
        context()?.let {
            AppLog.i(it, TAG, "route chain started windowMs=$ROUTE_WINDOW_MS maxAudioAttempts=$MAX_AUDIO_ATTEMPTS")
        }
        attemptAudioRoute("initial")
        mainHandler.postDelayed(ticker, TICKER_INTERVAL_MS)
    }

    private fun registerRouteListeners() {
        val a = audio ?: return
        runCatching { a.addOnModeChangedListener(mainExecutor, modeChangedListener) }
            .onFailure { context()?.let { ctx -> AppLog.e(ctx, TAG, "OnModeChangedListener unavailable", it) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { a.addOnCommunicationDeviceChangedListener(mainExecutor, commDeviceListener) }
                .onFailure { context()?.let { ctx -> AppLog.e(ctx, TAG, "OnCommunicationDeviceChangedListener unavailable", it) } }
        }
    }

    private fun unregisterRouteListeners() {
        val a = audio ?: return
        runCatching { a.removeOnModeChangedListener(modeChangedListener) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { a.removeOnCommunicationDeviceChangedListener(commDeviceListener) }
        }
    }

    private fun attemptAudioRoute(tag: String) {
        val a = audio ?: return
        if (phase != Phase.WATCHING || audioAttempts >= MAX_AUDIO_ATTEMPTS) return
        audioAttempts++
        lastAttemptElapsed = SystemClock.elapsedRealtime() - chainStartElapsed
        context()?.let {
            AppLog.i(it, TAG, "audio route attempt $audioAttempts/$MAX_AUDIO_ATTEMPTS ($tag)")
        }
        requestPublicSpeakerRoute(context() ?: return, a, "$tag#${audioAttempts}")
    }

    private fun startPrivilegedHandoff() {
        if (phase != Phase.WATCHING) return
        phase = Phase.HANDOFF
        mainHandler.removeCallbacks(ticker)
        mainHandler.removeCallbacks(modeWaitRunnable)
        unregisterRouteListeners()
        val ctx = context() ?: run {
            phase = Phase.DONE
            return
        }
        AppLog.i(ctx, TAG, "audio route attempts finished (resetObserved=$resetObserved); handing off to privileged backends mode=${SettingsStore.routeMode(ctx)}")
        if (ShizukuBridge.isReady()) prewarmUiDump(ctx)
        privilegedBackendIndex = 0
        runNextPrivilegedBackend()
    }

    // ---------------------------------------------------------------------
    // Handoff phase: privileged backends, tried in mode-dependent order
    // ---------------------------------------------------------------------

    private fun runNextPrivilegedBackend() {
        val ctx = context() ?: return
        if (phase != Phase.HANDOFF) return
        val chain = when (SettingsStore.routeMode(ctx)) {
            SettingsStore.MODE_ACCESSIBILITY -> listOf(
                Backend.ACCESSIBILITY, Backend.MODE_OWNER, Backend.SHIZUKU_UI, Backend.SHIZUKU_AUDIO, Backend.ROOT_AUDIO
            )
            else -> listOf(Backend.MODE_OWNER, Backend.SHIZUKU_UI, Backend.SHIZUKU_AUDIO, Backend.ROOT_AUDIO)
        }

        while (privilegedBackendIndex < chain.size) {
            val backend = chain[privilegedBackendIndex++]
            val started = when (backend) {
                Backend.ACCESSIBILITY -> runAccessibilityBackend(ctx) { runNextPrivilegedBackend() }
                Backend.MODE_OWNER -> runModeOwnerBackend(ctx) { runNextPrivilegedBackend() }
                Backend.SHIZUKU_UI -> runShizukuUiBackend(ctx) { runNextPrivilegedBackend() }
                Backend.SHIZUKU_AUDIO -> runShizukuAudioBackend(ctx) { runNextPrivilegedBackend() }
                Backend.ROOT_AUDIO -> runRootAudioBackend(ctx) { runNextPrivilegedBackend() }
            }
            if (started) return
        }

        AppLog.w(ctx, TAG, "all privileged backends exhausted mode=${SettingsStore.routeMode(ctx)}")
        CallState.lastError = CallState.lastError.ifBlank { "所有后端均未能切到扬声器" }
        finishChain("backends exhausted")
    }

    private fun runModeOwnerBackend(ctx: Context, next: () -> Unit): Boolean {
        val a = audio ?: return false
        modeOwnerUsed = true
        runCatching { a.mode = AudioManager.MODE_IN_COMMUNICATION }
        val modeAfter = runCatching { a.mode }.getOrDefault(-1)
        if (modeAfter != AudioManager.MODE_IN_COMMUNICATION) {
            // Some ROMs (MIUI observed) silently reject third-party mode changes while a GSM
            // call holds MODE_IN_CALL — bail out immediately instead of burning a verify cycle.
            CallState.lastError = "Mode 接管被 ROM 拒绝 (mode=$modeAfter)"
            AppLog.w(ctx, TAG, "mode takeover rejected by the ROM (mode after=$modeAfter); skipping mode-owner backend")
            next()
            return true
        }
        AppLog.i(ctx, TAG, "mode-owner backend: MODE_IN_COMMUNICATION taken; requesting speaker")
        requestPublicSpeakerRoute(ctx, a, "mode-owner")
        mainHandler.postDelayed({
            if (phase != Phase.HANDOFF || !CallState.current.active) return@postDelayed
            if (isSpeakerActuallyActive(ctx, source = "ModeOwner")) {
                markSuccess("Mode 接管")
                finishChain("mode-owner backend succeeded")
            } else {
                CallState.lastError = "Mode 接管后路由仍不是扬声器"
                AppLog.w(ctx, TAG, "mode-owner backend did not establish speaker; continuing fallback chain")
                next()
            }
        }, VERIFY_DELAY_MS)
        return true
    }

    private fun runAccessibilityBackend(ctx: Context, next: () -> Unit): Boolean {
        if (!SpeakerAccessibilityService.isConnected()) {
            AppLog.w(ctx, TAG, "accessibility service not connected; skipping backend")
            return false
        }
        CallState.requestAccessibilityFallback()
        AppLog.i(ctx, TAG, "accessibility backend: requesting speaker clicks")
        SpeakerAccessibilityService.requestSpeakerClick()
        mainHandler.postDelayed({
            if (phase != Phase.HANDOFF || !CallState.current.active) return@postDelayed
            if (isSpeakerActuallyActive(ctx, source = "Accessibility-handoff")) {
                markSuccess("无障碍")
                finishChain("accessibility backend succeeded")
            } else {
                CallState.clearAccessibilityFallback()
                CallState.lastError = "无障碍点击后路由仍不是扬声器"
                next()
            }
        }, ACCESSIBILITY_HANDOFF_WAIT_MS)
        return true
    }

    private fun runShizukuUiBackend(ctx: Context, next: () -> Unit): Boolean {
        if (!ShizukuBridge.isReady()) {
            AppLog.w(ctx, TAG, "Shizuku not ready; skipping UI backend")
            return false
        }
        val learned = SpeakerAccessibilityService.loadFingerprint(ctx)
        if (learned != null) {
            tryShizukuUiClick(ctx) { ok, error ->
                if (phase != Phase.HANDOFF || !CallState.current.active) return@tryShizukuUiClick
                if (ok) {
                    markSuccess("Shizuku UI 点击")
                    finishChain("shizuku ui backend succeeded")
                } else {
                    CallState.lastError = "Shizuku UI: $error"
                    next()
                }
            }
            return true
        }

        // No fingerprint yet: try the window dump and, in parallel, learn from the user's own
        // manual speaker tap (read from /dev/input by the daemon — no accessibility involved).
        AppLog.i(ctx, TAG, "Shizuku UI backend: no learned fingerprint; arming tap learning plus window dump")
        ShizukuBridge.startTouchCapture(ctx) { started ->
            if (started) {
                touchCaptureStarted = true
                AppLog.i(ctx, TAG, "touch capture armed: tap the speaker button manually once and the app will learn it")
                startTapPolling(ctx)
            } else {
                AppLog.w(ctx, TAG, "touch capture unavailable; relying on the window dump only")
            }
        }
        dumpAndTap(ctx, null) { ok, error ->
            if (phase != Phase.HANDOFF || !CallState.current.active) return@dumpAndTap
            if (ok) {
                markSuccess("Shizuku UI 点击")
                finishChain("shizuku ui backend succeeded")
                return@dumpAndTap
            }
            CallState.lastError = "Shizuku UI: $error"
            // Give the manual-tap learner a window before moving on to the audio backends.
            mainHandler.postDelayed({
                if (phase != Phase.HANDOFF || !CallState.current.active) return@postDelayed
                AppLog.w(ctx, TAG, "no tap learned within ${LEARN_WINDOW_MS}ms; continuing fallback chain")
                next()
            }, LEARN_WINDOW_MS)
        }
        return true
    }

    private fun startTapPolling(ctx: Context) {
        var lastSeen = 0
        val poll = object : Runnable {
            override fun run() {
                if (phase != Phase.HANDOFF || !CallState.current.active) return
                ShizukuBridge.pollTouchCapture(ctx) { result ->
                    if (phase != Phase.HANDOFF || !CallState.current.active) return@pollTouchCapture
                    val parts = result.split(":")
                    val count = parts.getOrNull(0)?.toIntOrNull() ?: 0
                    val coords = parts.getOrNull(1)?.split(",")?.mapNotNull { it.toIntOrNull() }
                    if (count > lastSeen && coords != null && coords.size == 2 && coords[0] >= 0 && coords[1] >= 0) {
                        lastSeen = count
                        val (x, y) = coords
                        AppLog.i(ctx, TAG, "manual tap captured at $x,$y; verifying whether it enabled the speaker")
                        mainHandler.postDelayed({
                            if (phase != Phase.HANDOFF || !CallState.current.active) return@postDelayed
                            if (isSpeakerActuallyActive(ctx, source = "TapLearn")) {
                                saveTapFingerprint(ctx, x, y)
                                markSuccess("手动点击（已学习）")
                                finishChain("manual speaker tap verified and learned")
                            }
                        }, UI_TAP_VERIFY_DELAY_MS)
                    }
                    mainHandler.postDelayed(this, 900)
                }
            }
        }
        mainHandler.postDelayed(poll, 1200)
    }

    private fun saveTapFingerprint(ctx: Context, x: Int, y: Int) {
        val existing = SpeakerAccessibilityService.loadFingerprint(ctx)
        if (existing != null && !SpeakerAccessibilityService.isAutoFingerprint(ctx)) return
        val dm = ctx.resources.displayMetrics
        fun norm(value: Int, max: Int) = if (max <= 0) 0 else ((value.toLong() * 10000L) / max).toInt()
        val fp = SpeakerAccessibilityService.Fingerprint(
            pkg = "",
            viewId = "",
            desc = "",
            className = "",
            cx = norm(x, dm.widthPixels),
            cy = norm(y, dm.heightPixels),
            w = 0,
            h = 0
        )
        SpeakerAccessibilityService.saveFingerprint(ctx, fp, auto = true)
        AppLog.i(ctx, TAG, "speaker control learned from manual tap at $x,$y; next calls use the fast path")
    }

    private fun stopTouchCaptureIfNeeded() {
        if (!touchCaptureStarted) return
        touchCaptureStarted = false
        context()?.let { ShizukuBridge.stopTouchCapture(it) }
    }

    private fun runShizukuAudioBackend(ctx: Context, next: () -> Unit): Boolean {
        if (!ShizukuBridge.isReady()) {
            AppLog.w(ctx, TAG, "Shizuku not ready; skipping audio backend")
            return false
        }
        AppLog.i(ctx, TAG, "Shizuku audio backend: requesting privileged speaker route")
        ShizukuBridge.setSpeaker(ctx, true) { ok, error ->
            if (phase != Phase.HANDOFF || !CallState.current.active) return@setSpeaker
            if (!ok) {
                CallState.lastError = error
                next()
                return@setSpeaker
            }
            verifyAfterBackend(ctx, "Shizuku", next)
        }
        return true
    }

    private fun runRootAudioBackend(ctx: Context, next: () -> Unit): Boolean {
        rootAudioUsed = true
        AppLog.i(ctx, TAG, "Root audio backend: requesting su/app_process speaker route")
        RootBackend.setSpeaker(ctx, true) { ok, error ->
            if (phase != Phase.HANDOFF || !CallState.current.active) return@setSpeaker
            if (!ok) {
                CallState.lastError = error
                next()
                return@setSpeaker
            }
            verifyAfterBackend(ctx, "Root", next)
        }
        return true
    }

    private fun verifyAfterBackend(ctx: Context, backend: String, onFailure: () -> Unit) {
        mainHandler.postDelayed({
            if (phase != Phase.HANDOFF || !CallState.current.active) return@postDelayed
            if (isSpeakerActuallyActive(ctx, source = backend)) {
                markSuccess(backend)
                finishChain("$backend backend succeeded")
            } else {
                val error = "$backend returned success but actual route is not built-in speaker"
                CallState.lastError = error
                AppLog.w(ctx, TAG, "$error; continuing fallback chain")
                onFailure()
            }
        }, VERIFY_DELAY_MS)
    }

    private fun tryShizukuUiClick(ctx: Context, done: (Boolean, String) -> Unit) {
        val learned = SpeakerAccessibilityService.loadFingerprint(ctx)
        if (learned != null) {
            // Fast path: check the focused window (fast dumpsys query) and tap the learned
            // coordinates directly — uiautomator dump can take many seconds on animating
            // in-call screens, which is too slow for a live call.
            AppLog.i(ctx, TAG, "Shizuku UI backend: fast path with learned fingerprint")
            ShizukuBridge.currentWindowPackage(ctx) { pkg ->
                if (!chainReady()) {
                    done(false, "chain inactive")
                    return@currentWindowPackage
                }
                if (pkg.isNotBlank() && (pkg.equals(learned.pkg, ignoreCase = true) || isTrustedInCallPackage(pkg, learned))) {
                    val metrics = ctx.resources.displayMetrics
                    val x = (learned.cx * metrics.widthPixels) / 10000
                    val y = (learned.cy * metrics.heightPixels) / 10000
                    AppLog.i(ctx, TAG, "Shizuku UI backend: tapping learned coords x=$x y=$y window=$pkg")
                    tapAndVerify(ctx, x, y, "ShizukuUi-learned") { ok, err ->
                        if (ok || !chainReady()) {
                            done(ok, err)
                        } else {
                            dumpAndTap(ctx, learned, done)
                        }
                    }
                } else {
                    if (pkg.isNotBlank()) {
                        AppLog.w(ctx, TAG, "fast path skipped: foreground window '$pkg' does not match learned '${learned.pkg}'")
                    }
                    dumpAndTap(ctx, learned, done)
                }
            }
        } else {
            dumpAndTap(ctx, null, done)
        }
    }

    private fun tapAndVerify(ctx: Context, x: Int, y: Int, source: String, done: (Boolean, String) -> Unit) {
        ShizukuBridge.inputTap(ctx, x, y) { tapped ->
            if (!chainReady()) {
                done(false, "chain inactive")
                return@inputTap
            }
            if (!tapped) {
                done(false, "input tap failed")
                return@inputTap
            }
            mainHandler.postDelayed({
                if (!chainReady()) {
                    done(false, "chain inactive")
                    return@postDelayed
                }
                done(isSpeakerActuallyActive(ctx, source = source), "route still not speaker after tap")
            }, UI_TAP_VERIFY_DELAY_MS)
        }
    }

    private fun dumpAndTap(ctx: Context, learned: SpeakerAccessibilityService.Fingerprint?, done: (Boolean, String) -> Unit) {
        AppLog.i(ctx, TAG, "Shizuku UI backend: dumping in-call window learned=${learned != null}")
        requestUiDump(ctx) { xml ->
            if (!chainReady()) {
                done(false, "chain inactive")
                return@requestUiDump
            }
            val payload = xml.orEmpty()
            if (!payload.startsWith("OK:")) {
                done(false, payload.removePrefix("ERR:").ifBlank { "uiautomator dump unavailable" })
                return@requestUiDump
            }
            val metrics = ctx.resources.displayMetrics
            val control = UiDumpParser.findSpeakerControl(
                payload.removePrefix("OK:"), metrics.widthPixels, metrics.heightPixels, learned
            )
            if (control == null) {
                done(false, "speaker control not found in UI dump")
                return@requestUiDump
            }
            if (!isTrustedInCallPackage(control.pkg, learned)) {
                done(false, "dump window package '${control.pkg}' is not a call UI; tap skipped")
                return@requestUiDump
            }
            AppLog.i(
                ctx, TAG,
                "Shizuku UI backend: tapping speaker control matchedBy=${control.matchedBy} at=${control.cx},${control.cy}"
            )
            tapAndVerify(ctx, control.cx, control.cy, "ShizukuUi-dump") { ok, err ->
                if (ok && chainReady()) autoLearnFromDump(ctx, control)
                done(ok, err)
            }
        }
    }

    /**
     * The verified dump tap doubles as auto-learning: remember the control so later calls
     * take the sub-second fast path and the accessibility service is never needed. A
     * manually learned fingerprint is never overwritten.
     */
    private fun autoLearnFromDump(ctx: Context, control: UiDumpParser.Control) {
        val existing = SpeakerAccessibilityService.loadFingerprint(ctx)
        if (existing != null && !SpeakerAccessibilityService.isAutoFingerprint(ctx)) return
        val dm = ctx.resources.displayMetrics
        fun norm(value: Int, max: Int) = if (max <= 0) 0 else ((value.toLong() * 10000L) / max).toInt()
        val fp = SpeakerAccessibilityService.Fingerprint(
            pkg = control.pkg,
            viewId = control.viewId,
            desc = control.desc,
            className = control.className,
            cx = norm(control.cx, dm.widthPixels),
            cy = norm(control.cy, dm.heightPixels),
            w = norm(control.w, dm.widthPixels),
            h = norm(control.h, dm.heightPixels)
        )
        SpeakerAccessibilityService.saveFingerprint(ctx, fp, auto = true)
        AppLog.i(
            ctx, TAG,
            "speaker control auto-learned from dump package=${fp.pkg} viewId=${fp.viewId.ifBlank { "<none>" }}; next calls use the fast path"
        )
    }

    /** Every step of the UI backend must bail out once the chain or the call is gone. */
    private fun chainReady(): Boolean = phase == Phase.HANDOFF && CallState.current.active

    private fun prewarmUiDump(ctx: Context) {
        if (uiDumpInFlight || uiDumpCache != null) return
        AppLog.i(ctx, TAG, "pre-warming uiautomator dump for the Shizuku UI backend")
        requestUiDump(ctx) { }
    }

    private fun requestUiDump(ctx: Context, onResult: (String?) -> Unit) {
        uiDumpCache?.let {
            onResult(it)
            return
        }
        uiDumpWaiters.add(onResult)
        if (uiDumpInFlight) return
        uiDumpInFlight = true
        val token = chainStartElapsed
        uiDumpToken = token
        ShizukuBridge.dumpUi(ctx) { xml ->
            uiDumpInFlight = false
            val fresh = token == uiDumpToken && phase != Phase.IDLE && phase != Phase.DONE
            if (fresh) uiDumpCache = xml
            val waiters = ArrayList(uiDumpWaiters)
            uiDumpWaiters.clear()
            waiters.forEach { it(if (fresh) xml else null) }
        }
    }

    // A blind tap at remembered coordinates in an unrelated window would be dangerous;
    // only allow taps inside the learned dialer package or a package that looks like call UI.
    private fun isTrustedInCallPackage(pkg: String, learned: SpeakerAccessibilityService.Fingerprint?): Boolean {
        if (pkg.isBlank()) return false
        if (learned != null && learned.pkg.isNotBlank() && pkg.equals(learned.pkg, ignoreCase = true)) return true
        val lower = pkg.lowercase()
        return listOf("call", "dialer", "phone", "telecom", "incall").any { lower.contains(it) }
    }

    // ---------------------------------------------------------------------
    // Route request and verification
    // ---------------------------------------------------------------------

    private fun requestPublicSpeakerRoute(context: Context, audio: AudioManager, attempt: String) {
        AppLog.i(context, "AudioManager", "request speaker route attempt=$attempt")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = runCatching {
                audio.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
            }.getOrNull()

            if (speaker != null) {
                val accepted = runCatching { audio.setCommunicationDevice(speaker) }
                    .onFailure { AppLog.e(context, "AudioManager", "setCommunicationDevice failed attempt=$attempt", it) }
                    .getOrDefault(false)
                AppLog.i(
                    context,
                    "AudioManager",
                    "setCommunicationDevice speaker accepted=$accepted attempt=$attempt device=${describeDevice(speaker)}"
                )
            } else {
                AppLog.w(context, "AudioManager", "built-in speaker missing from availableCommunicationDevices attempt=$attempt")
            }
        }

        runCatching {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = true
        }.onFailure {
            CallState.lastError = "AudioManager: ${it.message}"
            AppLog.e(context, "AudioManager", "legacy setSpeakerphoneOn failed attempt=$attempt", it)
        }
    }

    fun isSpeakerActuallyActive(
        context: Context,
        audio: AudioManager = context.getSystemService(AudioManager::class.java),
        source: String = "verify"
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = runCatching { audio.communicationDevice }.getOrNull()
            val speaker = device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            AppLog.i(
                context,
                "RouteVerify",
                "source=$source communicationDevice=${describeDevice(device)} speaker=$speaker"
            )
            return speaker
        }

        @Suppress("DEPRECATION")
        val legacy = runCatching { audio.isSpeakerphoneOn }.getOrDefault(false)
        AppLog.i(context, "RouteVerify", "source=$source legacySpeakerphone=$legacy")
        return legacy
    }

    private fun routeIsSpeaker(): Boolean {
        val a = audio ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { a.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                .getOrDefault(false)
        } else {
            runCatching { @Suppress("DEPRECATION") a.isSpeakerphoneOn }.getOrDefault(false)
        }
    }

    private fun isExternalUserRoute(device: AudioDeviceInfo?): Boolean {
        if (device == null) return false
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
            else -> false
        }
    }

    private fun markSuccess(backend: String) {
        CallState.lastBackend = backend
        CallState.lastError = ""
        context()?.let { AppLog.i(it, TAG, "$backend confirmed actual speaker route") }
    }

    private fun finishChain(reason: String) {
        if (phase == Phase.IDLE || phase == Phase.DONE) return
        phase = Phase.DONE
        mainHandler.removeCallbacks(ticker)
        mainHandler.removeCallbacks(modeWaitRunnable)
        unregisterRouteListeners()
        stopTouchCaptureIfNeeded()
        context()?.let { AppLog.i(it, TAG, "route chain finished: $reason") }
    }

    private fun describeDevice(device: AudioDeviceInfo?): String {
        if (device == null) return "null"
        return "type=${device.type},name=${device.productName},id=${device.id}"
    }

    private fun context(): Context? = appContext
}
