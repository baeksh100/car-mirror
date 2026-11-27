package com.example.mirrordrive

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * iPhone remote, car side. The user turns this on once in Android's accessibility settings;
 * from then on the system keeps it running, and it serves the remote web page on
 * [REMOTE_PORT] and presses Home / Back / … when the page's buttons are tapped.
 */
class RemoteControlService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private var mdns: RemoteMdns? = null
    private val pool = Executors.newFixedThreadPool(4)
    private val icons = ConcurrentHashMap<String, ByteArray>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        startServer()
        mdns = RemoteMdns(this).also { it.start() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Learn which apps are used on the cast, to order the remote's app list.
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android") return
        if (packageManager.getLaunchIntentForPackage(pkg) == null && !isLauncher(pkg)) return
        runCatching { AppUsage.record(this, pkg) }
    }

    private fun isLauncher(pkg: String) = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg), 0).isNotEmpty()
    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        runCatching { mdns?.stop() }
        runCatching { server?.close() }
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun startServer() {
        if (server != null) return
        Thread({
            try {
                val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(REMOTE_PORT)) }
                server = ss
                Log.i(TAG, "remote listening on $REMOTE_PORT")
                while (!ss.isClosed) {
                    val s = ss.accept()
                    pool.execute { handle(s) }
                }
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "remote server stopped", t)
            }
        }, "remote-http").apply { isDaemon = true }.start()
    }

    private fun handle(s: Socket) = runCatching {
        s.use {
            it.soTimeout = 5000
            val r = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.ISO_8859_1))
            val req = parseRequestLine(r.readLine())
            while (true) { val h = r.readLine() ?: break; if (h.isEmpty()) break }
            val out = it.getOutputStream()
            if (req == null) return@use send(out, 400, "text/plain", "bad request".toByteArray())
            val key = CarSettings.remoteKey(this)
            when (val route = routeRemote(req.first, req.second, key)) {
                RemoteRoute.Page -> send(out, 200, "text/html; charset=utf-8", page())
                RemoteRoute.Status -> send(out, 200, JSON, status().toByteArray())
                RemoteRoute.Apps -> send(out, 200, JSON, apps().toByteArray())
                RemoteRoute.TouchIcon -> send(out, 200, "image/png", icon(packageName, 180), cache = true)
                is RemoteRoute.AppIcon -> send(out, 200, "image/png", icon(route.pkg, 96), cache = true)
                is RemoteRoute.Command -> if (route.cmd == RemoteCommand.REBOOT) {
                    // Off the main thread: asking for root can take a few seconds.
                    val mode = reboot()
                    send(out, if (mode == null) 500 else 200, JSON, "{\"ok\":${mode != null},\"mode\":${jsonStr(mode ?: "")}}".toByteArray())
                } else {
                    val ok = onMain { perform(route.cmd) }
                    send(out, if (ok) 200 else 500, JSON, status().toByteArray())
                }
                is RemoteRoute.OpenApp -> {
                    val ok = onMain { launch(launchIntent(route.pkg)) }
                    if (ok) runCatching { AppUsage.record(this, route.pkg) }
                    send(out, if (ok) 200 else 404, JSON, "{\"ok\":$ok}".toByteArray())
                }
                is RemoteRoute.TypeText -> {
                    val ok = onMain { typeText(route.text, route.replace) }
                    send(out, if (ok) 200 else 409, JSON, "{\"ok\":$ok}".toByteArray())
                }
                is RemoteRoute.Pin -> {
                    AppUsage.setPinned(this, route.pkg, route.on)
                    send(out, 200, JSON, "{\"ok\":true}".toByteArray())
                }
                is RemoteRoute.SetHome -> {
                    CarSettings.setRemoteHome(this, route.pkg)
                    send(out, 200, JSON, status().toByteArray())
                }
                RemoteRoute.Forbidden -> send(out, 403, "text/html; charset=utf-8", FORBIDDEN.toByteArray())
                RemoteRoute.NotFound -> send(out, 404, "text/plain", "not found".toByteArray())
            }
        }
    }.onFailure { Log.w(TAG, "request failed", it) }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray, cache: Boolean = false) {
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: ${if (cache) "max-age=86400" else "no-store"}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    /** Run on the main thread and wait (briefly) for the answer. */
    private fun onMain(block: () -> Boolean): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = false
        main.post { result = runCatching(block).getOrDefault(false); latch.countDown() }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    private fun perform(cmd: RemoteCommand): Boolean = when (cmd) {
        RemoteCommand.HOME -> homeTarget()
            ?.let { runCatching { launch(launchIntent(it)) }.getOrDefault(false) }
            ?.takeIf { it } ?: performGlobalAction(GLOBAL_ACTION_HOME)
        RemoteCommand.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        RemoteCommand.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        RemoteCommand.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        RemoteCommand.QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        RemoteCommand.SCROLL_UP -> swipe(up = false)
        RemoteCommand.SCROLL_DOWN -> swipe(up = true)
        RemoteCommand.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
        RemoteCommand.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
        RemoteCommand.MUTE -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
        RemoteCommand.SETTINGS -> launch(Intent(Settings.ACTION_SETTINGS))
        RemoteCommand.CARMIRROR -> launch(Intent(this, MainActivity::class.java))
        RemoteCommand.ENTER -> enter()
        RemoteCommand.POWER_MENU -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        RemoteCommand.REBOOT -> false   // handled off the main thread, see reboot()
        RemoteCommand.CLEAR_TEXT -> editFocused { n -> setText(n, "", 0) }
        RemoteCommand.BACKSPACE -> editFocused { n ->
            val (t, c) = backspaceText(n.text?.toString(), hint(n), n.textSelectionStart, n.textSelectionEnd)
            setText(n, t, c)
        }
    }

    private fun launch(i: Intent?): Boolean {
        i ?: return false
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        return true
    }

    /**
     * Where Home goes: the app the user picked on the remote, else the car's own launcher when
     * there is exactly one launcher besides Android's default home (so the car mode opens with
     * no setup), else null (Android's Home).
     */
    private fun homeTarget(): String? {
        CarSettings.remoteHome(this)?.let { return it }
        val pm = packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val default = pm.resolveActivity(homeIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        val others = pm.queryIntentActivities(homeIntent, 0).map { it.activityInfo.packageName }.distinct()
            .filter { it != default && it != packageName && it != "android" && it != "com.android.settings" }
        return others.singleOrNull()
    }

    /** Normal app entry, or for a launcher-only app (car mode) its home screen activity. */
    private fun launchIntent(pkg: String): Intent? {
        packageManager.getLaunchIntentForPackage(pkg)?.let { return it }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg)
        val ri = packageManager.queryIntentActivities(home, 0).firstOrNull() ?: return null
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
    }

    /**
     * Restart the cast. An ordinary app may not reboot the device, so: if the cast is rooted
     * (many head units are), "su -c reboot"; otherwise open the power menu so "다시 시작" is one tap
     * on the car screen. Returns "reboot", "dialog", or null when neither worked.
     */
    private fun reboot(): String? {
        val hasSu = listOf("/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su")
            .any { java.io.File(it).exists() }
        if (hasSu) {
            val ok = runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
                // A successful reboot never returns; a refusal exits non-zero quickly.
                !p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() == 0
            }.getOrDefault(false)
            if (ok) return "reboot"
        }
        return if (onMain { performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) }) "dialog" else null
    }

    // ---- typing into the car's input box -----------------------------------------------------

    /** The input box that has focus, or else the first editable box on screen. */
    private fun focusedInput(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 400) {
            val n = queue.removeFirst()
            if (n.isEditable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun hint(n: AccessibilityNodeInfo) = n.isShowingHintText

    private fun editFocused(block: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val n = focusedInput() ?: return false
        if (!n.isFocused) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return block(n)
    }

    private fun setText(n: AccessibilityNodeInfo, text: String, cursor: Int): Boolean {
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) runCatching {
            n.refresh()
            val c = cursor.coerceIn(0, n.text?.length ?: text.length)
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, android.os.Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, c)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, c)
            })
        }
        return ok
    }

    private fun typeText(add: String, replace: Boolean): Boolean = editFocused { n ->
        val (t, c) = insertText(n.text?.toString(), hint(n), n.textSelectionStart, n.textSelectionEnd, add, replace)
        setText(n, t, c) || paste(n, add, replace)
    }

    /** Fallback for boxes that refuse SET_TEXT: put the text on the clipboard and paste it. */
    private fun paste(n: AccessibilityNodeInfo, add: String, replace: Boolean): Boolean = runCatching {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("CarMirror", add))
        if (replace) n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, n.text?.length ?: 0)
        })
        n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }.getOrDefault(false)

    /** The keyboard's Enter/Search/Go key (Android 11+). */
    private fun enter(): Boolean {
        val n = focusedInput() ?: return false
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return false
    }

    /** A finger swipe in the middle of the car screen; [up] moves the finger up (shows what is below). */
    private fun swipe(up: Boolean): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val top = dm.heightPixels * 0.3f
        val bottom = dm.heightPixels * 0.7f
        val p = Path().apply { if (up) { moveTo(x, bottom); lineTo(x, top) } else { moveTo(x, top); lineTo(x, bottom) } }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 250)).build()
        return dispatchGesture(g, null, null)
    }

    private fun audio() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun volume(dir: Int): Boolean {
        audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        return true
    }

    private fun status(): String {
        val a = audio()
        val v = a.getStreamVolume(AudioManager.STREAM_MUSIC)
        val m = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val muted = a.isStreamMute(AudioManager.STREAM_MUSIC)
        val home = homeTarget()
        val homeName = home?.let { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrDefault(it) }
        return "{\"ok\":true,\"vol\":$v,\"max\":$m,\"muted\":$muted,\"name\":${jsonStr(CarSettings.receiverName(this))}," +
            "\"home\":${home?.let { jsonStr(it) } ?: "null"},\"homeName\":${homeName?.let { jsonStr(it) } ?: "null"}}"
    }

    private fun apps(): String {
        val pm = packageManager
        // Launchers too: the car mode app may only be a HOME app.
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val homePkgs = homes.map { it.activityInfo.packageName }.toSet()
        val list = (homes + apps)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
        val pins = AppUsage.pins(this)
        val now = System.currentTimeMillis()
        val ordered = orderApps(list, pins) { AppUsage.score(this, it, now) }
        return ordered.joinToString(",", "[", "]") { (p, n) ->
            "{\"p\":${jsonStr(p)},\"n\":${jsonStr(n)},\"launcher\":${p in homePkgs},\"pin\":${p in pins}}"
        }
    }

    private fun icon(pkg: String, size: Int): ByteArray = icons.getOrPut("$pkg@$size") {
        val d = packageManager.getApplicationIcon(pkg)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (size >= 180) c.drawColor(0xFF111418.toInt())   // iOS shows transparency as black anyway
        d.setBounds(0, 0, size, size)
        d.draw(c)
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun page(): ByteArray = assets.open("remote.html").use { it.readBytes() }

    companion object {
        private const val TAG = "RemoteControl"
        private const val JSON = "application/json; charset=utf-8"
        private const val FORBIDDEN = "<meta name=viewport content='width=device-width'>" +
            "<p style='font:18px sans-serif;padding:24px'>주소가 맞지 않아요. 차 화면 CarMirror 설정의 " +
            "QR 코드를 다시 찍어 주세요.</p>"

        /** True while Android has this service turned on and bound. */
        @Volatile var running = false
            private set

        /** Whether the user switched this service on in accessibility settings. */
        fun isEnabled(ctx: Context): Boolean {
            if (running) return true
            val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return on.split(':').any { it.startsWith(ctx.packageName + "/") && it.endsWith("RemoteControlService") }
        }
    }
}
