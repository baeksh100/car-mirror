package com.example.mirrordrive

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * A fixed name for the remote page: http://carmirror-remote.local:8765/
 *
 * The car's iPhone hotspot gives the cast only IPv6 (no IPv4 at all), and its global IPv6 changes
 * from drive to drive, so a typed or saved IP address would keep breaking. Safari finds ".local"
 * names over Bonjour by itself, so the home-screen icon keeps working wherever the cast is.
 * One small mDNS responder per reachable address, refreshed when the network changes.
 */
class RemoteMdns(private val context: Context) {
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val instances = LinkedHashMap<String, JmDNS>()
    private var lock: WifiManager.MulticastLock? = null

    fun start() {
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
            lock = wifi?.createMulticastLock("carmirror-remote")?.apply { setReferenceCounted(false); acquire() }
        }
        io.scheduleWithFixedDelay({ runCatching { refresh() } }, 0, 10, TimeUnit.SECONDS)
    }

    fun stop() {
        io.execute {
            instances.values.forEach { runCatching { it.unregisterAllServices(); it.close() } }
            instances.clear()
            active = false
            runCatching { lock?.let { if (it.isHeld) it.release() } }
        }
        io.shutdown()
    }

    private fun refresh() {
        val wanted = candidateAddrs().associateBy { it.addr.hostAddress ?: "" }.filterKeys { it.isNotEmpty() }
        (instances.keys - wanted.keys).toList().forEach { ip ->
            instances.remove(ip)?.let { runCatching { it.unregisterAllServices(); it.close() } }
        }
        for ((ip, la) in wanted) {
            if (ip in instances) continue
            try {
                val jm = JmDNS.create(la.addr, REMOTE_HOST)
                jm.registerService(ServiceInfo.create("_http._tcp.local.", "CarMirror 리모컨", REMOTE_PORT, ""))
                instances[ip] = jm
                Log.i(TAG, "remote name on ${la.text}")
            } catch (t: Throwable) {
                Log.w(TAG, "remote name failed on ${la.text}", t)
            }
        }
        active = instances.isNotEmpty()
    }

    companion object {
        private const val TAG = "RemoteMdns"
        const val REMOTE_HOST = "carmirror-remote"

        /** True while the .local name is being answered on at least one network. */
        @Volatile var active = false
            private set
    }
}

/**
 * Hosts the iPhone can open by number, for when the .local name does not work: IPv4 on the
 * hotspot or Wi-Fi first, then global IPv6 (in brackets, as a URL needs).
 */
fun remoteIpHosts(): List<String> = runCatching {
    val v4 = candidateAddrs().map { it.addr }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
    val v6 = NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("usb") || it.name.startsWith("eth")) }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet6Address>()
        .filter { !it.isLinkLocalAddress && !it.isLoopbackAddress && !it.isMulticastAddress }
        .mapNotNull { it.hostAddress?.substringBefore('%') }
        .map { "[$it]" }
        .toList()
    (v4 + v6).distinct()
}.getOrDefault(emptyList())
