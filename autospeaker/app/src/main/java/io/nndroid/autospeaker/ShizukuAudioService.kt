package io.nndroid.autospeaker

import android.content.Context
import androidx.annotation.Keep

@Keep
class ShizukuAudioService : IPrivilegedAudioService.Stub() {
    constructor()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    override fun setSpeakerphone(enabled: Boolean): Boolean =
        PrivilegedAudioRouter.setSpeakerphone(enabled)

    override fun isSpeakerphoneOn(): Boolean =
        PrivilegedAudioRouter.isSpeakerphoneOn()

    override fun getLastError(): String = PrivilegedAudioRouter.lastError
}
