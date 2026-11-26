package com.example.mirrordrive

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** One usable local IPv4 address and the interface it lives on. */
data class LocalAddr(val ifName: String, val addr: InetAddress) {
    val text: String get() = "${addr.hostAddress}($ifName)"
}

// Cellular modems, VPN tunnels and dummy links can never be reached by the iPhone over Wi-Fi.
private val EXCLUDED_IF_PREFIXES = listOf("rmnet", "ccmni", "tun", "dummy", "ppp", "ipsec", "clat", "v4-")

/**
 * Lower = better. Car head units / cast boxes often expose several site-local IPv4 links at once
 * (internal Ethernet to the head unit, USB tethering, Wi-Fi). The original code advertised only the
 * FIRST one it found, so on such boxes the iPhone could be handed an unreachable address. We now
 * rank them and advertise on every candidate, preferring the iPhone Personal Hotspot subnet.
 */
private fun rank(ifName: String, addr: InetAddress): Int {
    val ip = addr.hostAddress ?: ""
    return when {
        ip.startsWith("172.20.10.") -> 0                       // iPhone Personal Hotspot subnet
        ifName.startsWith("wlan") -> 1
        ifName.startsWith("ap") || ifName.startsWith("swlan") || ifName.startsWith("softap") -> 2
        ifName.startsWith("p2p") -> 4
        ifName.startsWith("eth") || ifName.startsWith("usb") || ifName.startsWith("rndis") -> 5
        else -> 3
    }
}

/**
 * All site-local IPv4 addresses the iPhone could plausibly reach, best first.
 *
 * car3: first car test showed Wi-Fi joined to the iPhone hotspot yet NetworkInterface enumeration
 * returned nothing usable (Android 11+ restricts the netlink calls it relies on for apps targeting
 * API 30+, and some head-unit ROMs lock it down further). So the primary source is now the official
 * ConnectivityManager LinkProperties API; NetworkInterface and WifiInfo are fallbacks.
 */
fun candidateIpv4s(): List<LocalAddr> {
    val merged = LinkedHashMap<String, LocalAddr>()
    for (la in fromLinkProperties() + fromNetworkInterfaces() + fromWifiInfo()) {
        val ip = la.addr.hostAddress ?: continue
        if (ip !in merged) merged[ip] = la
    }
    return merged.values.sortedBy { rank(it.ifName, it.addr) }
}

private fun usable(ifName: String, a: InetAddress) =
    a is Inet4Address && a.isSiteLocalAddress && EXCLUDED_IF_PREFIXES.none { ifName.startsWith(it) }

@Suppress("DEPRECATION")
private fun fromLinkProperties(): List<LocalAddr> = runCatching {
    val cm = App.ctx?.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? android.net.ConnectivityManager ?: return emptyList()
    cm.allNetworks.flatMap { net ->
        val lp = cm.getLinkProperties(net) ?: return@flatMap emptyList()
        val name = lp.interfaceName ?: "net"
        lp.linkAddresses.map { it.address }.filter { usable(name, it) }.map { LocalAddr(name, it) }
    }
}.getOrDefault(emptyList())

@Suppress("DEPRECATION")
private fun fromWifiInfo(): List<LocalAddr> = runCatching {
    val wm = App.ctx?.applicationContext?.getSystemService(android.content.Context.WIFI_SERVICE)
        as? android.net.wifi.WifiManager ?: return emptyList()
    val ip = wm.connectionInfo?.ipAddress ?: 0
    if (ip == 0) return emptyList()
    val bytes = byteArrayOf((ip and 0xff).toByte(), (ip shr 8 and 0xff).toByte(),
        (ip shr 16 and 0xff).toByte(), (ip shr 24 and 0xff).toByte())
    val a = InetAddress.getByAddress(bytes)
    if (usable("wlan", a)) listOf(LocalAddr("wlan(wifiinfo)", a)) else emptyList()
}.getOrDefault(emptyList())

private fun fromNetworkInterfaces(): List<LocalAddr> = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .filter { nif -> EXCLUDED_IF_PREFIXES.none { nif.name.startsWith(it) } }
        .flatMap { nif ->
            nif.inetAddresses.asSequence()
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { LocalAddr(nif.name, it) }
        }
        .toList()
}.getOrDefault(emptyList())

/** Diagnostic: what each address source sees, e.g. "lp=wlan0:172.20.10.3 ni=- wi=172.20.10.3". */
fun sourceDump(): String {
    fun fmt(l: List<LocalAddr>) = if (l.isEmpty()) "-" else l.joinToString(",") { "${it.ifName}:${it.addr.hostAddress}" }
    return "lp=${fmt(fromLinkProperties())} ni=${fmt(fromNetworkInterfaces())} wi=${fmt(fromWifiInfo())}"
}

/** The best candidate (kept for callers that only need one address). */
fun localIpv4(): InetAddress? = candidateIpv4s().firstOrNull()?.addr

/** Every candidate as on-screen text, or null if none is up. */
fun localIpv4String(): String? =
    candidateIpv4s().takeIf { it.isNotEmpty() }?.joinToString(" ") { it.addr.hostAddress ?: "?" }

/**
 * Diagnostic: every UP interface with its IPv4 addresses, INCLUDING ones we filter out, so a
 * "no-network" screenshot tells us whether Wi-Fi is down or we are wrongly excluding a link.
 * Format: name:ip[,ip][*] where * marks an interface excluded by our filters. "none" if no IPv4.
 */
fun interfaceDump(): String = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .map { nif ->
            val v4 = nif.inetAddresses.asSequence().filterIsInstance<Inet4Address>()
                .map { it.hostAddress }.toList()
            val excluded = nif.isVirtual || EXCLUDED_IF_PREFIXES.any { nif.name.startsWith(it) }
            nif.name + ":" + (if (v4.isEmpty()) "v6only" else v4.joinToString(",")) + (if (excluded) "*" else "")
        }
        .joinToString(" ")
        .ifEmpty { "none" }
}.getOrElse { "err:${it.javaClass.simpleName}" }
