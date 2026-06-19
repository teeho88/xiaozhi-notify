package com.xiaozhi.notify

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** Lets the user pick which apps' notifications get forwarded.
 *  No selection = all apps (default). */
class AppsActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private val packages = ArrayList<String>()   // parallel to adapter rows

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        listView = findViewById(R.id.list)
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btnAll).setOnClickListener { toggleAll(it as MaterialButton) }
        loadApps()
    }

    private fun toggleAll(btn: MaterialButton) {
        val n = listView.adapter?.count ?: 0
        if (n == 0) return
        var allChecked = true
        for (i in 0 until n) if (!listView.isItemChecked(i)) { allChecked = false; break }
        val newState = !allChecked
        for (i in 0 until n) listView.setItemChecked(i, newState)
        btn.text = if (newState) "Bỏ chọn tất cả" else "Chọn tất cả"
    }

    private fun loadApps() {
        Thread {
            val pm = packageManager
            // User-facing apps (those with a launcher entry), sorted by name.
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val infos = pm.queryIntentActivities(main, 0)
            val seen = HashSet<String>()
            val rows = ArrayList<Pair<String, String>>()  // pkg, label
            for (ri in infos) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                rows.add(pkg to ri.loadLabel(pm).toString())
            }
            rows.sortBy { it.second.lowercase() }

            val allowed = Prefs(this).allowedApps
            val labels = ArrayList<String>()
            packages.clear()
            for ((pkg, label) in rows) {
                packages.add(pkg)
                labels.add(label)
            }
            runOnUiThread {
                val adapter = ArrayAdapter(
                    this, android.R.layout.simple_list_item_multiple_choice, labels
                )
                listView.adapter = adapter
                packages.forEachIndexed { i, pkg ->
                    if (pkg in allowed) listView.setItemChecked(i, true)
                }
            }
        }.start()
    }

    private fun save() {
        val selected = HashSet<String>()
        val checked = listView.checkedItemPositions
        for (i in 0 until checked.size()) {
            if (checked.valueAt(i)) {
                val pos = checked.keyAt(i)
                if (pos in packages.indices) selected.add(packages[pos])
            }
        }
        Prefs(this).allowedApps = selected
        Toast.makeText(
            this,
            if (selected.isEmpty()) "Đã lưu: chưa chọn app nào (không nhận thông báo)"
            else "Đã lưu: ${selected.size} ứng dụng",
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }
}
