package io.nndroid.autospeaker

import android.os.Binder
import android.os.IBinder
import android.os.Process

object PrivilegedAudioRouter {
    @Volatile
    var lastError: String = ""
        private set

    fun setSpeakerphone(enabled: Boolean): Boolean {
        lastError = ""
        val errors = mutableListOf<String>()

        val serviceResult = runCatching {
            val service = getAudioService()
            val methods = service.javaClass.methods
                .filter { it.name == "setSpeakerphoneOn" }
                .sortedBy { it.parameterCount }

            for (method in methods) {
                val types = method.parameterTypes
                val invoked = when {
                    types.size == 1 && types[0] == Boolean::class.javaPrimitiveType -> {
                        method.invoke(service, enabled)
                        true
                    }
                    types.size == 2 && IBinder::class.java.isAssignableFrom(types[0]) &&
                        types[1] == Boolean::class.javaPrimitiveType -> {
                        method.invoke(service, Binder(), enabled)
                        true
                    }
                    types.size == 3 && IBinder::class.java.isAssignableFrom(types[0]) &&
                        types[1] == Boolean::class.javaPrimitiveType -> {
                        val attribution = buildAttributionSource(types[2])
                        if (attribution != null) {
                            method.invoke(service, Binder(), enabled, attribution)
                            true
                        } else false
                    }
                    else -> false
                }
                if (invoked) {
                    Thread.sleep(180)
                    if (isSpeakerphoneOn() == enabled) return true
                    errors += "IAudioService method ${method.parameterCount} args invoked but state did not change"
                }
            }
            false
        }.onFailure { errors += "IAudioService: ${it.javaClass.simpleName}: ${it.message}" }
            .getOrDefault(false)

        if (serviceResult) return true

        val forceUseRequested = runCatching {
            val cls = Class.forName("android.media.AudioSystem")
            val method = cls.getDeclaredMethod(
                "setForceUse",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            // FOR_COMMUNICATION = 0; FORCE_SPEAKER = 1; FORCE_NONE = 0
            val rc = method.invoke(null, 0, if (enabled) 1 else 0) as? Int ?: -1
            Thread.sleep(180)
            if (rc != 0) errors += "AudioSystem.setForceUse rc=$rc"
            rc == 0
        }.onFailure { errors += "AudioSystem: ${it.javaClass.simpleName}: ${it.message}" }
            .getOrDefault(false)

        // setForceUse returning OK only means the request reached AudioPolicy. Telecom/OEM policy may
        // immediately override it. The caller performs the authoritative communication-device check.
        if (forceUseRequested) {
            lastError = "AudioSystem request accepted; real route must be verified by caller"
            return true
        }

        lastError = errors.joinToString(" | ").ifBlank { "No compatible privileged audio route API" }
        return false
    }

    fun isSpeakerphoneOn(): Boolean = runCatching {
        val service = getAudioService()
        val method = service.javaClass.methods.firstOrNull {
            it.name == "isSpeakerphoneOn" && it.parameterCount == 0
        } ?: return@runCatching false
        method.invoke(service) as? Boolean ?: false
    }.getOrDefault(false)

    private fun getAudioService(): Any {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
        getService.isAccessible = true
        val binder = getService.invoke(null, "audio") as? IBinder
            ?: error("audio binder unavailable")
        val stub = Class.forName("android.media.IAudioService\$Stub")
        val asInterface = stub.getDeclaredMethod("asInterface", IBinder::class.java)
        asInterface.isAccessible = true
        return asInterface.invoke(null, binder) ?: error("IAudioService unavailable")
    }

    private fun buildAttributionSource(expectedType: Class<*>): Any? = runCatching {
        val builderClass = Class.forName("android.content.AttributionSource\$Builder")
        val builder = builderClass
            .getConstructor(Int::class.javaPrimitiveType)
            .newInstance(Process.myUid())
        runCatching {
            builderClass.getMethod("setPackageName", String::class.java)
                .invoke(builder, "io.nndroid.autospeaker")
        }
        val source = builderClass.getMethod("build").invoke(builder)
        if (expectedType.isInstance(source)) source else null
    }.getOrNull()
}
