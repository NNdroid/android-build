package io.nndroid.autospeaker

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku

object ShizukuBridge {
    private const val REQUEST_CODE = 2046
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var service: IPrivilegedAudioService? = null
    @Volatile private var binding = false
    @Volatile var lastError: String = ""
        private set

    private var pendingEnabled: Boolean? = null
    private var pendingCallback: ((Boolean, String) -> Unit)? = null

    private fun args(context: Context) = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuAudioService::class.java.name)
    ).processNameSuffix("autospeaker")
        .daemon(false)
        .tag("autospeaker_audio_v2")
        .version(2)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            service = IPrivilegedAudioService.Stub.asInterface(binder)
            val enabled = pendingEnabled
            val callback = pendingCallback
            pendingEnabled = null
            pendingCallback = null
            if (enabled != null && callback != null) execute(enabled, callback)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
        }
    }

    fun requestPermission(): Boolean {
        lastError = ""
        return runCatching {
            if (!Shizuku.pingBinder()) {
                lastError = "Shizuku is not running"
                false
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                true
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                lastError = "Shizuku permission denied"
                false
            } else {
                Shizuku.requestPermission(REQUEST_CODE)
                false
            }
        }.getOrElse {
            lastError = "${it.javaClass.simpleName}: ${it.message}"
            false
        }
    }

    fun status(): String = when {
        !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> "未运行"
        runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) != PackageManager.PERMISSION_GRANTED -> "未授权"
        service != null -> "已连接"
        binding -> "连接中"
        else -> "已授权"
    }

    fun setSpeaker(context: Context, enabled: Boolean, callback: (Boolean, String) -> Unit) {
        lastError = ""
        val ready = runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        if (!ready) {
            lastError = if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                "Shizuku permission not granted"
            } else {
                "Shizuku not running"
            }
            callback(false, lastError)
            return
        }

        service?.let {
            execute(enabled, callback)
            return
        }

        pendingEnabled = enabled
        pendingCallback = callback
        if (binding) return
        binding = true

        runCatching {
            mainHandler.post {
                runCatching { Shizuku.bindUserService(args(context.applicationContext), connection) }
                    .onFailure {
                        binding = false
                        lastError = "Bind failed: ${it.javaClass.simpleName}: ${it.message}"
                        val cb = pendingCallback
                        pendingEnabled = null
                        pendingCallback = null
                        cb?.invoke(false, lastError)
                    }
            }
        }.onFailure {
            binding = false
            lastError = "Bind failed: ${it.javaClass.simpleName}: ${it.message}"
            callback(false, lastError)
        }
    }

    private fun execute(enabled: Boolean, callback: (Boolean, String) -> Unit) {
        Thread {
            val current = service
            if (current == null) {
                callback(false, "Shizuku user service unavailable")
                return@Thread
            }
            val ok = runCatching { current.setSpeakerphone(enabled) }.getOrDefault(false)
            val error = if (ok) "" else runCatching { current.lastError }.getOrDefault("Shizuku route failed")
            if (!ok) lastError = error
            mainHandler.post { callback(ok, error) }
        }.start()
    }
}
