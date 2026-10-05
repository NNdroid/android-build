package io.nndroid.autospeaker;

interface IPrivilegedAudioService {
    boolean setSpeakerphone(boolean enabled);
    boolean isSpeakerphoneOn();
    String getLastError();

    /** Dumps the active window with uiautomator; returns "OK:<xml>" or "ERR:<reason>". */
    String dumpUi();

    /** Returns the focused window's package name (fast, via dumpsys), or "" on failure. */
    String currentWindowPackage();

    /** Injects a tap with the shell identity's INJECT_EVENTS permission. */
    boolean inputTap(int x, int y);

    /** Arms a getevent reader that records the next touch tap (root/shell privilege). */
    boolean startTouchCapture();

    /** Returns "<tapCount>:<screenX>,<screenY>" of the latest captured tap. */
    String pollTouchCapture();

    /** Stops the getevent reader. */
    void stopTouchCapture();
}
