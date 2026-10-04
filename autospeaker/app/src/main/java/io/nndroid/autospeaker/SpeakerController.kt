package io.nndroid.autospeaker

import android.content.Context
import android.media.AudioManager
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
        AppLog.i(context, "AudioManager", "request speakerphone ON")
        runCatching {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = true
        }.onFailure {
            CallState.lastError = "AudioManager: ${it.message}"
            AppLog.e(context, "AudioManager", "setSpeakerphoneOn failed", it)
        }

        mainHandler.postDelayed({
            @Suppress("DEPRECATION")
            val directOk = runCatching { audio.isSpeakerphoneOn }.getOrDefault(false)
            if (directOk) {
                CallState.lastBackend = "AudioManager"
                CallState.lastError = ""
                AppLog.i(context, "AudioManager", "speakerphone confirmed ON")
                return@postDelayed
            }
            AppLog.w(context, "AudioManager", "speakerphone did not stay ON; falling back to Shizuku")
            tryShizuku(context.applicationContext)
        }, 250)
    }

    private fun tryShizuku(context: Context) {
        if (!CallState.activeIncomingCall) return
        CallState.lastBackend = "Shizuku"
        AppLog.i(context, "Router", "trying Shizuku backend")
        ShizukuBridge.setSpeaker(context, true) { ok, error ->
            if (!CallState.activeIncomingCall) return@setSpeaker
            if (ok) {
                CallState.lastBackend = "Shizuku"
                CallState.lastError = ""
                AppLog.i(context, "Router", "Shizuku backend succeeded")
            } else {
                CallState.lastError = error
                AppLog.w(context, "Router", "Shizuku failed: $error; trying Root")
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
            if (ok) {
                CallState.lastBackend = "Root"
                CallState.lastError = ""
                AppLog.i(context, "Router", "Root backend succeeded")
            } else {
                CallState.lastError = error
                AppLog.w(context, "Router", "Root failed: $error; requesting accessibility fallback")
                requestAccessibilityFallback(context)
            }
        }
    }

    private fun requestAccessibilityFallback(context: Context) {
        if (!CallState.activeIncomingCall) return
        CallState.lastBackend = "无障碍"
        CallState.accessibilityFallbackRequested = true
        AppLog.i(context, "Router", "accessibility fallback requested")
        SpeakerAccessibilityService.requestSpeakerClick()
    }
}
