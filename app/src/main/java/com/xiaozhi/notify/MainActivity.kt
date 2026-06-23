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
    private lateinit var edtName: TextInputEditText
    private lateinit var edtHost: TextInputEditText
    private lateinit var lblAccess: TextView
    private lateinit var lblInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        edtName = findViewById(R.id.edtName)
        edtHost = findViewById(R.id.edtHost)
        lblAccess = findViewById(R.id.lblAccess)
        lblInfo = findViewById(R.id.lblInfo)
        edtName.setText(prefs.displayName())
        edtHost.setText(prefs.manualHost)

        findViewById<MaterialButton>(R.id.btnGrant).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.btnPair).setOnClickListener {
            prefs.name = edtName.text?.toString().orEmpty()
            toast("Đang tìm đồng hồ...")
            Net.pair(this) { status, holder -> onPairResult(status, holder) }
        }
        findViewById<MaterialButton>(R.id.btnSaveHost).setOnClickListener {
            prefs.manualHost = edtHost.text?.toString().orEmpty()
            toast(if (prefs.manualHost.isEmpty()) "Đã xoá IP thủ công (dùng tự dò)" else "Đã lưu IP ${prefs.manualHost}")
            updateInfo()
        }
        findViewById<MaterialButton>(R.id.btnApps).setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
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
            if (!prefs.paired) { toast("Hãy ghép với đồng hồ trước"); return@setOnClickListener }
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

    private fun onPairResult(status: String, holder: String) {
        when (status) {
            "pending" -> toast("Hãy chạm \"Đồng ý\" trên màn hình đồng hồ...")
            "paired" -> toast("Đã ghép xong ✓")
            "busy" -> toast("Đồng hồ đang ghép với \"$holder\". Huỷ kết nối trên máy đó (hoặc trang /notify) trước.")
            "rejected" -> toast("Bị từ chối trên đồng hồ")
            "expired" -> toast("Hết thời gian chờ — thử ghép lại")
            "nohost" -> toast("Không tìm thấy đồng hồ (mDNS) — cùng WiFi chưa?")
            else -> toast("Không kết nối được đồng hồ")
        }
        updateInfo()
    }

    private fun updateAccess() {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        lblAccess.text = if (enabled) "Trạng thái quyền: ĐÃ cấp ✓"
                         else "Trạng thái quyền: CHƯA cấp — bấm nút trên"
    }

    private fun updateInfo() {
        val host = when {
            prefs.manualHost.isNotEmpty() -> "${prefs.manualHost} (thủ công)"
            prefs.host.isNotEmpty() -> "${prefs.host} (tự dò)"
            else -> "(chưa tìm thấy)"
        }
        val pair = if (prefs.paired) "Đã ghép (${prefs.displayName()})" else "CHƯA ghép"
        val apps = prefs.allowedApps.size.let { if (it == 0) "chưa chọn (không nhận gì)" else "$it ứng dụng" }
        lblInfo.text = "Ghép: $pair\nĐồng hồ: $host\nỨng dụng nhận: $apps\n${prefs.lastStatus}"
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
