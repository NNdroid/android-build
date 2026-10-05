package io.nndroid.autospeaker

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object ShizukuBridge {
    private const val REQUEST_CODE = 2046
    private const val CALL_TIMEOUT_MS = 3000L
    private const val DUMP_TIMEOUT_MS = 15_000L
    private const val TAP_TIMEOUT_MS = 6000L
    private const val WINDOW_TIMEOUT_MS = 4000L
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var service: IPrivilegedAudioService? = null
    @Volatile private var binding = false
    @Volatile private var initialized = false
    @Volatile private var appContext: Context? = null
    @Volatile var lastError: String = ""
        private set

    @Volatile private var pendingOnReady: ((IPrivilegedAudioService) -> Unit)? = null
    @Volatile private var pendingOnError: ((String) -> Unit)? = null

    private fun args(context: Context) = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuAudioService::class.java.name)
    ).processNameSuffix("autospeaker")
        .daemon(true)
        .tag("autospeaker_audio")
        // The daemon survives APK updates; tie the version to versionCode so Shizuku
        // restarts it with the new code after every app upgrade.
        .version(BuildConfig.VERSION_CODE)

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
        val errCb = pendingOnError
        pendingOnReady = null
        pendingOnError = null
        errCb?.invoke(lastError)
        if (context != null) AppLog.w(context, "Shizuku", "binder died; waiting for Shizuku server to return")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            val iface = binder?.let { IPrivilegedAudioService.Stub.asInterface(it) }
            service = iface
            appContext?.let { AppLog.i(it, "Shizuku", "UserService connected component=$name") }
            val onReady = pendingOnReady
            val onError = pendingOnError
            pendingOnReady = null
            pendingOnError = null
            if (iface != null) onReady?.invoke(iface) else onError?.invoke("UserService connected without binder")
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

    fun isReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun warmUp(context: Context) {
        init(context)
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            AppLog.w(context, "Shizuku", "warmUp skipped: binder unavailable")
            return
        }
        if (service != null || binding) return

        AppLog.i(context, "Shizuku", "warming daemon UserService uid=${runCatching { Shizuku.getUid() }.getOrDefault(-1)}")
        ensureService(context,
            onReady = { appContext?.let { AppLog.i(it, "Shizuku", "UserService warmed up") } },
            onError = { appContext?.let { AppLog.w(it, "Shizuku", "warm up failed: $it") } }
        )
    }

    // ------------------------------------------------------------------
    // Privileged calls (all bounded by a timeout so a hung daemon cannot
    // stall the fallback chain)
    // ------------------------------------------------------------------

    fun setSpeaker(context: Context, enabled: Boolean, callback: (Boolean, String) -> Unit) {
        lastError = ""
        AppLog.i(context, "Shizuku", "setSpeaker enabled=$enabled status=${status()}")
        ensureService(context,
            onReady = { _ ->
                invokeOnService(CALL_TIMEOUT_MS, { svc ->
                    val ok = runCatching { svc.setSpeakerphone(enabled) }.getOrDefault(false)
                    val detail = if (ok) "" else runCatching { svc.lastError }.getOrDefault("Shizuku route failed")
                    ok to detail
                }) { result ->
                    val (ok, detail) = result.getOrElse { false to (it.message ?: "Shizuku route failed") }
                    if (!ok) lastError = detail
                    appContext?.let {
                        if (ok) AppLog.i(it, "Shizuku", "speaker route succeeded")
                        else AppLog.w(it, "Shizuku", "speaker route failed: $detail")
                    }
                    callback(ok, detail)
                }
            },
            onError = { callback(false, it) }
        )
    }

    /** Returns "OK:<xml>" on success, "ERR:<reason>" or null on failure. */
    fun dumpUi(context: Context, callback: (String?) -> Unit) {
        ensureService(context,
            onReady = { _ ->
                invokeOnService(DUMP_TIMEOUT_MS, { svc -> svc.dumpUi() }) { result ->
                    val value = result.getOrNull()
                    if (value == null) {
                        AppLog.w(context, "Shizuku", "dumpUi failed: ${result.exceptionOrNull()?.message}")
                    }
                    callback(value)
                }
            },
            onError = {
                AppLog.w(context, "Shizuku", "dumpUi unavailable: $it")
                callback("ERR:$it")
            }
        )
    }

    fun inputTap(context: Context, x: Int, y: Int, callback: (Boolean) -> Unit) {
        ensureService(context,
            onReady = { _ ->
                invokeOnService(TAP_TIMEOUT_MS, { svc ->
                    runCatching { svc.inputTap(x, y) }.getOrDefault(false)
                }) { result ->
                    callback(result.getOrElse { false })
                }
            },
            onError = {
                AppLog.w(context, "Shizuku", "inputTap unavailable: $it")
                callback(false)
            }
        )
    }

    /** Focused window package via dumpsys; "" on any failure. */
    fun currentWindowPackage(context: Context, callback: (String) -> Unit) {
        ensureService(context,
            onReady = { _ ->
                invokeOnService(WINDOW_TIMEOUT_MS, { svc ->
                    runCatching { svc.currentWindowPackage() }.getOrDefault("")
                }) { result ->
                    callback(result.getOrElse { "" })
                }
            },
            onError = {
                AppLog.w(context, "Shizuku", "currentWindowPackage unavailable: $it")
                callback("")
            }
        )
    }

    // Touch capture: learn the speaker button from the user's own manual tap, read from
    // /dev/input by the daemon (root/shell privilege) — no accessibility involved.

    fun startTouchCapture(context: Context, callback: (Boolean) -> Unit) {
        ensureService(context,
            onReady = { _ ->
                invokeOnService(CALL_TIMEOUT_MS, { svc ->
                    runCatching { svc.startTouchCapture() }.getOrDefault(false)
                }) { result ->
                    callback(result.getOrElse { false })
                }
            },
            onError = {
                AppLog.w(context, "Shizuku", "startTouchCapture unavailable: $it")
                callback(false)
            }
        )
    }

    /** "<tapCount>:<screenX>,<screenY>"; "-1,-1" when no tap captured yet. */
    fun pollTouchCapture(context: Context, callback: (String) -> Unit) {
        ensureService(context,
            onReady = { _ ->
                invokeOnService(2000, { svc ->
                    runCatching { svc.pollTouchCapture() }.getOrDefault("0:-1,-1")
                }) { result ->
                    callback(result.getOrElse { "0:-1,-1" })
                }
            },
            onError = { callback("0:-1,-1") }
        )
    }

    fun stopTouchCapture(context: Context) {
        ensureService(context,
            onReady = { _ ->
                invokeOnService(CALL_TIMEOUT_MS, { svc ->
                    runCatching { svc.stopTouchCapture() }.isSuccess
                }) { }
            },
            onError = { }
        )
    }

    private fun ensureService(
        context: Context,
        onReady: (IPrivilegedAudioService) -> Unit,
        onError: (String) -> Unit
    ) {
        init(context)
        if (!isReady()) {
            val err = if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                "Shizuku permission not granted"
            } else {
                "Shizuku Binder unavailable"
            }
            lastError = err
            AppLog.w(context, "Shizuku", err)
            onError(err)
            return
        }

        service?.let {
            onReady(it)
            return
        }

        pendingOnReady = onReady
        pendingOnError = onError
        if (binding) return
        binding = true

        mainHandler.post {
            runCatching { Shizuku.bindUserService(args(context.applicationContext), connection) }
                .onFailure {
                    binding = false
                    lastError = "Bind failed: ${it.javaClass.simpleName}: ${it.message}"
                    AppLog.e(context, "Shizuku", "bind failed", it)
                    val errCb = pendingOnError
                    pendingOnReady = null
                    pendingOnError = null
                    errCb?.invoke(lastError)
                }
        }
    }

    private fun <T> invokeOnService(
        timeoutMs: Long,
        call: (IPrivilegedAudioService) -> T,
        onDone: (Result<T>) -> Unit
    ) {
        Thread {
            val current = service
            if (current == null) {
                onDone(Result.failure(IllegalStateException("Shizuku user service unavailable")))
                return@Thread
            }
            val executor = Executors.newSingleThreadExecutor()
            val result = try {
                val future = executor.submit(Callable { call(current) })
                try {
                    Result.success(future.get(timeoutMs, TimeUnit.MILLISECONDS))
                } catch (e: TimeoutException) {
                    future.cancel(true)
                    // Treat the daemon as stale so the next call rebinds instead of
                    // talking to a possibly hung service forever.
                    service = null
                    binding = false
                    Result.failure(TimeoutException("Shizuku call timed out after ${timeoutMs}ms"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                executor.shutdownNow()
            }
            mainHandler.post { onDone(result) }
        }.start()
    }
}
