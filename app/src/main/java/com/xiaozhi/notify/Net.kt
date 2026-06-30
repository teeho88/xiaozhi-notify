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
import java.net.Inet4Address
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Finds the clock on the LAN by mDNS (NsdManager does real multicast resolution
 * at the app layer, so it works even on routers that can't resolve ".local" for
 * a plain HTTP client) and forwards notifications to its /api/notify endpoint.
 *
 * Pairing is automatic + confirmed on the watch: pair() discovers the device,
 * sends a pair request, and polls until the user taps "Đồng ý" on the screen.
 * Because the host is resolved via mDNS on every send, the pairing survives the
 * watch's IP changing on WiFi reconnect — the key stays constant.
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
            if (!prefs.paired) {
                prefs.lastStatus = "Chưa ghép với đồng hồ"
                statusListener?.invoke()
                return@execute
            }
            val manual = prefs.manualHost.isNotEmpty()
            var code = postNotify(prefs, app, title, text)
            if (code !in 200..299 && !manual) {
                // Address stale/missing: re-resolve (mDNS, then subnet scan), retry once.
                resolveHost(appCtx)
                code = postNotify(Prefs(appCtx), app, title, text)
            }
            if (code == 403) Prefs(appCtx).paired = false  // watch dropped us
            prefs.lastStatus = when {
                code in 200..299 -> "Đã gửi: ${title.ifBlank { text }}"
                code == 403 -> "Đồng hồ đã huỷ ghép — hãy ghép lại"
                code == -2 -> "Không tìm thấy đồng hồ (mDNS) — nhập IP thủ công"
                code == -1 -> "Không kết nối được — sai IP, hoặc khác WiFi với đồng hồ"
                else -> "Lỗi HTTP $code"
            }
            statusListener?.invoke()
        }
    }

    /** Warm the cached address when the listener binds, off the send queue so a
     *  notification arriving right after isn't stuck behind the resolve. */
    fun warmUp(ctx: Context) {
        val appCtx = ctx.applicationContext
        Thread {
            if (Prefs(appCtx).manualHost.isEmpty()) resolveHost(appCtx)
        }.apply { isDaemon = true }.start()
    }

    /**
     * Drive the full pairing handshake. onResult(status, holder) is posted to the
     * main thread possibly several times: first "pending" (waiting for the user
     * to tap Đồng ý on the watch), then a terminal status:
     *   paired | busy | rejected | expired | nohost | neterr
     */
    fun pair(ctx: Context, onResult: (String, String) -> Unit) {
        val appCtx = ctx.applicationContext
        exec.execute {
            val prefs = Prefs(appCtx)
            if (prefs.host.isEmpty() && prefs.manualHost.isEmpty()) {
                if (!resolveHost(appCtx)) { postUi(onResult, "nohost", ""); return@execute }
            }
            var r = postPair(Prefs(appCtx), true)
            if (r.first == "neterr" && prefs.manualHost.isEmpty()) {
                resolveHost(appCtx)
                r = postPair(Prefs(appCtx), true)
            }
            if (r.first != "pending") { finishPair(appCtx, r, onResult); return@execute }
            postUi(onResult, "pending", "")
            var tries = 20  // ~40s, matches the watch's 30s prompt + margin
            while (tries-- > 0) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) {}
                val p = postPair(Prefs(appCtx), false)
                if (p.first == "pending") continue
                finishPair(appCtx, p, onResult); return@execute
            }
            postUi(onResult, "expired", "")
        }
    }

    private fun finishPair(ctx: Context, r: Pair<String, String>, onResult: (String, String) -> Unit) {
        if (r.first == "paired") Prefs(ctx).paired = true
        postUi(onResult, r.first, r.second)
    }

    private fun postUi(onResult: (String, String) -> Unit, status: String, holder: String) {
        Handler(Looper.getMainLooper()).post { onResult(status, holder) }
    }

    // ---- HTTP ---------------------------------------------------------------

    /** POST /api/notify/pair {key,name,force} -> (status, holder). */
    private fun postPair(prefs: Prefs, force: Boolean): Pair<String, String> {
        val url = endpoint(prefs, "/api/notify/pair") ?: return Pair("nohost", "")
        return try {
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject()
                .put("key", prefs.key)
                .put("name", prefs.displayName())
                .put("force", force)
                .toString()
            conn.outputStream.use { os: OutputStream -> os.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            if (code !in 200..299) return Pair("neterr", "")
            val o = JSONObject(resp)
            Pair(o.optString("status", "expired"), o.optString("holder", ""))
        } catch (e: Exception) {
            Log.w(TAG, "pair failed: ${e.message}")
            Pair("neterr", "")
        }
    }

    /** POST /api/notify {key,app,title,text}. Returns HTTP code, or -1/-2 error. */
    private fun postNotify(prefs: Prefs, app: String, title: String, text: String): Int {
        val url = endpoint(prefs, "/api/notify") ?: return -2
        return try {
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject()
                .put("key", prefs.key)
                .put("app", app)
                .put("title", title)
                .put("text", text)
                .toString()
            conn.outputStream.use { os: OutputStream -> os.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            Log.i(TAG, "POST ${url.host} -> $code")
            code
        } catch (e: Exception) {
            Log.w(TAG, "POST failed: ${e.message}")
            -1
        }
    }

    private data class Target(val host: String, val port: Int)

    private fun endpoint(prefs: Prefs, path: String): URL? {
        val target = (if (prefs.manualHost.isNotEmpty()) {
            parseManualTarget(prefs.manualHost)
        } else if (prefs.host.isNotEmpty()) {
            Target(prefs.host, if (prefs.port > 0) prefs.port else 80)
        } else {
            null
        }) ?: return null
        return URL("http://${urlHost(target.host)}:${target.port}$path")
    }

    private fun parseManualTarget(raw: String): Target? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        s = s.removePrefix("http://").removePrefix("https://").substringBefore('/').trim()
        if (s.isEmpty()) return null
        if (s.startsWith("[")) {
            val host = s.substringAfter('[').substringBefore(']')
            val rest = s.substringAfter(']', "")
            val port = rest.removePrefix(":").toIntOrNull() ?: 80
            return Target(host, port)
        }
        val colonCount = s.count { it == ':' }
        if (colonCount == 1) {
            val host = s.substringBefore(':')
            val port = s.substringAfter(':').toIntOrNull() ?: 80
            return Target(host, port)
        }
        return Target(s, 80)
    }

    private fun urlHost(host: String): String {
        val h = host.trim().removeSurrounding("[", "]")
        if (h.isEmpty()) return h
        return if (h.contains(':')) "[${h.replace("%", "%25")}]" else h
    }

    // ---- host resolution ----------------------------------------------------

    /**
     * Resolve and cache the clock's address. Tries mDNS first (fast where it
     * works), then falls back to a direct subnet scan. The scan is the reliable
     * path on the many OEM Android builds where NsdManager multicast is broken
     * or filtered — there a notification would otherwise never get a host and
     * silently fail to send. Returns true and stores host/port in Prefs on hit.
     */
    private fun resolveHost(ctx: Context): Boolean =
        discoverBlocking(ctx, 3000) || scanSubnet(ctx)

    /**
     * Probe every host on the phone's /24 in parallel for the clock, identified
     * by its `GET /api/notify` reply (403 "device not paired" when sent without
     * a key). Plain TCP/HTTP, so it works on every Android version regardless of
     * mDNS support.
     */
    private fun scanSubnet(ctx: Context): Boolean {
        val ip = localIpv4() ?: return false
        val prefix = ip.substringBeforeLast('.', "")
        if (prefix.isEmpty()) return false

        val pool = Executors.newFixedThreadPool(32)
        val found = AtomicReference<String?>(null)
        val latch = CountDownLatch(254)
        for (i in 1..254) {
            val host = "$prefix.$i"
            if (host == ip) { latch.countDown(); continue }
            pool.execute {
                try {
                    if (found.get() == null && probeIsClock(host)) found.compareAndSet(null, host)
                } finally { latch.countDown() }
            }
        }
        try { latch.await(8, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        pool.shutdownNow()

        val hit = found.get() ?: return false
        val prefs = Prefs(ctx)
        prefs.host = hit
        prefs.port = 80
        Log.i(TAG, "Subnet scan resolved clock -> $hit")
        return true
    }

    /** True if [host] answers /api/notify like the clock does (403 + our body). */
    private fun probeIsClock(host: String): Boolean = try {
        val conn = URL("http://$host:80/api/notify").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 700
        conn.readTimeout = 700
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        code == 403 && body.contains("device not paired")
    } catch (_: Exception) {
        false
    }

    /** First site-local IPv4 of an up, non-loopback interface (WiFi/hotspot). */
    private fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
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
        var resolving = false

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
                if (!info.serviceType.contains("xiaozhi-notify")) return
                if (resolving || resolved) return
                resolving = true
                try { nsd.resolveService(info, resolveListener) } catch (_: Exception) { latch.countDown() }
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
