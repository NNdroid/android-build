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
        .tag("autospeaker_audio_v4")
        .version(4)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        val context = appContext ?: return@OnBinderReceivedListener
        lastError = ""
        AppLog.i(context, "Shizuku", "binder received uid=${runCatching { Shizuku.getUid() }.getOrDefault(-1)}")
        if (runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) == PackageManager.PERMISSION_GRANTED) {
            warmUp(context)
        } else {
            AppLog.i(context, "Shizuku", "binder alive but permission not granted")
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        val context = appContext
        service = null
        binding = false
        lastError = "Shizuku Binder disconnected"
        if (context != null) AppLog.w(context, "Shizuku", "binder died; waiting for Shizuku server to return")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            service = IPrivilegedAudioService.Stub.asInterface(binder)
            appContext?.let { AppLog.i(it, "Shizuku", "UserService connected component=$name") }
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
            appContext?.let { AppLog.w(it, "Shizuku", "UserService disconnected component=$name") }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (initialized) return
        initialized = true
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        val provider = context.packageManager.resolveContentProvider("${context.packageName}.shizuku", 0)
        AppLog.i(
            context,
            "Shizuku",
            "bridge initialized provider=${provider?.name ?: "MISSING"} authority=${provider?.authority ?: "-"} binder=${runCatching { Shizuku.pingBinder() }.getOrDefault(false)}"
        )
    }

    fun requestPermission(context: Context? = appContext): Boolean {
        context?.let { init(it) }
        lastError = ""
        return runCatching {
            if (!Shizuku.pingBinder()) {
                lastError = "Shizuku Binder not received (server stopped or binder delivery failed)"
                context?.let {
                    val provider = it.packageManager.resolveContentProvider("${it.packageName}.shizuku", 0)
                    AppLog.w(it, "Shizuku", "$lastError; provider=${provider?.name ?: "MISSING"}")
                }
                false
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                context?.let {
                    AppLog.i(it, "Shizuku", "permission already granted uid=${runCatching { Shizuku.getUid() }.getOrDefault(-1)}")
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
        !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> "未连接（未收到 Binder）"
        runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) != PackageManager.PERMISSION_GRANTED -> "已连接，未授权"
        service != null -> "已连接"
        binding -> "已授权，UserService 连接中"
        else -> "已授权"
    }

    fun warmUp(context: Context) {
        init(context)
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            AppLog.w(context, "Shizuku", "warmUp skipped: binder unavailable")
            return
        }
        if (runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) != PackageManager.PERMISSION_GRANTED) {
            AppLog.i(context, "Shizuku", "warmUp skipped: permission not granted")
            return
        }
        if (service != null || binding) return

        binding = true
        AppLog.i(context, "Shizuku", "binding daemon UserService uid=${runCatching { Shizuku.getUid() }.getOrDefault(-1)}")
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
                "Shizuku Binder unavailable"
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
