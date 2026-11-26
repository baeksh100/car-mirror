package com.example.mirrordrive

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

fun airplayTxt(deviceId: String, pkHex: String): Map<String, String> = linkedMapOf(
    "deviceid" to deviceId,                 // lowercase colon MAC
    "features" to "0x5A7FFEE6,0x0",
    "pw" to "false",
    "flags" to "0x4",
    "model" to "AppleTV3,2",
    "pk" to pkHex,
    "pi" to "2e388006-13ba-4041-9a67-25dd4a43d536",
    "srcvers" to "220.68",
    "vv" to "2",
)

fun raopTxt(pkHex: String): Map<String, String> = linkedMapOf(
    "txtvers" to "1", "ch" to "2", "cn" to "0,1,2,3", "da" to "true",
    "et" to "0,3,5", "vv" to "2", "ft" to "0x5A7FFEE6,0x0", "am" to "AppleTV3,2",
    "md" to "0,1,2", "rhd" to "5.6.0.0", "pw" to "false", "sf" to "0x4",
    "sr" to "44100", "ss" to "16", "sv" to "false", "tp" to "UDP",
    "vs" to "220.68", "vn" to "65537", "pk" to pkHex,
)

fun raopInstanceName(deviceId: String, friendly: String): String =
    deviceId.replace(":", "").uppercase() + "@" + friendly

/**
 * Advertises the receiver over mDNS/Bonjour so the iPhone lists it under Screen Mirroring.
 *
 * Car fix: the original started jmDNS exactly once, at service creation. In a car the box usually
 * boots BEFORE the iPhone hotspot is joined, so there was no IPv4 yet, start failed, and nothing
 * ever retried — the receiver stayed invisible until the app was force-restarted. Now we:
 *  - watch network changes (ConnectivityManager callback) and re-advertise when addresses change,
 *  - poll every few seconds as a safety net (some head-unit ROMs drop network callbacks),
 *  - advertise on EVERY reachable local IPv4 instead of just the first one found.
 */
class DiscoveryService(private val context: Context) {
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val instances = LinkedHashMap<String, JmDNS>()   // ip -> jmDNS bound to it
    private var lock: WifiManager.MulticastLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var poll: ScheduledFuture<*>? = null
    private var pendingRefresh: ScheduledFuture<*>? = null
    private var params: Params? = null

    private data class Params(val name: String, val port: Int, val pkHex: String, val deviceId: String)

