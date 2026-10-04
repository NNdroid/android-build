package io.nndroid.autospeaker

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import kotlin.math.abs

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
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && isLearning(this)) {
            captureLearningClick(event)
        }

        if (!CallState.activeIncomingCall || !CallState.accessibilityFallbackRequested) return
        AppLog.i(this, "Accessibility", "event type=${event.eventType} package=${event.packageName}")
        tryClickSpeaker("event:${event.eventType}")
    }

    private fun captureLearningClick(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == packageName) return
        val source = event.source ?: return
        val target = if (source.isClickable) source else clickableParent(source) ?: source
        if (!target.isVisibleToUser) return

        val snapshot = snapshot(target, pkg)
        AppLog.i(
            this,
            "Learning",
            "candidate clicked package=${snapshot.pkg} class=${snapshot.className} viewId=${snapshot.viewId.ifBlank { "<none>" }} bounds=${snapshot.cx},${snapshot.cy},${snapshot.w},${snapshot.h}; verifying speaker route"
        )

        handler.postDelayed({
            if (!isLearning(this)) return@postDelayed
            if (SpeakerController.isSpeakerActuallyActive(this, source = "Learning")) {
                saveFingerprint(this, snapshot)
                stopLearning(this)
                AppLog.i(this, "Learning", "speaker button learned successfully package=${snapshot.pkg} viewId=${snapshot.viewId.ifBlank { "<none>" }}")
            } else {
                AppLog.i(this, "Learning", "clicked control did not produce speaker route; learning remains active")
            }
        }, 450)
    }

    private fun tryClickSpeaker(trigger: String) {
        if (!CallState.activeIncomingCall || !CallState.accessibilityFallbackRequested || clickInFlight) return
        val root = rootInActiveWindow ?: run {
            AppLog.w(this, "Accessibility", "root window unavailable trigger=$trigger")
            return
        }

        val pkg = root.packageName?.toString().orEmpty().ifBlank { "unknown" }
        val candidate = findLearnedCandidate(root)
            ?: findSpeakerCandidate(root)

        if (candidate == null) {
            AppLog.w(this, "Accessibility", "speaker control not found package=$pkg trigger=$trigger")
            logAnonymousIconCandidates(root)
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

    private fun findLearnedCandidate(root: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, String>? {
        val learned = loadFingerprint(this) ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        if (learned.pkg.isNotBlank() && pkg.isNotBlank() && learned.pkg != pkg) return null

        if (learned.viewId.isNotBlank()) {
            val byId = runCatching { root.findAccessibilityNodeInfosByViewId(learned.viewId) }
                .getOrDefault(emptyList())
                .firstOrNull { it.isVisibleToUser && (it.isClickable || clickableParent(it) != null) }
            if (byId != null) {
                AppLog.i(this, "Accessibility", "learned speaker matched by viewId=${learned.viewId}")
                return byId to "learned:viewId"
            }
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MAX_VALUE
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && (node.isClickable || clickableParent(node) != null)) {
                val desc = node.contentDescription?.toString().orEmpty()
                if (learned.desc.isNotBlank() && desc.equals(learned.desc, ignoreCase = true)) {
                    return node to "learned:contentDescription"
                }

                if (learned.className.isBlank() || node.className?.toString() == learned.className) {
                    val snap = snapshot(node, pkg)
                    val score = abs(snap.cx - learned.cx) + abs(snap.cy - learned.cy) +
                        abs(snap.w - learned.w) / 2 + abs(snap.h - learned.h) / 2
                    if (score < bestScore) {
                        bestScore = score
                        best = node
                    }
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }

        if (best != null && bestScore <= 900) {
            AppLog.i(this, "Accessibility", "learned speaker matched by geometry score=$bestScore")
            return best to "learned:geometry:$bestScore"
        }
        return null
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
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        AppLog.i(this, "Accessibility", "tree scan completed nodes=$scanned package=${root.packageName}")
        return null
    }

    private fun logAnonymousIconCandidates(root: AccessibilityNodeInfo) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var logged = 0
        while (queue.isNotEmpty() && logged < 12) {
            val node = queue.removeFirst()
            val className = node.className?.toString().orEmpty()
            val iconLike = className.contains("Image", ignoreCase = true) ||
                className.contains("Button", ignoreCase = true)
            if (iconLike && node.isVisibleToUser && (node.isClickable || clickableParent(node) != null)) {
                val snap = snapshot(node, root.packageName?.toString().orEmpty())
                AppLog.i(
                    this,
                    "AccessibilityCandidate",
                    "class=${snap.className} viewId=${snap.viewId.ifBlank { "<none>" }} hasDesc=${snap.desc.isNotBlank()} bounds=${snap.cx},${snap.cy},${snap.w},${snap.h} checked=${node.isChecked} selected=${node.isSelected}"
                )
                logged++
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
    }

    private fun snapshot(node: AccessibilityNodeInfo, pkg: String): Fingerprint {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val dm = resources.displayMetrics
        fun norm(value: Int, max: Int): Int = if (max <= 0) 0 else ((value.toLong() * 10000L) / max).toInt()
        return Fingerprint(
            pkg = pkg,
            viewId = node.viewIdResourceName.orEmpty(),
            desc = node.contentDescription?.toString().orEmpty(),
            className = node.className?.toString().orEmpty(),
            cx = norm(rect.centerX(), dm.widthPixels),
            cy = norm(rect.centerY(), dm.heightPixels),
            w = norm(rect.width(), dm.widthPixels),
            h = norm(rect.height(), dm.heightPixels)
        )
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

    data class Fingerprint(
        val pkg: String,
        val viewId: String,
        val desc: String,
        val className: String,
        val cx: Int,
        val cy: Int,
        val w: Int,
        val h: Int
    )

    companion object {
        @Volatile private var instance: SpeakerAccessibilityService? = null
        private val handler = Handler(Looper.getMainLooper())
        private const val PREFS = "speaker_learning"
        private const val KEY_UNTIL = "learn_until"

        fun isConnected(): Boolean = instance != null

        fun isEnabledInSettings(context: Context): Boolean {
            val component = ComponentName(context, SpeakerAccessibilityService::class.java).flattenToString()
            return Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty().split(':').any { it.equals(component, ignoreCase = true) }
        }

        fun startLearning(context: Context, durationMs: Long = 30_000L) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_UNTIL, System.currentTimeMillis() + durationMs).apply()
            AppLog.i(context, "Learning", "speaker button learning started durationMs=$durationMs")
        }

        fun stopLearning(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_UNTIL).apply()
        }

        fun isLearning(context: Context): Boolean {
            val until = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_UNTIL, 0L)
            return System.currentTimeMillis() < until
        }

        fun hasLearnedFingerprint(context: Context): Boolean = loadFingerprint(context) != null

        fun clearLearnedFingerprint(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("fp_pkg").remove("fp_view_id").remove("fp_desc").remove("fp_class")
                .remove("fp_cx").remove("fp_cy").remove("fp_w").remove("fp_h")
                .remove(KEY_UNTIL).apply()
            AppLog.i(context, "Learning", "learned speaker fingerprint cleared")
        }

        private fun saveFingerprint(context: Context, fp: Fingerprint) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("fp_pkg", fp.pkg)
                .putString("fp_view_id", fp.viewId)
                .putString("fp_desc", fp.desc)
                .putString("fp_class", fp.className)
                .putInt("fp_cx", fp.cx)
                .putInt("fp_cy", fp.cy)
                .putInt("fp_w", fp.w)
                .putInt("fp_h", fp.h)
                .apply()
        }

        private fun loadFingerprint(context: Context): Fingerprint? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!p.contains("fp_cx") || !p.contains("fp_cy")) return null
            return Fingerprint(
                pkg = p.getString("fp_pkg", "").orEmpty(),
                viewId = p.getString("fp_view_id", "").orEmpty(),
                desc = p.getString("fp_desc", "").orEmpty(),
                className = p.getString("fp_class", "").orEmpty(),
                cx = p.getInt("fp_cx", 0),
                cy = p.getInt("fp_cy", 0),
                w = p.getInt("fp_w", 0),
                h = p.getInt("fp_h", 0)
            )
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
