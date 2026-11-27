package com.example.mirrordrive

import java.net.URLDecoder

/**
 * The iPhone remote: a tiny web page that CarMirror serves on the car's Wi-Fi. Safari on the
 * iPhone opens it (and it can be added to the home screen); its buttons call back here and the
 * accessibility service presses Home / Back / … on the car screen.
 *
 * This file is the pure part (request parsing and routing), kept free of Android so it is tested.
 */
const val REMOTE_PORT = 8765

/** What the car should do for one button. */
enum class RemoteCommand(val id: String) {
    HOME("home"), BACK("back"), RECENTS("recents"),
    NOTIFICATIONS("notifications"), QUICK_SETTINGS("quicksettings"),
    SCROLL_UP("scrollup"), SCROLL_DOWN("scrolldown"),
    VOLUME_UP("volup"), VOLUME_DOWN("voldown"), MUTE("mute"),
    SETTINGS("settings"), CARMIRROR("carmirror");

    companion object {
        fun of(id: String): RemoteCommand? = values().firstOrNull { it.id == id }
    }
}

/** A routed request. Every route except [Forbidden]/[NotFound] needs the right key. */
sealed class RemoteRoute {
    object Page : RemoteRoute()
    object Status : RemoteRoute()
    object Apps : RemoteRoute()
    object TouchIcon : RemoteRoute()
    data class Command(val cmd: RemoteCommand) : RemoteRoute()
    data class OpenApp(val pkg: String) : RemoteRoute()
    data class AppIcon(val pkg: String) : RemoteRoute()
    object Forbidden : RemoteRoute()
    object NotFound : RemoteRoute()
}

/** "GET /cmd/home?k=abc HTTP/1.1" -> ("/cmd/home", {k=abc}); null when it is not a request line. */
fun parseRequestLine(line: String?): Pair<String, Map<String, String>>? {
    val parts = line?.trim()?.split(' ') ?: return null
    if (parts.size < 2 || parts[0] != "GET") return null
    val target = parts[1]
    val q = target.indexOf('?')
    val path = if (q < 0) target else target.substring(0, q)
    val query = if (q < 0) emptyMap() else parseQuery(target.substring(q + 1))
    return path to query
}

fun parseQuery(s: String): Map<String, String> = s.split('&').filter { it.isNotEmpty() }.associate {
    val eq = it.indexOf('=')
    val k = if (eq < 0) it else it.substring(0, eq)
    val v = if (eq < 0) "" else it.substring(eq + 1)
    dec(k) to dec(v)
}

private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

private val PKG = Regex("[A-Za-z0-9_.]{1,200}")

fun routeRemote(path: String, query: Map<String, String>, key: String): RemoteRoute {
    // The home-screen icon is fetched by iOS without our query string, so it needs no key.
    if (path == "/apple-touch-icon.png" || path == "/apple-touch-icon-precomposed.png") return RemoteRoute.TouchIcon
    if (path == "/favicon.ico") return RemoteRoute.NotFound
    if (key.isEmpty() || query["k"] != key) return RemoteRoute.Forbidden
    val pkg = query["p"]?.takeIf { PKG.matches(it) }
    return when {
        path == "/" -> RemoteRoute.Page
        path == "/status" -> RemoteRoute.Status
        path == "/apps" -> RemoteRoute.Apps
        path == "/open" && pkg != null -> RemoteRoute.OpenApp(pkg)
        path == "/icon" && pkg != null -> RemoteRoute.AppIcon(pkg)
        path.startsWith("/cmd/") -> RemoteCommand.of(path.removePrefix("/cmd/"))
            ?.let { RemoteRoute.Command(it) } ?: RemoteRoute.NotFound
        else -> RemoteRoute.NotFound
    }
}

/** The address to open on the iPhone. */
fun remoteUrl(ip: String, key: String) = "http://$ip:$REMOTE_PORT/?k=$key"

/** Random lowercase key, without look-alike characters (0/o, 1/l). */
fun newRemoteKey(rnd: java.util.Random = java.security.SecureRandom()): String {
    val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
    return (1..6).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
}

/** Minimal JSON string escaping for app names. */
fun jsonStr(s: String): String = buildString {
    append('"')
    for (c in s) when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c < ' ' -> append(String.format("\\u%04x", c.code))
        else -> append(c)
    }
    append('"')
}
