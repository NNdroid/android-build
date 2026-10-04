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
                    Thread.sleep(120)
                    if (isSpeakerphoneOn()) return true
                }
            }
            false
        }.onFailure { errors += "IAudioService: ${it.javaClass.simpleName}: ${it.message}" }
            .getOrDefault(false)

        if (serviceResult) return true

        val forceUseResult = runCatching {
            val cls = Class.forName("android.media.AudioSystem")
            val method = cls.getDeclaredMethod(
                "setForceUse",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            // FOR_COMMUNICATION = 0; FORCE_SPEAKER = 1; FORCE_NONE = 0
            val rc = method.invoke(null, 0, if (enabled) 1 else 0) as? Int ?: -1
            Thread.sleep(120)
            rc == 0 || isSpeakerphoneOn() == enabled
        }.onFailure { errors += "AudioSystem: ${it.javaClass.simpleName}: ${it.message}" }
            .getOrDefault(false)

        if (!forceUseResult) lastError = errors.joinToString(" | ").ifBlank { "No compatible privileged audio route API" }
        return forceUseResult
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
