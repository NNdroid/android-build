package io.nndroid.autospeaker;

interface IPrivilegedAudioService {
    boolean setSpeakerphone(boolean enabled);
    boolean isSpeakerphoneOn();
    boolean setAccessibilityServiceEnabled(boolean enabled);
    String getLastError();
}
