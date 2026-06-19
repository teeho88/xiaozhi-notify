package com.xiaozhi.notify

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds the clock on the LAN by mDNS (NsdManager does real multicast resolution
 * at the app layer, so it works even on routers that can't resolve ".local" for
 * a plain HTTP client) and forwards notifications to its /api/notify endpoint.
 *
 * The resolved address is cached in Prefs; each send tries the cache first and
 * only re-discovers when the cached address fails (e.g. after a network switch).
 */
object Net {
    private const val TAG = "XiaoZhiNet"
    const val SERVICE_TYPE = "_xiaozhi-notify._tcp."

    private val exec = Executors.newSingleThreadExecutor()

    /** Set by MainActivity to refresh its status line; cleared in onPause. */
    @Volatile var statusListener: (() -> Unit)? = null

    // ---- public API --------------------------------------------------------

    fun send(ctx: Context, app: String, title: String, text: String) {
        val appCtx = ctx.applicationContext
        exec.execute {
            val prefs = Prefs(appCtx)
            if (prefs.token.isEmpty()) {
                prefs.lastStatus = "Chưa nhập token"
                statusListener?.invoke()
                return@execute
            }
            var ok = post(prefs, app, title, text)
            if (!ok) {
                // Cached address stale (or none yet): re-discover, then retry once.
                discoverBlocking(appCtx, 5000)
                ok = post(Prefs(appCtx), app, title, text)
            }
            prefs.lastStatus = if (ok) "Đã gửi: ${title.ifBlank { text }}"
                               else "Lỗi gửi — kiểm tra WiFi/token"
            statusListener?.invoke()
        }
    }

    /** Discover on demand (used by the "Tìm thiết bị" button). */
    fun discoverAsync(ctx: Context, done: (Boolean) -> Unit) {
        val appCtx = ctx.applicationContext
        exec.execute {
            val found = discoverBlocking(appCtx, 6000)
            Handler(Looper.getMainLooper()).post { done(found) }
        }
    }

    // ---- HTTP ---------------------------------------------------------------

    private fun post(prefs: Prefs, app: String, title: String, text: String): Boolean {
        val host = prefs.host
        if (host.isEmpty()) return false
        return try {
            val url = URL("http://$host:${prefs.port}/api/notify")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject()
                .put("token", prefs.token)
                .put("id", prefs.deviceId)
                .put("app", app)
                .put("title", title)
                .put("text", text)
                .toString()
            conn.outputStream.use { os: OutputStream -> os.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            Log.i(TAG, "POST $host -> $code")
            code in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "POST failed: ${e.message}")
            false
        }
    }

    // ---- mDNS discovery -----------------------------------------------------

    /** Blocks the calling (worker) thread until a device resolves or timeout.
     *  Returns true and stores host/port in Prefs on success. */
    private fun discoverBlocking(ctx: Context, timeoutMs: Long): Boolean {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("xiaozhi-nsd").apply {
            setReferenceCounted(false)
            acquire()
        }
        val latch = CountDownLatch(1)
        var resolved = false  // visibility guaranteed by latch await/countDown

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                val addr = info.host?.hostAddress
                if (addr != null) {
                    val prefs = Prefs(ctx)
                    prefs.host = addr
                    prefs.port = if (info.port > 0) info.port else 80
                    resolved = true
                    Log.i(TAG, "Resolved ${info.serviceName} -> $addr:${info.port}")
                }
                latch.countDown()
            }
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed: $errorCode")
                latch.countDown()
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.contains("xiaozhi-notify")) {
                    try { nsd.resolveService(info, resolveListener) } catch (_: Exception) {}
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }

        return try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            resolved
        } catch (e: Exception) {
            Log.w(TAG, "Discovery error: ${e.message}")
            false
        } finally {
            try { nsd.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
            if (lock.isHeld) lock.release()
        }
    }
}
