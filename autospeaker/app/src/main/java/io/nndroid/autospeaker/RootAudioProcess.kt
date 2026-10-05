package io.nndroid.autospeaker

object RootAudioProcess {
    // Keep the process (and its binder) alive after enabling: on Android 12+ the
    // communication-device request is tracked per client and cleaned up on client death,
    // so an app_process that exits immediately would silently undo its own route request.
    private const val HOLD_MS = 10_000L

    @JvmStatic
    fun main(args: Array<String>) {
        val enabled = args.firstOrNull()?.equals("on", ignoreCase = true) == true
        val ok = PrivilegedAudioRouter.setSpeakerphone(enabled)
        if (!ok) {
            System.err.println("ERROR:${PrivilegedAudioRouter.lastError}")
            kotlin.system.exitProcess(1)
        }
        println("OK")
        System.out.flush()
        if (enabled) Thread.sleep(HOLD_MS)
    }
}
