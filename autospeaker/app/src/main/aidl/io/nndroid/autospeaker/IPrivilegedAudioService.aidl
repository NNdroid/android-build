package io.nndroid.autospeaker;

interface IPrivilegedAudioService {
    boolean setSpeakerphone(boolean enabled);
    boolean isSpeakerphoneOn();
    String getLastError();
}
