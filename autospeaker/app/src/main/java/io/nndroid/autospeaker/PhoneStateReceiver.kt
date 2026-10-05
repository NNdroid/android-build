package io.nndroid.autospeaker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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

        AppLog.i(context, "Call", "PHONE_STATE raw=$rawState active=${CallState.current.active}")
        CallState.onState(state)

        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> if (CallState.current.active) {
                AppLog.i(context, "Call", "incoming call answered; starting event-driven route chain")
                SpeakerController.onIncomingCallAnswered(context.applicationContext)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                AppLog.i(context, "Call", "call idle; stopping route chain")
                SpeakerController.onCallEnded(context.applicationContext)
            }
            else -> {}
        }
    }
}
