// Ported from PhoneGo (MIT, Copyright (c) 2024 xuyifan-0731, (c) 2025 yangqin-jiang):
// android-app/.../MdnsAdbFinder.kt and AdbPortScanner.kt, merged.
package app.momoding.phone.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Where wireless debugging listens. The port changes each time it is switched on, so: the port
 * that worked last time, else the one adbd advertises over mDNS, else a scan of the ephemeral
 * range. Every answer is confirmed with [AdbConnection.speaksAdb] (an mDNS record can outlive
 * the switch).
 */
object AdbPorts {
    private const val PREFS = "momoding_phone_shell"
    private const val KEY = "adbd_port"
    private const val SERVICE = "_adb-tls-connect._tcp"

    fun find(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getInt(KEY, 0).takeIf { it > 0 && AdbConnection.speaksAdb(it) }?.let { return it }
        val port = advertised(context)?.takeIf { AdbConnection.speaksAdb(it) } ?: scan() ?: return null
        prefs.edit().putInt(KEY, port).apply()
        return port
    }

    fun forget(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()

    /** The port adbd advertises over mDNS, or null within [timeoutMs]. */
    private fun advertised(context: Context, timeoutMs: Long = 5000): Int? {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        val latch = CountDownLatch(1)
        var port = 0
        val resolving = AtomicBoolean(false) // the record is advertised on several interfaces
        val resolve = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { latch.countDown() }
            override fun onServiceResolved(info: NsdServiceInfo) { port = info.port; latch.countDown() }
        }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.startsWith(SERVICE) && resolving.compareAndSet(false, true)) {
                    runCatching { @Suppress("DEPRECATION") nsd.resolveService(info, resolve) }
                }
            }
        }
        return try {
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            port.takeIf { it > 0 }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
    }

    /** The Linux ephemeral range, where adbd's port comes from; twice, as adbd may be mid-restart. */
    private fun scan(): Int? {
        repeat(2) {
            val next = AtomicInteger(32768)
            val found = AtomicReference<Int?>(null)
            (1..192).map {
                thread(isDaemon = true, name = "adb-port-$it") {
                    while (found.get() == null) {
                        val port = next.getAndIncrement()
                        if (port > 60999) return@thread
                        if (accepts(port) && AdbConnection.speaksAdb(port)) found.compareAndSet(null, port)
                    }
                }
            }.forEach { it.join() }
            found.get()?.let { return it }
        }
        return null
    }

    private fun accepts(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 40); true }
    }.getOrDefault(false)
}
