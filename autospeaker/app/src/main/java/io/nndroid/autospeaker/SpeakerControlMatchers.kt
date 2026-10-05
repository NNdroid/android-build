package io.nndroid.autospeaker

/**
 * Text matching rules shared by the accessibility service and the Shizuku UI-dump parser so
 * both UI backends identify the same speaker control.
 */
object SpeakerControlMatchers {
    val labels = listOf("免提", "扬声器", "Speaker", "Speakerphone", "Handsfree", "Hands-free")
    val hints = listOf("speaker", "speakerphone", "handsfree", "hands_free", "audio_route", "免提", "扬声器")

    fun matchHint(text: String, desc: String, viewId: String): String? =
        hints.firstOrNull {
            text.contains(it, ignoreCase = true) ||
                desc.contains(it, ignoreCase = true) ||
                viewId.contains(it, ignoreCase = true)
        }
}
