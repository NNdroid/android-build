package io.nndroid.autospeaker

object CallState {
    @Volatile var incomingRinging = false
    @Volatile var activeIncomingCall = false
    @Volatile var speakerAttempted = false

    fun onState(state: Int) {
        when (state) {
            android.telephony.TelephonyManager.CALL_STATE_RINGING -> {
                incomingRinging = true
                activeIncomingCall = false
                speakerAttempted = false
            }
            android.telephony.TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (incomingRinging) activeIncomingCall = true
            }
            android.telephony.TelephonyManager.CALL_STATE_IDLE -> {
                incomingRinging = false
                activeIncomingCall = false
                speakerAttempted = false
            }
        }
    }
}
