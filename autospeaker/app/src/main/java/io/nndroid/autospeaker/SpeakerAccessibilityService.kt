package io.nndroid.autospeaker

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

class SpeakerAccessibilityService : AccessibilityService() {
    private val labels = listOf("免提", "扬声器", "Speaker", "Speakerphone")

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLog.i(this, "Accessibility", "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!CallState.activeIncomingCall || !CallState.accessibilityFallbackRequested) return
        AppLog.i(this, "Accessibility", "event type=${event?.eventType} package=${event?.packageName}")
        tryClickSpeaker()
    }

    private fun tryClickSpeaker() {
        if (!CallState.activeIncomingCall || !CallState.accessibilityFallbackRequested) return
        val root = rootInActiveWindow ?: run {
            AppLog.w(this, "Accessibility", "root window unavailable")
            return
        }

        val target = findSpeakerNode(root)
        if (target == null) {
            AppLog.w(this, "Accessibility", "speaker control not found in active window package=${root.packageName}")
            return
        }

        val label = listOfNotNull(target.text, target.contentDescription).joinToString(" / ")
        if (target.isChecked || target.isSelected) {
            CallState.accessibilityFallbackRequested = false
            CallState.lastBackend = "无障碍（已开启）"
            CallState.lastError = ""
            AppLog.i(this, "Accessibility", "speaker already enabled node=$label")
            return
        }

        val clickable = if (target.isClickable) target else clickableParent(target)
        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            AppLog.i(this, "Accessibility", "clicked speaker control node=$label")
            handler.postDelayed({
                if (!CallState.activeIncomingCall) return@postDelayed
                if (SpeakerController.isSpeakerActuallyActive(this, source = "Accessibility")) {
                    CallState.accessibilityFallbackRequested = false
                    CallState.lastBackend = "无障碍"
                    CallState.lastError = ""
                    AppLog.i(this, "Accessibility", "speaker route confirmed after click")
                } else {
                    CallState.lastError = "无障碍点击后实际路由仍不是扬声器"
                    AppLog.w(this, "Accessibility", "click completed but route verification still not speaker")
                }
            }, 500)
        } else {
            AppLog.w(this, "Accessibility", "speaker node found but ACTION_CLICK failed node=$label")
        }
    }

    private fun findSpeakerNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (label in labels) {
            val direct = root.findAccessibilityNodeInfosByText(label).firstOrNull {
                it.isVisibleToUser && (it.isClickable || clickableParent(it) != null)
            }
            if (direct != null) return direct
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < 500) {
            val node = queue.removeFirst()
            scanned++
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val matches = labels.any { label ->
                text.contains(label, ignoreCase = true) || desc.contains(label, ignoreCase = true)
            }
            if (matches && node.isVisibleToUser && (node.isClickable || clickableParent(node) != null)) {
                AppLog.i(this, "Accessibility", "speaker node found by tree scan text=$text desc=$desc class=${node.className}")
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        AppLog.i(this, "Accessibility", "tree scan completed nodes=$scanned package=${root.packageName}")
        return null
    }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p = node.parent
        repeat(6) {
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

        fun isConnected(): Boolean = instance != null

        fun requestSpeakerClick() {
            val current = instance
            if (current == null) return
            handler.post { current.tryClickSpeaker() }
            handler.postDelayed({ instance?.tryClickSpeaker() }, 300)
            handler.postDelayed({ instance?.tryClickSpeaker() }, 900)
            handler.postDelayed({ instance?.tryClickSpeaker() }, 1600)
        }
    }
}
