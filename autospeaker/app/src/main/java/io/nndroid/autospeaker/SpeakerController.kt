package io.nndroid.autospeaker

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

object SpeakerController {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun enableForIncomingCall(context: Context) {
        if (!CallState.activeIncomingCall || CallState.speakerAttempted) return
        CallState.speakerAttempted = true
        CallState.lastBackend = "AudioManager"
        CallState.lastError = ""

        val audio = context.getSystemService(AudioManager::class.java)
        runCatching {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = true
        }.onFailure { CallState.lastError = "AudioManager: ${it.message}" }

        mainHandler.postDelayed({
            @Suppress("DEPRECATION")
            val directOk = runCatching { audio.isSpeakerphoneOn }.getOrDefault(false)
            if (directOk) {
                CallState.lastBackend = "AudioManager"
                return@postDelayed
            }
            tryShizuku(context.applicationContext)
        }, 250)
    }

    private fun tryShizuku(context: Context) {
        if (!CallState.activeIncomingCall) return
        CallState.lastBackend = "Shizuku"
        ShizukuBridge.setSpeaker(context, true) { ok, error ->
            if (!CallState.activeIncomingCall) return@setSpeaker
            if (ok) {
                CallState.lastBackend = "Shizuku"
                CallState.lastError = ""
            } else {
                CallState.lastError = error
                tryRoot(context)
            }
        }
    }

    private fun tryRoot(context: Context) {
        if (!CallState.activeIncomingCall) return
        CallState.lastBackend = "Root"
        RootBackend.setSpeaker(context, true) { ok, error ->
            if (!CallState.activeIncomingCall) return@setSpeaker
            if (ok) {
                CallState.lastBackend = "Root"
                CallState.lastError = ""
            } else {
                CallState.lastError = error
                requestAccessibilityFallback()
            }
        }
    }

    private fun requestAccessibilityFallback() {
        if (!CallState.activeIncomingCall) return
        CallState.lastBackend = "无障碍"
        CallState.accessibilityFallbackRequested = true
        SpeakerAccessibilityService.requestSpeakerClick()
    }
}
