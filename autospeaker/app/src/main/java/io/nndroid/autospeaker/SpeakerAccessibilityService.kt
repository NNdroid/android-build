package io.nndroid.autospeaker

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

class SpeakerAccessibilityService : AccessibilityService() {
    private val labels = listOf("免提", "扬声器", "Speaker", "Speakerphone", "Handsfree", "Hands-free")
    private val hints = listOf("speaker", "speakerphone", "handsfree", "hands_free", "audio_route", "免提", "扬声器")
    @Volatile private var clickInFlight = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLog.i(this, "Accessibility", "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!CallState.activeIncomingCall || !CallState.accessibilityFallbackRequested) return
        AppLog.i(this, "Accessibility", "event type=${event?.eventType} package=${event?.packageName}")
        tryClickSpeaker("event:${event?.eventType ?: -1}")
    }

    private fun tryClickSpeaker(trigger: String) {
        if (!CallState.activeIncomingCall || !CallState.accessibilityFallbackRequested || clickInFlight) return
        val root = rootInActiveWindow ?: run {
            AppLog.w(this, "Accessibility", "root window unavailable trigger=$trigger")
            return
        }

        val pkg = root.packageName?.toString().orEmpty().ifBlank { "unknown" }
        val candidate = findSpeakerCandidate(root)
        if (candidate == null) {
            AppLog.w(this, "Accessibility", "speaker control not found package=$pkg trigger=$trigger")
            return
        }

        val (target, matchedBy) = candidate
        if (target.isChecked || target.isSelected) {
            if (SpeakerController.isSpeakerActuallyActive(this, source = "Accessibility-selected")) {
                CallState.accessibilityFallbackRequested = false
                CallState.lastBackend = "无障碍（已开启）"
                CallState.lastError = ""
                AppLog.i(this, "Accessibility", "speaker already active package=$pkg matchedBy=$matchedBy")
            }
            return
        }

        val clickable = if (target.isClickable) target else clickableParent(target)
        if (clickable == null) {
            AppLog.w(this, "Accessibility", "candidate not clickable package=$pkg matchedBy=$matchedBy")
            return
        }

        clickInFlight = true
        val clicked = runCatching {
            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)

        if (!clicked) {
            clickInFlight = false
            AppLog.w(this, "Accessibility", "click failed package=$pkg matchedBy=$matchedBy")
            return
        }

        AppLog.i(this, "Accessibility", "clicked speaker candidate package=$pkg matchedBy=$matchedBy")
        handler.postDelayed({
            if (!CallState.activeIncomingCall) {
                clickInFlight = false
                return@postDelayed
            }
            if (SpeakerController.isSpeakerActuallyActive(this, source = "Accessibility")) {
                CallState.accessibilityFallbackRequested = false
                CallState.lastBackend = "无障碍"
                CallState.lastError = ""
                AppLog.i(this, "Accessibility", "speaker route verified package=$pkg matchedBy=$matchedBy")
            } else {
                CallState.lastError = "无障碍点击后实际路由仍不是扬声器"
                AppLog.w(this, "Accessibility", "click did not produce speaker route package=$pkg matchedBy=$matchedBy; retry allowed")
            }
            clickInFlight = false
        }, 350)
    }

    private fun findSpeakerCandidate(root: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, String>? {
        for (label in labels) {
            val direct = root.findAccessibilityNodeInfosByText(label).firstOrNull {
                it.isVisibleToUser && (it.isClickable || clickableParent(it) != null)
            }
            if (direct != null) return direct to "text:$label"
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < 600) {
            val node = queue.removeFirst()
            scanned++
            if (node.isVisibleToUser) {
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val viewId = node.viewIdResourceName.orEmpty()
                val hint = hints.firstOrNull {
                    text.contains(it, ignoreCase = true) ||
                        desc.contains(it, ignoreCase = true) ||
                        viewId.contains(it, ignoreCase = true)
                }
                if (hint != null && (node.isClickable || clickableParent(node) != null)) {
                    val source = when {
                        viewId.contains(hint, ignoreCase = true) -> "viewId:$hint"
                        desc.contains(hint, ignoreCase = true) -> "contentDescription:$hint"
                        else -> "text:$hint"
                    }
                    AppLog.i(this, "Accessibility", "speaker candidate found source=$source class=${node.className}")
                    return node to source
                }
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

        fun isEnabledInSettings(context: Context): Boolean {
            val component = ComponentName(context, SpeakerAccessibilityService::class.java).flattenToString()
            return Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty().split(':').any { it.equals(component, ignoreCase = true) }
        }

        fun requestSpeakerClick() {
            val current = instance ?: return
            handler.post { current.tryClickSpeaker("request:0") }
            handler.postDelayed({ instance?.tryClickSpeaker("request:350") }, 350)
            handler.postDelayed({ instance?.tryClickSpeaker("request:900") }, 900)
            handler.postDelayed({ instance?.tryClickSpeaker("request:1600") }, 1600)
        }
    }
}
