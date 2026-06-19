package com.xiaozhi.notify

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var edtToken: TextInputEditText
    private lateinit var lblAccess: TextView
    private lateinit var lblInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        edtToken = findViewById(R.id.edtToken)
        lblAccess = findViewById(R.id.lblAccess)
        lblInfo = findViewById(R.id.lblInfo)
        edtToken.setText(prefs.token)

        findViewById<MaterialButton>(R.id.btnGrant).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.btnSaveToken).setOnClickListener {
            prefs.token = edtToken.text?.toString().orEmpty()
            toast("Đã lưu token")
            updateInfo()
        }
        findViewById<MaterialSwitch>(R.id.swEnabled).apply {
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, v -> prefs.enabled = v }
        }
        findViewById<MaterialSwitch>(R.id.swOngoing).apply {
            isChecked = prefs.includeOngoing
            setOnCheckedChangeListener { _, v -> prefs.includeOngoing = v }
        }
        findViewById<MaterialButton>(R.id.btnDiscover).setOnClickListener {
            toast("Đang tìm...")
            Net.discoverAsync(this) { found ->
                toast(if (found) "Đã tìm thấy đồng hồ" else "Không thấy — cùng WiFi với đồng hồ chưa?")
                updateInfo()
            }
        }
        findViewById<MaterialButton>(R.id.btnTest).setOnClickListener {
            if (prefs.token.isEmpty()) { toast("Nhập token trước"); return@setOnClickListener }
            Net.send(this, "XiaoZhi Notify", "Thông báo thử", "Nếu đồng hồ hiện dòng này, kết nối đã hoạt động.")
            toast("Đã gửi thử")
        }
    }

    override fun onResume() {
        super.onResume()
        Net.statusListener = { runOnUiThread { updateInfo() } }
        updateAccess()
        updateInfo()
    }

    override fun onPause() {
        super.onPause()
        Net.statusListener = null
    }

    private fun updateAccess() {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        lblAccess.text = if (enabled) "Trạng thái quyền: ĐÃ cấp ✓"
                         else "Trạng thái quyền: CHƯA cấp — bấm nút trên"
    }

    private fun updateInfo() {
        val host = prefs.host.ifEmpty { "(chưa tìm thấy)" }
        lblInfo.text = "Đồng hồ: $host\nID máy: ${prefs.deviceId}\n${prefs.lastStatus}"
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
