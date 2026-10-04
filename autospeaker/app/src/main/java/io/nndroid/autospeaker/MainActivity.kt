package io.nndroid.autospeaker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {
    private lateinit var tm: TelephonyManager
    @Suppress("DEPRECATION")
    private val listener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) = CallState.onState(state)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad,pad,pad,pad) }
        layout.addView(TextView(this).apply { text = "AutoSpeaker · vivo X60\n\n来电由你手动接听后，应用会尝试直接切换扬声器；若 OriginOS 阻止 AudioManager，则无障碍服务会点击 vivo 通话界面的“免提/扬声器”按钮。\n\n1. 授予电话状态权限\n2. 开启 AutoSpeaker 无障碍服务\n3. 让另一台手机打进来测试"; textSize = 18f })
        layout.addView(Button(this).apply { text = "开启无障碍服务"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
        setContentView(layout)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), 100)
        else startListener()
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
}
