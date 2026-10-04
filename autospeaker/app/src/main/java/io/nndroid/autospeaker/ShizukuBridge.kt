package io.nndroid.autospeaker

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku

object ShizukuBridge {
    private const val REQUEST_CODE = 2046
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var service: IPrivilegedAudioService? = null
    @Volatile private var binding = false
    @Volatile private var initialized = false
    @Volatile private var appContext: Context? = null
    @Volatile var lastError: String = ""
        private set

    private var pendingEnabled: Boolean? = null
    private var pendingCallback: ((Boolean, String) -> Unit)? = null

    private fun args(context: Context) = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuAudioService::class.java.name)
    ).processNameSuffix("autospeaker")
        .daemon(true)
        .tag("autospeaker_audio_v3")
        .version(3)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        val context = appContext ?: return@OnBinderReceivedListener
        AppLog.i(context, "Shizuku", "binder received uid=${runCatching { Shizuku.getUid() }.getOrDefault(-1)}")
        if (runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) == PackageManager.PERMISSION_GRANTED) {
            warmUp(context)
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        val context = appContext
        service = null
        binding = false
        lastError = "Shizuku binder died"
        if (context != null) AppLog.w(context, "Shizuku", "binder died; UserService will be rebound when Shizuku returns")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            service = IPrivilegedAudioService.Stub.asInterface(binder)
            appContext?.let { AppLog.i(it, "Shizuku", "UserService connected") }
            val enabled = pendingEnabled
            val callback = pendingCallback
            pendingEnabled = null
            pendingCallback = null
            if (enabled != null && callback != null) execute(enabled, callback)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            lastError = "Shizuku UserService disconnected"
            appContext?.let { AppLog.w(it, "Shizuku", "UserService disconnected") }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (initialized) return
        initialized = true
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        AppLog.i(context, "Shizuku", "bridge initialized")
    }

    fun requestPermission(context: Context? = appContext): Boolean {
        context?.let { init(it) }
        lastError = ""
        return runCatching {
            if (!Shizuku.pingBinder()) {
                lastError = "Shizuku is not running"
                context?.let { AppLog.w(it, "Shizuku", lastError) }
                false
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                context?.let {
                    AppLog.i(it, "Shizuku", "permission already granted")
                    warmUp(it)
                }
                true
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                lastError = "Shizuku permission denied"
                context?.let { AppLog.w(it, "Shizuku", lastError) }
                false
            } else {
                context?.let { AppLog.i(it, "Shizuku", "requesting permission") }
                Shizuku.requestPermission(REQUEST_CODE)
                false
            }
        }.getOrElse {
            lastError = "${it.javaClass.simpleName}: ${it.message}"
            context?.let { c -> AppLog.e(c, "Shizuku", "permission check failed", it) }
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

    fun warmUp(context: Context) {
        init(context)
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return
        if (runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) != PackageManager.PERMISSION_GRANTED) return
        if (service != null || binding) return

        binding = true
        AppLog.i(context, "Shizuku", "binding daemon UserService")
        mainHandler.post {
            runCatching { Shizuku.bindUserService(args(context.applicationContext), connection) }
                .onFailure {
                    binding = false
                    lastError = "Bind failed: ${it.javaClass.simpleName}: ${it.message}"
                    AppLog.e(context, "Shizuku", "UserService bind failed", it)
                }
        }
    }

    fun setSpeaker(context: Context, enabled: Boolean, callback: (Boolean, String) -> Unit) {
        init(context)
        lastError = ""
        AppLog.i(context, "Shizuku", "setSpeaker enabled=$enabled status=${status()}")

        val ready = runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        if (!ready) {
            lastError = if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                "Shizuku permission not granted"
            } else {
                "Shizuku not running"
            }
            AppLog.w(context, "Shizuku", lastError)
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

        mainHandler.post {
            runCatching { Shizuku.bindUserService(args(context.applicationContext), connection) }
                .onFailure {
                    binding = false
                    lastError = "Bind failed: ${it.javaClass.simpleName}: ${it.message}"
                    AppLog.e(context, "Shizuku", "bind failed", it)
                    val cb = pendingCallback
                    pendingEnabled = null
                    pendingCallback = null
                    cb?.invoke(false, lastError)
                }
        }
    }

    private fun execute(enabled: Boolean, callback: (Boolean, String) -> Unit) {
        Thread {
            val current = service
            if (current == null) {
                val error = "Shizuku user service unavailable"
                appContext?.let { AppLog.w(it, "Shizuku", error) }
                mainHandler.post { callback(false, error) }
                return@Thread
            }
            val ok = runCatching { current.setSpeakerphone(enabled) }.getOrDefault(false)
            val error = if (ok) "" else runCatching { current.lastError }.getOrDefault("Shizuku route failed")
            if (!ok) lastError = error
            appContext?.let {
                if (ok) AppLog.i(it, "Shizuku", "speaker route succeeded")
                else AppLog.w(it, "Shizuku", "speaker route failed: $error")
            }
            mainHandler.post { callback(ok, error) }
        }.start()
    }
}
