package io.nndroid.autospeaker

import android.Manifest
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {
    private lateinit var tm: TelephonyManager
    private lateinit var statusView: TextView
    private val handler = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    private val listener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) = CallState.onState(state)
    }

    private val statusRefresh = object : Runnable {
        override fun run() {
            if (::statusView.isInitialized) {
                statusView.text = buildString {
                    append("Shizuku：${ShizukuBridge.status()}\n")
                    if (ShizukuBridge.lastError.isNotBlank()) {
                        append("Shizuku 信息：${ShizukuBridge.lastError}\n")
                    }
                    append("当前后端：${CallState.lastBackend}")
                    if (CallState.lastError.isNotBlank()) append("\n最后错误：${CallState.lastError}")
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        val message = if (grantResult == PackageManager.PERMISSION_GRANTED) {
            "Shizuku 授权成功"
        } else {
            "Shizuku 授权被拒绝"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        handler.post(statusRefresh)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        layout.addView(TextView(this).apply {
            text = "AutoSpeaker · vivo X60\n\n手动接听来电后，按以下顺序自动尝试开启免提：\n\n1. Android AudioManager\n2. Shizuku UserService（shell/root）\n3. Root + app_process\n4. 无障碍自动点击“免提/扬声器”\n\n建议同时授权 Shizuku 和无障碍；没有 Root 也可以正常使用前两级和最终回退。"
            textSize = 18f
        })

        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        layout.addView(statusView)

        layout.addView(Button(this).apply {
            text = "授权 / 检查 Shizuku"
            setOnClickListener {
                val granted = ShizukuBridge.requestPermission()
                handler.postDelayed(statusRefresh, 300)

                when {
                    granted -> Toast.makeText(this@MainActivity, "Shizuku 已授权", Toast.LENGTH_SHORT).show()
                    ShizukuBridge.lastError == "Shizuku is not running" -> {
                        Toast.makeText(this@MainActivity, "Shizuku 未运行，请先启动 Shizuku", Toast.LENGTH_LONG).show()
                        packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { launch ->
                            runCatching { startActivity(launch) }
                        }
                    }
                    ShizukuBridge.lastError.isNotBlank() -> {
                        Toast.makeText(this@MainActivity, "Shizuku：${ShizukuBridge.lastError}", Toast.LENGTH_LONG).show()
                    }
                    else -> Toast.makeText(this@MainActivity, "已请求 Shizuku 授权，请确认授权弹窗", Toast.LENGTH_LONG).show()
                }
            }
        })

        layout.addView(Button(this).apply {
            text = "开启无障碍服务"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        setContentView(layout)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), 100)
        } else {
            startListener()
        }
        handler.post(statusRefresh)
    }

    @Suppress("DEPRECATION")
    private fun startListener() {
        tm = getSystemService(TelephonyManager::class.java)
        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListener()
    }

    override fun onDestroy() {
        handler.removeCallbacks(statusRefresh)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }
}
