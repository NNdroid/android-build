package io.nndroid.autospeaker

import android.app.Application
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku

class AutoSpeakerApp : Application() {
    override fun onCreate() {
        super.onCreate()

        AppLog.i(this, "App", "process started package=$packageName")
        ShizukuBridge.init(this)

        val provider = packageManager.resolveContentProvider("$packageName.shizuku", 0)
        AppLog.i(
            this,
            "Shizuku",
            "provider=${provider?.name ?: "MISSING"} authority=${provider?.authority ?: "-"} exported=${provider?.exported ?: false}"
        )

        val managerInstalled = runCatching {
            packageManager.getApplicationInfo("moe.shizuku.privileged.api", 0)
            true
        }.getOrDefault(false)
        AppLog.i(this, "Shizuku", "managerInstalled=$managerInstalled binder=${Shizuku.pingBinder()}")

        Handler(Looper.getMainLooper()).postDelayed({
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false) &&
                runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) == PackageManager.PERMISSION_GRANTED
            ) {
                AppLog.i(this, "Shizuku", "binder ready at app startup; warming daemon UserService")
                ShizukuBridge.warmUp(this)
            } else {
                AppLog.i(this, "Shizuku", "startup state=${ShizukuBridge.status()}")
            }
        }, 500)
    }
}
