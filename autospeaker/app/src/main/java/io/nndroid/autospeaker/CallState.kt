package io.nndroid.autospeaker

import android.telephony.TelephonyManager
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable snapshot of the phone-state machine. Writers (PhoneStateReceiver and the legacy
 * PhoneStateListener) publish whole snapshots through a CAS loop, so readers never observe a
 * torn state. On dual-SIM devices each subscription broadcasts its own PHONE_STATE; a second
 * SIM's RINGING must not tear down a call that is already active.
 */
data class CallSnapshot(
    val ringing: Boolean = false,
    val active: Boolean = false,
    val generation: Int = 0,
    val speakerAttempted: Boolean = false,
    val accessibilityFallbackRequested: Boolean = false
)

object CallState {
    @Volatile var lastBackend: String = "待机"
    @Volatile var lastError: String = ""

    private val ref = AtomicReference(CallSnapshot())

    val current: CallSnapshot
        get() = ref.get()

    fun onState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                update { cur ->
                    when {
                        // Call waiting or a duplicate RINGING: keep the ongoing call untouched.
                        cur.active -> cur
                        cur.ringing -> cur
                        else -> cur.copy(
                            ringing = true,
                            active = false,
                            generation = cur.generation + 1,
                            speakerAttempted = false,
                            accessibilityFallbackRequested = false
                        )
                    }
                }
                lastBackend = "来电中"
                lastError = ""
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                update { cur ->
                    if (cur.ringing && !cur.active) cur.copy(active = true, speakerAttempted = false) else cur
                }
                if (current.active) lastBackend = "正在切换"
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                ref.set(CallSnapshot(generation = ref.get().generation))
                lastBackend = "待机"
                lastError = ""
            }
        }
    }

    /** Returns true exactly once per incoming call; guards the whole routing chain. */
    fun markSpeakerAttempted(): Boolean {
        while (true) {
            val cur = ref.get()
            if (!cur.active || cur.speakerAttempted) return false
            if (ref.compareAndSet(cur, cur.copy(speakerAttempted = true))) return true
        }
    }

    fun requestAccessibilityFallback() {
        update { if (it.active) it.copy(accessibilityFallbackRequested = true) else it }
    }

    fun clearAccessibilityFallback() {
        update { it.copy(accessibilityFallbackRequested = false) }
    }

    fun markAccessibilitySucceeded(backend: String = "无障碍") {
        clearAccessibilityFallback()
        lastBackend = backend
        lastError = ""
    }

    private fun update(transform: (CallSnapshot) -> CallSnapshot) {
        while (true) {
            val cur = ref.get()
            val next = transform(cur)
            if (next == cur || ref.compareAndSet(cur, next)) return
        }
    }
}
