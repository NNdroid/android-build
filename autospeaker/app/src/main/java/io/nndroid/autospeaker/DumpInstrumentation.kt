package io.nndroid.autospeaker

import android.app.Instrumentation
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/**
 * Runs via `am instrument` from the Shizuku daemon. Grabs the active window's node tree
 * immediately — unlike `uiautomator dump`, it never waits for UI idle, which matters because
 * in-call screens animate forever. Output uses the same node-attribute format that
 * UiDumpParser consumes.
 */
class DumpInstrumentation : Instrumentation() {
    private var visited = 0

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val outFile = File(targetContext.filesDir, DUMP_FILE)
        try {
            var root: AccessibilityNodeInfo? = null
            val deadline = System.currentTimeMillis() + 2000
            while (root == null && System.currentTimeMillis() < deadline) {
                root = runCatching { uiAutomation?.rootInActiveWindow }.getOrNull()
                if (root == null) Thread.sleep(100)
            }

            val sb = StringBuilder()
            if (root == null) {
                sb.append("<ERROR:no active window>")
            } else {
                visited = 0
                sb.append("<nodes>\n")
                appendNode(sb, root, 0)
                sb.append("</nodes>\n")
            }
            outFile.writeText(sb.toString())
        } catch (t: Throwable) {
            runCatching { outFile.writeText("<ERROR:${t.javaClass.simpleName}:${t.message}>") }
        }
        finish(0, Bundle())
    }

    private fun appendNode(sb: StringBuilder, node: AccessibilityNodeInfo, depth: Int) {
        if (visited++ > 3000 || depth > 40) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        sb.append("<node")
        sb.append(" text=\"").append(escape(node.text?.toString().orEmpty())).append("\"")
        sb.append(" content-desc=\"").append(escape(node.contentDescription?.toString().orEmpty())).append("\"")
        sb.append(" resource-id=\"").append(escape(node.viewIdResourceName.orEmpty())).append("\"")
        sb.append(" class=\"").append(escape(node.className?.toString().orEmpty())).append("\"")
        sb.append(" package=\"").append(escape(node.packageName?.toString().orEmpty())).append("\"")
        sb.append(" bounds=\"[${rect.left},${rect.top}][${rect.right},${rect.bottom}]\"")
        sb.append("/>\n")
        for (i in 0 until node.childCount) {
            runCatching { node.getChild(i) }.getOrNull()?.let { appendNode(sb, it, depth + 1) }
        }
    }

    private fun escape(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    companion object {
        const val DUMP_FILE = "autospeaker_fastdump.xml"
    }
}
