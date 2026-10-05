package io.nndroid.autospeaker

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {
    private lateinit var tm: TelephonyManager
    private lateinit var statusView: TextView
    private lateinit var modeButton: Button
    private lateinit var logView: TextView
    private val handler = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    private val listener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) = CallState.onState(state)
    }

    private val statusRefresh = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        val message = if (granted) "Shizuku 授权成功" else "Shizuku 授权被拒绝"
        AppLog.i(this, "UI", message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        if (granted) ShizukuBridge.warmUp(this)
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuBridge.init(this)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        AppLog.i(this, "App", "AutoSpeaker started version=${BuildConfig.VERSION_NAME}")

        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(layout)

        layout.addView(TextView(this).apply {
            text = "AutoSpeaker · 事件驱动自动免提\n\n来电接听后监听 MODE_IN_CALL 与通信设备变化，在 5 秒窗口内最多重试，被 Telecom 重置后自动补回。\n\n路由模式：\n• Shizuku 主路径（默认，全程不需要无障碍）：AudioManager → Mode 接管（部分 ROM 支持）→ Shizuku 在通话界面点免提 → Shizuku / Root 音频兜底\n• 无障碍主路径：AudioManager → 无障碍点击免提 → Mode 接管 / Shizuku UI / 音频、Root 兜底\n\n首次通话（还没有学习指纹）时：像平时一样手动点一次免提按钮即可，应用会从触摸事件自动学习它的位置（无需无障碍）；之后每次通话自动秒切。“学习免提按钮”为备用手段。"
            textSize = 17f
        })

        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        layout.addView(statusView)

        layout.addView(Button(this).apply {
            text = "授权 / 检查 Shizuku"
            setOnClickListener {
                AppLog.i(this@MainActivity, "UI", "Shizuku check button pressed")
                val granted = ShizukuBridge.requestPermission(this@MainActivity)
                refreshStatus()
                when {
                    granted -> {
                        ShizukuBridge.warmUp(this@MainActivity)
                        Toast.makeText(this@MainActivity, "Shizuku 已授权，正在连接 UserService", Toast.LENGTH_SHORT).show()
                    }
                    ShizukuBridge.lastError.startsWith("Shizuku Binder not received") -> {
                        Toast.makeText(
                            this@MainActivity,
                            "未收到 Shizuku Binder。请确认 Shizuku 首页显示服务正在运行，然后返回重试。",
                            Toast.LENGTH_LONG
                        ).show()
                        packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { launch ->
                            runCatching { startActivity(launch) }
                        }
                    }
                    ShizukuBridge.lastError.isNotBlank() -> {
                        Toast.makeText(this@MainActivity, "Shizuku：${ShizukuBridge.lastError}", Toast.LENGTH_LONG).show()
                    }
                    else -> Toast.makeText(this@MainActivity, "已请求 Shizuku 授权，请确认弹窗", Toast.LENGTH_LONG).show()
                }
            }
        })

        modeButton = Button(this).apply {
            setOnClickListener {
                val newMode = if (SettingsStore.routeMode(this@MainActivity) == SettingsStore.MODE_SHIZUKU) {
                    SettingsStore.MODE_ACCESSIBILITY
                } else {
                    SettingsStore.MODE_SHIZUKU
                }
                SettingsStore.setRouteMode(this@MainActivity, newMode)
                AppLog.i(this@MainActivity, "UI", "route mode switched to $newMode")
                Toast.makeText(
                    this@MainActivity,
                    if (newMode == SettingsStore.MODE_SHIZUKU) "路由模式：Shizuku 主路径（无障碍不再参与自动路由）"
                    else "路由模式：无障碍主路径（Shizuku 作为兜底）",
                    Toast.LENGTH_SHORT
                ).show()
                refreshStatus()
            }
        }
        layout.addView(modeButton)

        layout.addView(Button(this).apply {
            text = "开启无障碍（学习免提按钮时需要）"
            setOnClickListener {
                AppLog.i(this@MainActivity, "UI", "opening accessibility settings configured=${SpeakerAccessibilityService.isEnabledInSettings(this@MainActivity)} connected=${SpeakerAccessibilityService.isConnected()}")
                Toast.makeText(this@MainActivity, "请在无障碍列表中找到 AutoSpeaker 并打开开关，然后返回本应用确认显示“已连接”", Toast.LENGTH_LONG).show()
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .onFailure {
                        AppLog.e(this@MainActivity, "UI", "failed to open accessibility settings", it)
                        Toast.makeText(this@MainActivity, "无法打开无障碍设置：${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
        })

        layout.addView(Button(this).apply {
            text = "学习免提按钮（图标无文字时）"
            setOnClickListener {
                if (!SpeakerAccessibilityService.isConnected()) {
                    Toast.makeText(this@MainActivity, "请先开启 AutoSpeaker 无障碍服务", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                SpeakerAccessibilityService.startLearning(this@MainActivity)
                AppLog.i(this@MainActivity, "UI", "speaker button learning requested")
                Toast.makeText(
                    this@MainActivity,
                    "学习模式已开启 30 秒。切回正在通话的 vivo 电话界面，手动点一次真正的扬声器图标；只有检测到实际切到扬声器才会保存。学习到的指纹同时供无障碍与 Shizuku UI 后端使用。",
                    Toast.LENGTH_LONG
                ).show()
                refreshStatus()
            }
        })

        layout.addView(Button(this).apply {
            text = "清除已学习免提按钮"
            setOnClickListener {
                SpeakerAccessibilityService.clearLearnedFingerprint(this@MainActivity)
                Toast.makeText(this@MainActivity, "已清除免提按钮学习记录", Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        })

        layout.addView(TextView(this).apply {
            text = "运行日志（最近 300 行）"
            textSize = 17f
            setPadding(0, pad, 0, pad / 3)
        })

        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, pad / 2)
        }
        layout.addView(logView)

        layout.addView(Button(this).apply {
            text = "刷新日志"
            setOnClickListener { refreshStatus() }
        })

        layout.addView(Button(this).apply {
            text = "复制日志"
            setOnClickListener {
                val logs = AppLog.read(this@MainActivity)
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("AutoSpeaker logs", logs))
                Toast.makeText(this@MainActivity, "日志已复制", Toast.LENGTH_SHORT).show()
            }
        })

        layout.addView(Button(this).apply {
            text = "清空日志"
            setOnClickListener {
                AppLog.clear(this@MainActivity)
                AppLog.i(this@MainActivity, "App", "log cleared by user")
                refreshStatus()
            }
        })

        setContentView(root)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), 100)
        } else {
            startListener()
        }
        handler.post(statusRefresh)
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) {
            val configured = SpeakerAccessibilityService.isEnabledInSettings(this)
            val connected = SpeakerAccessibilityService.isConnected()
            AppLog.i(this, "Accessibility", "resume configured=$configured connected=$connected")
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        if (::modeButton.isInitialized) {
            modeButton.text = when (SettingsStore.routeMode(this)) {
                SettingsStore.MODE_ACCESSIBILITY -> "路由模式：无障碍主路径（点击切换）"
                else -> "路由模式：Shizuku 主路径（点击切换）"
            }
        }
        if (::statusView.isInitialized) {
            val provider = packageManager.resolveContentProvider("$packageName.shizuku", 0)
            val accessibilityConfigured = SpeakerAccessibilityService.isEnabledInSettings(this)
            val accessibilityConnected = SpeakerAccessibilityService.isConnected()
            val learning = SpeakerAccessibilityService.isLearning(this)
            val learned = SpeakerAccessibilityService.hasLearnedFingerprint(this)
            statusView.text = buildString {
                append("路由模式：${if (SettingsStore.routeMode(this@MainActivity) == SettingsStore.MODE_SHIZUKU) "Shizuku 主路径" else "无障碍主路径"}\n")
                append("Shizuku Provider：${if (provider != null) "已注册" else "缺失"}\n")
                append("Shizuku：${ShizukuBridge.status()}\n")
                append("无障碍设置：${if (accessibilityConfigured) "已开启" else "未开启"}\n")
                append("无障碍服务：${if (accessibilityConnected) "已连接" else "未连接"}\n")
                append("免提按钮学习：${when { learning -> "学习中（请手动点一次扬声器图标）"; learned -> "已学习"; else -> "未学习" }}\n")
                if (accessibilityConfigured && !accessibilityConnected) {
                    append("无障碍提示：开关已开但服务未绑定，请关闭后重新开启一次\n")
                }
                if (ShizukuBridge.lastError.isNotBlank()) append("Shizuku 信息：${ShizukuBridge.lastError}\n")
                append("当前后端：${CallState.lastBackend.ifBlank { "尚未执行" }}")
                if (CallState.lastError.isNotBlank()) append("\n最后错误：${CallState.lastError}")
            }
        }
        if (::logView.isInitialized) logView.text = AppLog.read(this)
    }

    @Suppress("DEPRECATION")
    private fun startListener() {
        tm = getSystemService(TelephonyManager::class.java)
        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        AppLog.i(this, "Call", "PhoneStateListener registered")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            AppLog.i(this, "Permission", "READ_PHONE_STATE granted")
            startListener()
        } else if (requestCode == 100) {
            AppLog.w(this, "Permission", "READ_PHONE_STATE denied")
        }
    }

    override fun onDestroy() {
        AppLog.i(this, "App", "MainActivity destroyed")
        handler.removeCallbacks(statusRefresh)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }
}
