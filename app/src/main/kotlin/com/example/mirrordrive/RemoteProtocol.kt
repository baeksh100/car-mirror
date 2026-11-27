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
    SETTINGS("settings"), CARMIRROR("carmirror"),
    ENTER("enter"), CLEAR_TEXT("cleartext"), BACKSPACE("backspace"),
    POWER_MENU("power"), REBOOT("reboot");

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
    /** Choose which app the Home button opens (null = Android's own home). */
    data class SetHome(val pkg: String?) : RemoteRoute()
    /** Keep an app at the front of the remote's list (or stop keeping it). */
    data class Pin(val pkg: String, val on: Boolean) : RemoteRoute()
    /** Type [text] into the car's focused input box; [replace] clears what is there first. */
    data class TypeText(val text: String, val replace: Boolean) : RemoteRoute()
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
        path == "/sethome" && query["p"].isNullOrEmpty() -> RemoteRoute.SetHome(null)
        path == "/sethome" && pkg != null -> RemoteRoute.SetHome(pkg)
        path == "/pin" && pkg != null -> RemoteRoute.Pin(pkg, query["on"] != "0")
        path == "/text" && !query["t"].isNullOrEmpty() ->
            RemoteRoute.TypeText(query.getValue("t").take(MAX_TEXT), query["mode"] == "replace")
        path.startsWith("/cmd/") -> RemoteCommand.of(path.removePrefix("/cmd/"))
            ?.let { RemoteRoute.Command(it) } ?: RemoteRoute.NotFound
        else -> RemoteRoute.NotFound
    }
}

const val MAX_TEXT = 2000

/**
 * New contents of an input box after typing [add] at the cursor ([selStart]..[selEnd], -1 = none
 * so the end). A box that only shows its hint counts as empty. Returns the text and the cursor.
 */
fun insertText(existing: String?, showingHint: Boolean, selStart: Int, selEnd: Int, add: String, replace: Boolean): Pair<String, Int> {
    val cur = if (replace || showingHint) "" else existing.orEmpty()
    val a = selStart.coerceIn(-1, cur.length)
    val b = selEnd.coerceIn(-1, cur.length)
    val start = if (a < 0 || b < 0) cur.length else minOf(a, b)
    val end = if (a < 0 || b < 0) cur.length else maxOf(a, b)
    val out = cur.substring(0, start) + add + cur.substring(end)
    return out to start + add.length
}

/** Text after one backspace at the cursor (or deleting the selection). */
fun backspaceText(existing: String?, showingHint: Boolean, selStart: Int, selEnd: Int): Pair<String, Int> {
    val cur = if (showingHint) "" else existing.orEmpty()
    val a = selStart.coerceIn(-1, cur.length)
    val b = selEnd.coerceIn(-1, cur.length)
    val start = if (a < 0 || b < 0) cur.length else minOf(a, b)
    val end = if (a < 0 || b < 0) cur.length else maxOf(a, b)
    if (start != end) return cur.substring(0, start) + cur.substring(end) to start
    if (start == 0) return cur to 0
    // Remove a whole surrogate pair (emoji) at once.
    val cut = if (start >= 2 && Character.isSurrogatePair(cur[start - 2], cur[start - 1])) 2 else 1
    return cur.substring(0, start - cut) + cur.substring(start) to start - cut
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
