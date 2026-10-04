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
        requestPublicSpeakerRoute(context, audio)

        mainHandler.postDelayed({
            if (!CallState.activeIncomingCall) return@postDelayed
            if (isSpeakerActuallyActive(context, audio, "AudioManager")) {
                markSuccess(context, "AudioManager")
            } else if (SpeakerAccessibilityService.isConnected()) {
                AppLog.w(context, "AudioManager", "actual route is not speaker; accessibility is connected, trying UI fallback before privileged backends")
                tryAccessibilityFirst(context.applicationContext)
            } else {
                AppLog.w(context, "AudioManager", "actual route is not speaker and accessibility is unavailable; falling back to Shizuku")
                tryShizuku(context.applicationContext)
            }
        }, 450)
    }

    private fun requestPublicSpeakerRoute(context: Context, audio: AudioManager) {
        AppLog.i(context, "AudioManager", "request speaker route")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = runCatching {
                audio.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
            }.getOrNull()

            if (speaker != null) {
                val accepted = runCatching { audio.setCommunicationDevice(speaker) }
                    .onFailure { AppLog.e(context, "AudioManager", "setCommunicationDevice failed", it) }
                    .getOrDefault(false)
                AppLog.i(context, "AudioManager", "setCommunicationDevice speaker accepted=$accepted device=${describeDevice(speaker)}")
            } else {
                AppLog.w(context, "AudioManager", "built-in speaker missing from availableCommunicationDevices")
            }
        }

        runCatching {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = true
        }.onFailure {
            CallState.lastError = "AudioManager: ${it.message}"
            AppLog.e(context, "AudioManager", "legacy setSpeakerphoneOn failed", it)
        }
    }

    private fun tryAccessibilityFirst(context: Context) {
        if (!CallState.activeIncomingCall) return
        CallState.lastBackend = "无障碍"
        CallState.accessibilityFallbackRequested = true
        AppLog.i(context, "Router", "trying accessibility before Shizuku on vivo route-reset path")
        SpeakerAccessibilityService.requestSpeakerClick()

        mainHandler.postDelayed({
            if (!CallState.activeIncomingCall) return@postDelayed
            if (!CallState.accessibilityFallbackRequested && isSpeakerActuallyActive(context, source = "Accessibility-preferred")) {
                markSuccess(context, "无障碍")
                return@postDelayed
            }
            if (isSpeakerActuallyActive(context, source = "Accessibility-preferred-timeout")) {
                CallState.accessibilityFallbackRequested = false
                markSuccess(context, "无障碍")
                return@postDelayed
            }
            AppLog.w(context, "Router", "accessibility preferred attempt did not establish speaker route; continuing to Shizuku")
            tryShizuku(context)
        }, 2200)
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

    fun isSpeakerActuallyActive(context: Context, audio: AudioManager = context.getSystemService(AudioManager::class.java), source: String = "verify"): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = runCatching { audio.communicationDevice }
                .onFailure { AppLog.e(context, "RouteVerify", "getCommunicationDevice failed source=$source", it) }
                .getOrNull()
            val speaker = device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            AppLog.i(context, "RouteVerify", "source=$source communicationDevice=${describeDevice(device)} speaker=$speaker")
            return speaker
        }

        @Suppress("DEPRECATION")
        val legacy = runCatching { audio.isSpeakerphoneOn }.getOrDefault(false)
        AppLog.i(context, "RouteVerify", "source=$source legacySpeakerphone=$legacy")
        return legacy
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
