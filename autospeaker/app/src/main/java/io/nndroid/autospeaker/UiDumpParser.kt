package io.nndroid.autospeaker

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.math.abs

/**
 * Minimal reader for `uiautomator dump` XML. Finds the speaker control the same way the
 * accessibility service does (learned fingerprint first, then text/hint keywords) and returns
 * its screen centre so the Shizuku backend can `input tap` it.
 */
object UiDumpParser {
    data class Control(
        val pkg: String,
        val viewId: String,
        val desc: String,
        val className: String,
        val matchedBy: String,
        val cx: Int,
        val cy: Int,
        val w: Int,
        val h: Int
    )

    private const val MAX_GEOMETRY_SCORE = 900
    private val boundsRegex = Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")

    fun findSpeakerControl(
        xml: String,
        screenW: Int,
        screenH: Int,
        learned: SpeakerAccessibilityService.Fingerprint?
    ): Control? {
        var learnedGeometry: Control? = null
        var learnedGeometryScore = Int.MAX_VALUE
        var keyword: Control? = null
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "node") {
                    val text = parser.getAttributeValue(null, "text").orEmpty()
                    val desc = parser.getAttributeValue(null, "content-desc").orEmpty()
                    val viewId = parser.getAttributeValue(null, "resource-id").orEmpty()
                    val className = parser.getAttributeValue(null, "class").orEmpty()
                    val pkg = parser.getAttributeValue(null, "package").orEmpty()
                    val bounds = parseBounds(parser.getAttributeValue(null, "bounds"))
                    if (bounds != null) {
                        val cx = bounds.left + bounds.w / 2
                        val cy = bounds.top + bounds.h / 2
                        if (bounds.w > 0 && bounds.h > 0 && cx in 0 until screenW && cy in 0 until screenH) {
                            val control = Control(pkg, viewId, desc, className, "", cx, cy, bounds.w, bounds.h)

                            if (learned != null) {
                                val exact = matchLearnedExactly(control, learned)
                                if (exact != null) return exact

                                val classOk = learned.className.isBlank() || className == learned.className
                                if (classOk) {
                                    val score = geometryScore(control, learned, screenW, screenH)
                                    if (score < learnedGeometryScore) {
                                        learnedGeometryScore = score
                                        learnedGeometry = control.copy(matchedBy = "learned:geometry:$score")
                                    }
                                }
                            }

                            if (keyword == null) {
                                val hint = SpeakerControlMatchers.matchHint(text, desc, viewId)
                                if (hint != null) keyword = control.copy(matchedBy = "keyword:$hint")
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (t: Throwable) {
            // Fall through and return whatever candidate was collected before the parse error.
        }
        return learnedGeometry?.takeIf { learnedGeometryScore <= MAX_GEOMETRY_SCORE } ?: keyword
    }

    private fun matchLearnedExactly(
        control: Control,
        learned: SpeakerAccessibilityService.Fingerprint
    ): Control? {
        if (learned.pkg.isNotBlank() && !control.pkg.equals(learned.pkg, ignoreCase = true)) return null
        return when {
            learned.viewId.isNotBlank() && control.viewId == learned.viewId ->
                control.copy(matchedBy = "learned:viewId")
            learned.desc.isNotBlank() && control.desc.equals(learned.desc, ignoreCase = true) ->
                control.copy(matchedBy = "learned:contentDescription")
            else -> null
        }
    }

    private fun geometryScore(
        control: Control,
        learned: SpeakerAccessibilityService.Fingerprint,
        screenW: Int,
        screenH: Int
    ): Int {
        fun norm(value: Int, max: Int) = if (max <= 0) 0 else (value.toLong() * 10000L / max).toInt()
        val ncx = norm(control.cx, screenW)
        val ncy = norm(control.cy, screenH)
        val nw = norm(control.w, screenW)
        val nh = norm(control.h, screenH)
        return abs(ncx - learned.cx) + abs(ncy - learned.cy) + abs(nw - learned.w) / 2 + abs(nh - learned.h) / 2
    }

    private data class Bounds(val left: Int, val top: Int, val w: Int, val h: Int)

    private fun parseBounds(value: String?): Bounds? {
        if (value.isNullOrBlank()) return null
        val match = boundsRegex.matchEntire(value.trim()) ?: return null
        val (l, t, r, b) = match.destructured
        val left = l.toInt()
        val top = t.toInt()
        val right = r.toInt()
        val bottom = b.toInt()
        if (right <= left || bottom <= top) return null
        return Bounds(left, top, right - left, bottom - top)
    }
}
