package io.nndroid.autospeaker

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SpeakerAccessibilityService : AccessibilityService() {
    private val labels = listOf("免提", "扬声器", "Speaker", "Speakerphone")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!CallState.activeIncomingCall || CallState.speakerAttempted) return
        val root = rootInActiveWindow ?: return
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            val target = nodes.firstOrNull { it.isVisibleToUser && (it.isClickable || clickableParent(it) != null) } ?: continue
            if (target.isChecked || target.isSelected) {
                CallState.speakerAttempted = true
                return
            }
            val clickable = if (target.isClickable) target else clickableParent(target)
            if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                CallState.speakerAttempted = true
                return
            }
        }
    }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p = node.parent
        repeat(4) {
            if (p == null) return null
            if (p!!.isClickable) return p
            p = p!!.parent
        }
        return null
    }

    override fun onInterrupt() = Unit
}
