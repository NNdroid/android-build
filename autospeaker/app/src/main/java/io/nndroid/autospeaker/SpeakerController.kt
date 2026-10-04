package io.nndroid.autospeaker

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

object SpeakerController {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun enableForIncomingCall(context: Context) {
        if (!CallState.activeIncomingCall || CallState.speakerAttempted) {
            AppLog.i(context, "Router", "skip enable active=${CallState.activeIncomingCall} attempted=${CallState.speakerAttempted}")
            return
        }
        CallState.speakerAttempted = true
        CallState.lastBackend = "AudioManager"
        CallState.lastError = ""
        AppLog.i(context, "Router", "starting backend chain")

        val audio = context.getSystemService(AudioManager::class.java)
        requestPublicSpeakerRoute(context, audio, "initial")

        mainHandler.postDelayed({
            if (!CallState.activeIncomingCall) return@postDelayed
            if (isSpeakerActuallyActive(context, audio, "AudioManager-initial")) {
                markSuccess(context, "AudioManager")
                return@postDelayed
            }

            AppLog.w(context, "AudioManager", "initial route was reset by telecom/oem policy")
            if (SpeakerAccessibilityService.isConnected()) {
                tryAccessibilityImmediately(context.applicationContext)
            } else {
                AppLog.i(context, "Router", "accessibility unavailable; waiting 1s before stabilization retry")
            }

            // Important on vivo/OriginOS: the first route request can be overwritten while
            // Telecom is still settling the call audio state. Re-check exactly one second
            // later. If accessibility already established speaker, this becomes a no-op.
            scheduleStabilizationRetry(context.applicationContext, 1000L)
        }, 450L)
    }

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

    private fun tryAccessibilityImmediately(context: Context) {
        if (!CallState.activeIncomingCall) return
        CallState.lastBackend = "无障碍"
        CallState.accessibilityFallbackRequested = true
        AppLog.i(context, "Router", "accessibility connected; trying UI speaker control immediately while stabilization timer runs")
        SpeakerAccessibilityService.requestSpeakerClick()
    }

    private fun scheduleStabilizationRetry(context: Context, delayMs: Long) {
        mainHandler.postDelayed({
            if (!CallState.activeIncomingCall) return@postDelayed
            val audio = context.getSystemService(AudioManager::class.java)

            if (isSpeakerActuallyActive(context, audio, "pre-stabilization-retry")) {
                CallState.accessibilityFallbackRequested = false
                markSuccess(context, "扬声器已稳定")
                AppLog.i(context, "Router", "+1s retry skipped because speaker is already active")
                return@postDelayed
            }

            val current = currentCommunicationDevice(audio)
            if (isExternalUserRoute(current)) {
                CallState.accessibilityFallbackRequested = false
                CallState.lastBackend = "保持外部音频设备"
                CallState.lastError = "检测到外部通话音频设备，停止自动切换"
                AppLog.i(
                    context,
                    "Router",
                    "stabilization retry cancelled because current route is external device=${describeDevice(current)}"
                )
                return@postDelayed
            }

            AppLog.i(context, "Router", "running delayed stabilization retry delayMs=$delayMs")
            requestPublicSpeakerRoute(context, audio, "stabilization+${delayMs}ms")

            mainHandler.postDelayed({
                if (!CallState.activeIncomingCall) return@postDelayed
                if (isSpeakerActuallyActive(context, audio, "AudioManager-stabilization+${delayMs}ms")) {
                    CallState.accessibilityFallbackRequested = false
                    markSuccess(context, "AudioManager(+${delayMs}ms)")
                } else {
                    // Stop pending accessibility retries before entering the privileged chain,
                    // otherwise a late UI retry could toggle an already-changed route.
                    CallState.accessibilityFallbackRequested = false
                    AppLog.w(
                        context,
                        "Router",
                        "+${delayMs}ms retry was also reset to non-speaker; continuing to Shizuku"
                    )
                    tryShizuku(context)
                }
            }, 450L)
        }, delayMs)
    }

    private fun tryShizuku(context: Context) {
        if (!CallState.activeIncomingCall) return
        CallState.lastBackend = "Shizuku"
        AppLog.i(context, "Router", "trying Shizuku backend")
        ShizukuBridge.setSpeaker(context, true) { ok, error ->
            if (!CallState.activeIncomingCall) return@setSpeaker
            if (!ok) {
                CallState.lastError = error
                AppLog.w(context, "Router", "Shizuku invocation failed: $error; trying Root")
                tryRoot(context)
                return@setSpeaker
            }

            AppLog.i(context, "Router", "Shizuku invocation returned success; verifying real route")
            verifyAfterBackend(context, "Shizuku") {
                tryRoot(context)
            }
        }
    }

    private fun tryRoot(context: Context) {
        if (!CallState.activeIncomingCall) return
        CallState.lastBackend = "Root"
        AppLog.i(context, "Router", "trying Root backend")
        RootBackend.setSpeaker(context, true) { ok, error ->
            if (!CallState.activeIncomingCall) return@setSpeaker
            if (!ok) {
                CallState.lastError = error
                AppLog.w(context, "Router", "Root invocation failed: $error; requesting accessibility fallback")
                requestAccessibilityFallback(context)
                return@setSpeaker
            }

            AppLog.i(context, "Router", "Root invocation returned success; verifying real route")
            verifyAfterBackend(context, "Root") {
                requestAccessibilityFallback(context)
            }
        }
    }

    private fun verifyAfterBackend(context: Context, backend: String, onFailure: () -> Unit) {
        val audio = context.getSystemService(AudioManager::class.java)
        mainHandler.postDelayed({
            if (!CallState.activeIncomingCall) return@postDelayed
            if (isSpeakerActuallyActive(context, audio, backend)) {
                markSuccess(context, backend)
            } else {
                val error = "$backend returned success but actual route is not built-in speaker"
                CallState.lastError = error
                AppLog.w(context, "Router", "$error; continuing fallback chain")
                onFailure()
            }
        }, 450L)
    }

    fun isSpeakerActuallyActive(
        context: Context,
        audio: AudioManager = context.getSystemService(AudioManager::class.java),
        source: String = "verify"
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = currentCommunicationDevice(audio)
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

    private fun currentCommunicationDevice(audio: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching { audio.communicationDevice }.getOrNull()
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

    private fun markSuccess(context: Context, backend: String) {
        CallState.lastBackend = backend
        CallState.lastError = ""
        AppLog.i(context, "Router", "$backend confirmed actual speaker route")
    }

    private fun requestAccessibilityFallback(context: Context) {
        if (!CallState.activeIncomingCall) return
        CallState.lastBackend = "无障碍"
        CallState.accessibilityFallbackRequested = true
        val connected = SpeakerAccessibilityService.isConnected()
        AppLog.i(context, "Router", "accessibility fallback requested connected=$connected")
        if (!connected) {
            CallState.lastError = "无障碍服务未连接"
            AppLog.w(context, "Router", "accessibility service is not connected; cannot click speaker")
        }
        SpeakerAccessibilityService.requestSpeakerClick()
    }

    private fun describeDevice(device: AudioDeviceInfo?): String {
        if (device == null) return "null"
        return "type=${device.type},name=${device.productName},id=${device.id}"
    }
}
