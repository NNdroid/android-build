package io.nndroid.autospeaker

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SpeakerAccessibilityService : AccessibilityService() {
    private val labels = listOf("免提", "扬声器", "Speaker", "Speakerphone")

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLog.i(this, "Accessibility", "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!CallState.activeIncomingCall || !CallState.accessibilityFallbackRequested) return
        tryClickSpeaker()
    }

    private fun tryClickSpeaker() {
        if (!CallState.activeIncomingCall || !CallState.accessibilityFallbackRequested) return
        val root = rootInActiveWindow ?: run {
            AppLog.w(this, "Accessibility", "root window unavailable")
            return
        }

        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            val target = nodes.firstOrNull {
                it.isVisibleToUser && (it.isClickable || clickableParent(it) != null)
            } ?: continue

            if (target.isChecked || target.isSelected) {
                CallState.accessibilityFallbackRequested = false
                CallState.lastBackend = "无障碍（已开启）"
                CallState.lastError = ""
                AppLog.i(this, "Accessibility", "speaker already enabled via label=$label")
                return
            }

            val clickable = if (target.isClickable) target else clickableParent(target)
            if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                CallState.accessibilityFallbackRequested = false
                CallState.lastBackend = "无障碍"
                CallState.lastError = ""
                AppLog.i(this, "Accessibility", "clicked speaker control label=$label")
                return
            }
        }
        AppLog.w(this, "Accessibility", "speaker control not found in active window")
    }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p = node.parent
        repeat(5) {
            if (p == null) return null
            if (p!!.isClickable) return p
            p = p!!.parent
        }
        return null
    }

    override fun onInterrupt() {
        AppLog.w(this, "Accessibility", "service interrupted")
    }

    override fun onDestroy() {
        AppLog.w(this, "Accessibility", "service destroyed")
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: SpeakerAccessibilityService? = null
        private val handler = Handler(Looper.getMainLooper())

        fun requestSpeakerClick() {
            handler.post { instance?.tryClickSpeaker() }
            handler.postDelayed({ instance?.tryClickSpeaker() }, 300)
            handler.postDelayed({ instance?.tryClickSpeaker() }, 900)
        }
    }
}
