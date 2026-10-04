package io.nndroid.autospeaker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager

class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            else -> TelephonyManager.CALL_STATE_IDLE
        }
        CallState.onState(state)
        if (state == TelephonyManager.CALL_STATE_OFFHOOK && CallState.activeIncomingCall) {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    val audio = context.getSystemService(AudioManager::class.java)
                    @Suppress("DEPRECATION")
                    audio.isSpeakerphoneOn = true
                }
            }, 700)
        }
    }
}
