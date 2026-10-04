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
            } else if (SpeakerAccessibilityService.isConnected()) {
                AppLog.w(context, "AudioManager", "initial route was reset; accessibility is connected, trying UI route first")
                tryAccessibilityFirst(context.applicationContext)
            } else {
                AppLog.w(context, "AudioManager", "initial route was reset and accessibility is unavailable; scheduling +1s stabilization retry")
                tryStabilizedPublicRetry(context.applicationContext)
            }
        }, 450)
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
                AppLog.i(context, "AudioManager", "setCommunicationDevice speaker accepted=$accepted attempt=$attempt device=${describeDevice(speaker)}")
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

    private fun tryAccessibilityFirst(context: Context) {
        if (!CallState.activeIncomingCall) return
        CallState.lastBackend = "无障碍"
        CallState.accessibilityFallbackRequested = true
        AppLog.i(context, "Router", "trying accessibility before privileged backends")
        SpeakerAccessibilityService.requestSpeakerClick()

        mainHandler.postDelayed({
            if (!CallState.activeIncomingCall) return@postDelayed
            if (isSpeakerActuallyActive(context, source = "Accessibility-preferred")) {
                CallState.accessibilityFallbackRequested = false
                markSuccess(context, "无障碍")
                return@postDelayed
            }
            AppLog.w(context, "Router", "accessibility attempt did not establish speaker route; scheduling +1s stabilization retry")
            tryStabilizedPublicRetry(context)
        }, 1800)
    }

    private fun tryStabilizedPublicRetry(context: Context) {
        mainHandler.postDelayed({
            if (!CallState.activeIncomingCall) return@postDelayed
            val audio = context.getSystemService(AudioManager::class.java)

            if (isSpeakerActuallyActive(context, audio, "pre-stabilization-retry")) {
                markSuccess(context, "已有扬声器路由")
                return@postDelayed
            }

            val current = currentCommunicationDevice(audio)
            if (isExternalUserRoute(current)) {
                CallState.lastBackend = "保持外部音频设备"
                CallState.lastError = "检测到外部通话音频设备，停止自动切换"
                AppLog.i(context, "Router", "stabilization retry cancelled because current route is external device=${describeDevice(current)}")
                return@postDelayed
            }

            AppLog.i(context, "Router", "running delayed stabilization retry +1s")
            requestPublicSpeakerRoute(context, audio, "stabilization+1s")
            mainHandler.postDelayed({
                if (!CallState.activeIncomingCall) return@postDelayed
                if (isSpeakerActuallyActive(context, audio, "AudioManager-stabilization+1s")) {
                    markSuccess(context, "AudioManager(+1s)")
                } else {
                    AppLog.w(context, "Router", "+1s retry was also reset to non-speaker; continuing to Shizuku")
                    tryShizuku(context)
                }
            }, 450)
        }, 1000)
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
        }, 450)
    }

    fun isSpeakerActuallyActive(
        context: Context,
        audio: AudioManager = context.getSystemService(AudioManager::class.java),
        source: String = "verify"
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = currentCommunicationDevice(audio)
            val speaker = device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            AppLog.i(context, "RouteVerify", "source=$source communicationDevice=${describeDevice(device)} speaker=$speaker")
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
