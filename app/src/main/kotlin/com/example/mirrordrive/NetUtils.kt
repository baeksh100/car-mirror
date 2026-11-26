package com.example.mirrordrive

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/** One usable local address and the interface it lives on. */
data class LocalAddr(val ifName: String, val addr: InetAddress) {
    val isV6: Boolean get() = addr is Inet6Address
    val text: String get() = "${addr.hostAddress}($ifName)"
}

// Cellular modems, VPN tunnels, dummy links and the CLAT (464XLAT) shim can never be reached by
// the iPhone over Wi-Fi. "v4-" is CLAT: its 192.0.0.x address exists only inside this device.
private val EXCLUDED_IF_PREFIXES = listOf("rmnet", "ccmni", "tun", "dummy", "ppp", "ipsec", "clat", "v4-")

private fun excludedIf(name: String) = EXCLUDED_IF_PREFIXES.any { name.startsWith(it) }

/**
 * Lower = better. Prefer the iPhone Personal Hotspot IPv4 subnet, then Wi-Fi, then others.
 * IPv6 candidates rank after any IPv4 on the same kind of link.
 */
private fun rank(la: LocalAddr): Int {
    val ip = la.addr.hostAddress ?: ""
    val n = la.ifName
    val base = when {
        ip.startsWith("172.20.10.") -> 0
        n.startsWith("wlan") -> 1
        n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") -> 2
        n.startsWith("p2p") -> 4
        n.startsWith("eth") || n.startsWith("usb") || n.startsWith("rndis") -> 5
        else -> 3
    }
    return base * 2 + if (la.isV6) 1 else 0
}

/**
 * Addresses the iPhone could reach, best first — at most one per interface, so each interface gets
 * exactly one mDNS responder (two on one link would fight over the same name).
 *
 * car4: the real car test showed `wlan0:v6only v4-wlan0:192.0.0.4` — the iPhone hotspot handed the
 * box ONLY IPv6 (Android then runs a CLAT shim for outbound IPv4). There is no LAN IPv4 at all, so
 * an IPv4-only receiver can never be found. For interfaces with no usable IPv4 we now advertise
 * their IPv6 link-local address (always present, always on-link, carries the interface scope);
 * UxPlay's httpd already listens on IPv6 and iOS AirPlay happily connects over link-local v6.
 */
fun candidateAddrs(): List<LocalAddr> {
    val all = (fromNetworkInterfaces() + fromLinkProperties() + fromWifiInfo())
        .filterNot { excludedIf(it.ifName) }
    val perIf = LinkedHashMap<String, LocalAddr>()
    for (la in all.sortedBy { addrPref(it) }) {
        val key = la.ifName.substringBefore("(")
        if (key !in perIf) perIf[key] = la
    }
    return perIf.values.sortedBy { rank(it) }
}

/** Within one interface: site-local IPv4 > IPv6 link-local > other IPv6. */
private fun addrPref(la: LocalAddr): Int = when (val a = la.addr) {
    is Inet4Address -> 0
    is Inet6Address -> if (a.isLinkLocalAddress) 1 else 2
    else -> 3
}

/** Backwards-compatible name used by DiscoveryService. */
fun candidateIpv4s(): List<LocalAddr> = candidateAddrs()

/** The best candidate (kept for callers that only need one address). */
fun localIpv4(): InetAddress? = candidateAddrs().firstOrNull()?.addr

/** Every candidate as short on-screen text, or null if none is up. */
fun localIpv4String(): String? =
    candidateAddrs().takeIf { it.isNotEmpty() }?.joinToString(" ") { shortText(it) }

/** "172.20.10.3" or "fe80::1a2b%wlan0" style text, trimmed for the small screen. */
fun shortText(la: LocalAddr): String = la.addr.hostAddress ?: "?"

private fun usableV4(a: InetAddress) = a is Inet4Address && a.isSiteLocalAddress
private fun usableV6(a: InetAddress) = a is Inet6Address && !a.isLoopbackAddress && !a.isMulticastAddress

private fun fromNetworkInterfaces(): List<LocalAddr> = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual && !excludedIf(it.name) }
        .flatMap { nif ->
            nif.inetAddresses.asSequence()
                .filter { usableV4(it) || usableV6(it) }
                .map { LocalAddr(nif.name, it) }
        }
        .toList()
}.getOrDefault(emptyList())

@Suppress("DEPRECATION")
private fun fromLinkProperties(): List<LocalAddr> = runCatching {
    val cm = App.ctx?.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? android.net.ConnectivityManager ?: return emptyList()
    cm.allNetworks.flatMap { net ->
        val lp = cm.getLinkProperties(net) ?: return@flatMap emptyList()
        val name = lp.interfaceName ?: return@flatMap emptyList()
        if (excludedIf(name)) return@flatMap emptyList()
        val nif = runCatching { NetworkInterface.getByName(name) }.getOrNull()
        lp.linkAddresses.map { it.address }.mapNotNull { a ->
            when {
                usableV4(a) -> LocalAddr(name, a)
                // LinkProperties drops the scope id; re-attach it so link-local v6 is bindable.
                a is Inet6Address && usableV6(a) && nif != null ->
                    LocalAddr(name, Inet6Address.getByAddress(null, a.address, nif))
                else -> null
            }
        }
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
    if (usableV4(a)) listOf(LocalAddr("wlan0(wifiinfo)", a)) else emptyList()
}.getOrDefault(emptyList())

/** Diagnostic: what each address source sees (IPv4 only, compact). */
fun sourceDump(): String {
    fun fmt(l: List<LocalAddr>) = l.filterNot { it.isV6 }
        .let { v4 -> if (v4.isEmpty()) "-" else v4.joinToString(",") { "${it.ifName}:${it.addr.hostAddress}" } }
    return "v4 lp=${fmt(fromLinkProperties())} ni=${fmt(fromNetworkInterfaces())} wi=${fmt(fromWifiInfo())}"
}

/**
 * Diagnostic: every UP interface with its IPv4 addresses (or v6only), INCLUDING ones we filter
 * out (marked *), so a screenshot tells whether Wi-Fi is down or a link is being excluded.
 */
fun interfaceDump(): String = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .map { nif ->
            val v4 = nif.inetAddresses.asSequence().filterIsInstance<Inet4Address>()
                .map { it.hostAddress }.toList()
            val excluded = nif.isVirtual || excludedIf(nif.name)
            nif.name + ":" + (if (v4.isEmpty()) "v6only" else v4.joinToString(",")) + (if (excluded) "*" else "")
        }
        .joinToString(" ")
        .ifEmpty { "none" }
}.getOrElse { "err:${it.javaClass.simpleName}" }
