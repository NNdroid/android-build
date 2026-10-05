package io.nndroid.autospeaker

import android.content.Context

object SettingsStore {
    const val MODE_SHIZUKU = "shizuku"
    const val MODE_ACCESSIBILITY = "accessibility"

    private const val PREFS = "autospeaker_settings"
    private const val KEY_ROUTE_MODE = "route_mode"

    /** Shizuku-first is the default: routing does not depend on the accessibility service. */
    fun routeMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROUTE_MODE, MODE_SHIZUKU) ?: MODE_SHIZUKU

    fun setRouteMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ROUTE_MODE, mode).apply()
    }
}