    fun start(name: String, port: Int, pkHex: String, deviceId: String) = io.execute {
        params = Params(name, port, pkHex, deviceId)
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
            lock = wifi?.createMulticastLock("carmirror-mdns")?.apply { setReferenceCounted(false); acquire() }
        }.onFailure { Log.w(TAG, "multicast lock unavailable", it) }
        // car4: primary path is Android's own mDNS advertiser (NsdManager). The system daemon
        // advertises on every interface, IPv4 AND IPv6, and follows network changes itself — which
        // matters because the real car's iPhone hotspot gives the box IPv6 only. jmDNS (one
        // responder per address, below) is now only the fallback if NSD fails or never answers.
        if (!startNsd(Params(name, port, pkHex, deviceId))) startJmdns("nsd-unavailable")
        else io.schedule({ if (nsdOk < 2 && !jmdnsMode) { stopNsd(); startJmdns("nsd-timeout") } },
            NSD_TIMEOUT_S, TimeUnit.SECONDS)
    }

    private fun startJmdns(reason: String) {
        if (jmdnsMode) return
        jmdnsMode = true
        Log.i(TAG, "using jmDNS fallback ($reason)")
        registerNetworkCallback()
        refresh(reason)
        poll = io.scheduleWithFixedDelay({ refresh("poll") }, POLL_S, POLL_S, TimeUnit.SECONDS)
    }

    // ---- NsdManager (system mDNS) path ------------------------------------------------------
    private var nsd: NsdManager? = null
    private val nsdListeners = ArrayList<NsdManager.RegistrationListener>()
    @Volatile private var nsdOk = 0
    @Volatile private var jmdnsMode = false
    private val nsdNames = ArrayList<String>()

    private fun startNsd(p: Params): Boolean = runCatching {
        val mgr = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager? ?: return false
        nsd = mgr
        registerNsd(mgr, p.name, "_airplay._tcp", p.port, airplayTxt(p.deviceId, p.pkHex))
        registerNsd(mgr, raopInstanceName(p.deviceId, p.name), "_raop._tcp", p.port, raopTxt(p.pkHex))
        status = "nsd:registering"
        true
    }.getOrElse { Log.e(TAG, "NSD start failed", it); lastError = "nsd:${it.javaClass.simpleName}"; false }

    private fun registerNsd(mgr: NsdManager, name: String, type: String, port: Int, txt: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            serviceName = name; serviceType = type; this.port = port
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) {
                nsdOk++
                synchronized(nsdNames) { nsdNames.add(si.serviceName) }
                status = "nsd ok($nsdOk/2) ${synchronized(nsdNames) { nsdNames.firstOrNull() }}"
                Log.i(TAG, "NSD registered ${si.serviceName} $type")
            }
            override fun onRegistrationFailed(si: NsdServiceInfo, code: Int) {
                Log.e(TAG, "NSD register failed $type code=$code")
                lastError = "nsd:$type:$code"
                io.execute { stopNsd(); startJmdns("nsd-failed") }
            }
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, code: Int) {}
        }
        nsdListeners.add(l)
        mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun stopNsd() {
        val mgr = nsd ?: return
        nsdListeners.forEach { runCatching { mgr.unregisterService(it) } }
        nsdListeners.clear(); nsd = null; nsdOk = 0
    }

    fun stop() = io.execute {
        poll?.cancel(false); poll = null
        pendingRefresh?.cancel(false); pendingRefresh = null
        netCallback?.let { cb ->
            runCatching { connectivity()?.unregisterNetworkCallback(cb) }
        }
        netCallback = null
        stopNsd()
        jmdnsMode = false
        closeAll()
        lock?.let { if (it.isHeld) it.release() }; lock = null
        status = "stopped"
    }

    private fun connectivity() =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?

    private fun registerNetworkCallback() {
        val cm = connectivity() ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleRefresh("net+")
            override fun onLost(network: Network) = scheduleRefresh("net-")
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = scheduleRefresh("link")
        }
        // Not only TRANSPORT_WIFI: we also want Ethernet/USB links and the SoftAP case.
        val req = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(req, cb); netCallback = cb }
            .onFailure { Log.w(TAG, "network callback unavailable; relying on polling", it) }
    }

    /** Debounced: DHCP often fires several link changes within a second. */
    private fun scheduleRefresh(reason: String) {
        runCatching {
            io.execute {
                pendingRefresh?.cancel(false)
                pendingRefresh = io.schedule({ refresh(reason) }, 1500, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** Must run on [io]. Re-advertise so the set of bound addresses matches the current network. */
    private fun refresh(reason: String) {
        val p = params ?: return
        val wanted = candidateIpv4s().associateBy { it.addr.hostAddress ?: "" }.filterKeys { it.isNotEmpty() }
        if (wanted.keys == instances.keys && instances.isNotEmpty()) return

        // Drop instances whose address disappeared.
        (instances.keys - wanted.keys).toList().forEach { ip ->
            instances.remove(ip)?.let { jm -> runCatching { jm.unregisterAllServices(); jm.close() } }
        }
        // Bind new addresses.
        for ((ip, la) in wanted) {
            if (ip in instances) continue
            try {
                val jm = JmDNS.create(la.addr, p.name)
                jm.registerService(ServiceInfo.create(
                    "_airplay._tcp.local.", p.name, p.port, 0, 0, airplayTxt(p.deviceId, p.pkHex)))
                jm.registerService(ServiceInfo.create(
                    "_raop._tcp.local.", raopInstanceName(p.deviceId, p.name), p.port, 0, 0, raopTxt(p.pkHex)))
                instances[ip] = jm
                Log.i(TAG, "advertising on ${la.text} port=${p.port} ($reason)")
            } catch (t: Throwable) {
                Log.e(TAG, "mDNS register failed on ${la.text}", t)
                lastError = "${la.text}: ${t.javaClass.simpleName}"
            }
        }
        status = if (instances.isEmpty()) "jmdns:no-network(retrying)" else "jmdns on ${instances.keys.joinToString(",")}"
        Log.i(TAG, "discovery $status ($reason)")
    }

    private fun closeAll() {
        instances.values.forEach { jm -> runCatching { jm.unregisterAllServices(); jm.close() } }
        instances.clear()
    }

    companion object {
        private const val TAG = "DiscoveryService"
        private const val POLL_S = 5L
        private const val NSD_TIMEOUT_S = 8L

        /** Read by the on-screen diagnostic line. */
        @Volatile var status: String = "init"
        @Volatile var lastError: String = "-"
    }
}
