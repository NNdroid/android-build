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
            text = "AutoSpeaker · vivo X60\n\n来电手动接听后自动尝试：\n1. AudioManager\n2. Shizuku daemon UserService\n3. Root + app_process\n4. 无障碍点击免提\n\nShizuku 后端采用 daemon 模式；App 进程被回收后特权 UserService 仍可保留。"
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
                    ShizukuBridge.lastError == "Shizuku is not running" -> {
                        Toast.makeText(this@MainActivity, "Shizuku 未运行，请先启动 Shizuku", Toast.LENGTH_LONG).show()
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

        layout.addView(Button(this).apply {
            text = "开启无障碍服务"
            setOnClickListener {
                AppLog.i(this@MainActivity, "UI", "opening accessibility settings")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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

    private fun refreshStatus() {
        if (::statusView.isInitialized) {
            statusView.text = buildString {
                append("Shizuku：${ShizukuBridge.status()}\n")
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
