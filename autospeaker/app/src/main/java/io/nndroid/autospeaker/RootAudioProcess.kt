package io.nndroid.autospeaker

object RootAudioProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val enabled = args.firstOrNull()?.equals("on", ignoreCase = true) == true
        val ok = PrivilegedAudioRouter.setSpeakerphone(enabled)
        if (ok) {
            println("OK")
        } else {
            System.err.println("ERROR:${PrivilegedAudioRouter.lastError}")
            kotlin.system.exitProcess(1)
        }
    }
}
