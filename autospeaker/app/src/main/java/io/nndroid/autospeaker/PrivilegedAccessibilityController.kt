package io.nndroid.autospeaker

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object PrivilegedAccessibilityController {
    @Volatile var lastError: String = ""
        private set

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        lastError = ""
        val component = ComponentName(context.packageName, SpeakerAccessibilityService::class.java.name)
        val flattened = component.flattenToString()
        return runCatching {
            val resolver = context.contentResolver
            val current = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()

            val services = current.split(':')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()

            if (enabled) services += flattened else services -= flattened

            val servicesOk = Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                services.joinToString(":")
            )
            val enabledOk = Settings.Secure.putInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                if (services.isNotEmpty()) 1 else 0
            )

            if (!servicesOk || !enabledOk) {
                lastError = "Settings.Secure rejected accessibility update"
                false
            } else {
                Thread.sleep(250)
                val verify = Settings.Secure.getString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty().split(':').any { it.equals(flattened, ignoreCase = true) }
                if (verify == enabled) {
                    true
                } else {
                    lastError = "Accessibility secure-setting verification failed"
                    false
                }
            }
        }.getOrElse {
            lastError = "${it.javaClass.simpleName}: ${it.message}"
            false
        }
    }
}
