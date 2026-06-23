package com.xiaozhi.notify

import android.content.Context

/** Thin SharedPreferences wrapper holding the pairing token + device key, the
 *  last-known device address discovered via mDNS, and user toggles. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("xiaozhi", Context.MODE_PRIVATE)

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    /** Device key (the firmware "dev" lock), copied from the /notify page after
     *  pressing "Kết nối". Only the connected device's key is accepted. */
    var deviceKey: String
        get() = sp.getString("devkey", "") ?: ""
        set(v) = sp.edit().putString("devkey", v.trim()).apply()

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
