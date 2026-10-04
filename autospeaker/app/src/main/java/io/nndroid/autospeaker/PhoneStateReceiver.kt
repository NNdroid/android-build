package io.nndroid.autospeaker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager

class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        ShizukuBridge.init(context)
        val rawState = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val state = when (rawState) {
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            else -> TelephonyManager.CALL_STATE_IDLE
        }

        AppLog.i(context, "Call", "PHONE_STATE raw=$rawState incoming=${CallState.incomingRinging} active=${CallState.activeIncomingCall}")
        CallState.onState(state)
        AppLog.i(context, "Call", "state applied ringing=${CallState.incomingRinging} active=${CallState.activeIncomingCall}")

        if (state == TelephonyManager.CALL_STATE_OFFHOOK && CallState.activeIncomingCall) {
            val appContext = context.applicationContext
            AppLog.i(context, "Call", "incoming call answered; scheduling speaker route in 700ms")
            Handler(Looper.getMainLooper()).postDelayed({
                SpeakerController.enableForIncomingCall(appContext)
            }, 700)
        } else if (state == TelephonyManager.CALL_STATE_IDLE) {
            AppLog.i(context, "Call", "call idle; route state reset")
        }
    }
}
