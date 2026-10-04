package io.nndroid.autospeaker

object CallState {
    @Volatile var incomingRinging = false
    @Volatile var activeIncomingCall = false
    @Volatile var speakerAttempted = false
    @Volatile var accessibilityFallbackRequested = false
    @Volatile var lastBackend = "待机"
    @Volatile var lastError = ""

    fun onState(state: Int) {
        when (state) {
            android.telephony.TelephonyManager.CALL_STATE_RINGING -> {
                incomingRinging = true
                activeIncomingCall = false
                speakerAttempted = false
                accessibilityFallbackRequested = false
                lastBackend = "来电中"
                lastError = ""
            }
            android.telephony.TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (incomingRinging) {
                    activeIncomingCall = true
                    lastBackend = "正在切换"
                }
            }
            android.telephony.TelephonyManager.CALL_STATE_IDLE -> {
                incomingRinging = false
                activeIncomingCall = false
                speakerAttempted = false
                accessibilityFallbackRequested = false
                lastBackend = "待机"
            }
        }
    }
}
