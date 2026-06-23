package com.xiaozhi.notify

import android.content.Context
import android.os.Build
import java.util.UUID

/** Thin SharedPreferences wrapper: the stable pairing key + device name, the
 *  last-known mDNS address, the paired flag, and user toggles. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("xiaozhi", Context.MODE_PRIVATE)

    /** Stable per-install pairing key (the firmware-side paired holder id).
     *  Generated once and reused so re-pairing keeps the same identity. */
    val key: String
        get() {
            var k = sp.getString("key", "") ?: ""
            if (k.isEmpty()) {
                k = UUID.randomUUID().toString().replace("-", "")
                sp.edit().putString("key", k).apply()
            }
            return k
        }

    /** User-set device name shown on the watch; falls back to the phone model. */
    var name: String
        get() = sp.getString("name", "") ?: ""
        set(v) = sp.edit().putString("name", v.trim()).apply()

    fun displayName(): String = name.ifBlank { Build.MODEL ?: "Điện thoại" }

    /** True once the watch has accepted this device's pairing request. */
    var paired: Boolean
        get() = sp.getBoolean("paired", false)
        set(v) = sp.edit().putBoolean("paired", v).apply()

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var includeOngoing: Boolean
        get() = sp.getBoolean("ongoing", false)
        set(v) = sp.edit().putBoolean("ongoing", v).apply()

    /** Last resolved device host (cached so a notification can be sent without
     *  re-discovering every time). Empty until the first discovery succeeds. */
    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v).apply()

    var port: Int
        get() = sp.getInt("port", 80)
        set(v) = sp.edit().putInt("port", v).apply()

    var lastStatus: String
        get() = sp.getString("status", "Chưa gửi") ?: "Chưa gửi"
        set(v) = sp.edit().putString("status", v).apply()

    /** Optional manual device IP. When set, used directly instead of mDNS
     *  discovery (fallback for networks where discovery fails). */
    var manualHost: String
        get() = sp.getString("manual", "") ?: ""
        set(v) = sp.edit().putString("manual", v.trim()).apply()

    /** Packages whose notifications are forwarded. Empty = forward all apps. */
    var allowedApps: Set<String>
        get() = sp.getStringSet("apps", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("apps", v).apply()
}
