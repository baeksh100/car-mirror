package com.example.mirrordrive

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors

/**
 * Experimental: makes this box show up to the iPhone as a Bluetooth mouse
 * (Android BluetoothHidDevice, API 28+). The iPhone uses it through AssistiveTouch.
 *
 * Every Bluetooth call is wrapped: a car ROM can lack the HID-device profile, deny the
 * permission, or throw from the stack — none of that may crash the app.
 */
@SuppressLint("MissingPermission")
class HidMouse(
    private val ctx: Context,
    private val log: (String) -> Unit,
    private val onChange: () -> Unit,
) {
    val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile var proxyReady = false; private set
    @Volatile var registered = false; private set
    @Volatile var connected: BluetoothDevice? = null; private set
    @Volatile var proxyRequested = false; private set

    private var hid: BluetoothHidDevice? = null
    private val callbackExec = Executors.newSingleThreadExecutor()
    // Reports are sent from their own thread with small gaps so long moves are not dropped.
    private val sendThread = HandlerThread("hid-mouse").apply { start() }
    private val sender = Handler(sendThread.looper)

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, reg: Boolean) {
            registered = reg
            log(if (reg) "✓ 마우스 등록 성공" else "마우스 등록 해제됨")
            if (reg && pluggedDevice != null) log("이전에 연결했던 기기: ${name(pluggedDevice)}")
            if (reg) runCatching { hid?.connectedDevices?.firstOrNull() }.getOrNull()?.let { connected = it; log("✓ 이미 연결됨: ${name(it)}") }
            // Reconnect the last iPhone on its own, so touch control works right after start-up.
            if (reg) {
                pluggedDevice?.address?.let { MousePrefs.setLastHost(ctx, it) }
                sender.removeCallbacks(reconnectTick)
                sender.postDelayed(reconnectTick, 500)
            }
            onChange()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = device; tracker.invalidate(); log("✓ 연결됨: ${name(device)}")
                    MousePrefs.setLastHost(ctx, device.address)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connected?.address == device.address) connected = null
                    log("연결 끊김: ${name(device)}")
                }
                BluetoothProfile.STATE_CONNECTING -> log("연결 중… ${name(device)}")
            }
            onChange()
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            runCatching { hid?.replyReport(device, type, id, MouseReports.report(0, 0, 0)) }
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            log("프로토콜 설정: $protocol")
        }
    }

    /**
     * While registered but not connected, keep re-trying the remembered iPhone (every 20s): in
     * the car the box often starts before the iPhone is in range or has Bluetooth on.
     */
    private var reconnectTries = 0
    private var saidBtOff = false
    private val reconnectTick: Runnable = object : Runnable {
        override fun run() {
            if (!registered) return
            if (connected == null) {
                val addr = MousePrefs.lastHost(ctx) ?: return
                val d = runCatching { adapter?.getRemoteDevice(addr) }.getOrNull() ?: return
                if (reconnectTries++ % 6 == 0) log("기억한 아이폰에 다시 연결 시도: ${name(d)}")
                runCatching { hid?.connect(d) }
            }
            sender.postDelayed(this, 20_000)
        }
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = proxy as BluetoothHidDevice
            proxyReady = true
            log("✓ 블루투스 마우스 기능 있음 (HID Device)")
            register()
            onChange()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            proxyReady = false; registered = false; connected = null; hid = null
            log("마우스 기능 연결이 끊겼어요")
            onChange()
        }
    }

    fun start() {
        val a = adapter
        if (a == null) { log("✗ 이 기기에 블루투스가 없어요"); return }
        if (!a.isEnabled) {
            if (!saidBtOff) log("블루투스가 꺼져 있어요. 켜고 다시 시도하세요.")
            saidBtOff = true
            return
        }
        saidBtOff = false
        if (proxyRequested) { if (proxyReady && !registered) register(); return }
        val ok = runCatching { a.getProfileProxy(ctx, listener, BluetoothProfile.HID_DEVICE) }
            .onFailure { log("✗ 마우스 기능 요청 실패: ${it.javaClass.simpleName} ${it.message}") }
            .getOrDefault(false)
        proxyRequested = ok
        if (!ok) log("✗ 이 기기는 블루투스 마우스 역할(HID Device)을 지원하지 않아요")
        else sender.postDelayed({
            if (!proxyReady) log("✗ 3초 동안 응답 없음 — 이 기기 펌웨어에서 마우스 역할이 꺼져 있을 수 있어요")
        }, 3000)
    }

    private fun register() {
        val h = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "CarMirror Mouse", "CarMirror touch-to-mouse", "CarMirror",
            BluetoothHidDevice.SUBCLASS1_MOUSE, MouseReports.DESCRIPTOR,
        )
        val ok = runCatching { h.registerApp(sdp, null, null, callbackExec, callback) }
            .onFailure { log("✗ 마우스 등록 오류: ${it.javaClass.simpleName} ${it.message}") }
            .getOrDefault(false)
        if (!ok) log("✗ 마우스 등록 요청이 거절됐어요 (다른 앱이 이미 쓰고 있을 수 있어요)")
        else log("마우스 등록 요청 보냄…")
    }

    // ---- pairing from this side -------------------------------------------------------------
    // The iPhone often hides an Android box from its AssistiveTouch device list (the box says it
    // is a phone/tablet, not a mouse). Pairing *from the box* and then connecting the mouse
    // profile avoids that list entirely.

    /** Device we paired from here and should connect as soon as bonding finishes. */
    @Volatile private var pendingConnect: String? = null
    private var receiverOn = false
    var onFound: ((BluetoothDevice) -> Unit)? = null

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d: BluetoothDevice = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> onFound?.invoke(d)
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    when (st) {
                        BluetoothDevice.BOND_BONDING -> log("페어링 중… ${name(d)} (양쪽 화면의 숫자가 같으면 '연결/페어링'을 누르세요)")
                        BluetoothDevice.BOND_BONDED -> {
                            log("✓ 페어링 완료: ${name(d)}")
                            if (pendingConnect == d.address) { pendingConnect = null; sender.postDelayed({ connect(d) }, 800) }
                        }
                        BluetoothDevice.BOND_NONE -> if (pendingConnect == d.address) { pendingConnect = null; log("✗ 페어링 실패/취소: ${name(d)}") }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> log("주변 기기 찾기 끝")
            }
        }
    }

    private fun ensureReceiver() {
        if (receiverOn) return
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        runCatching { ctx.registerReceiver(btReceiver, f); receiverOn = true }
            .onFailure { log("✗ 블루투스 알림 등록 실패: ${it.message}") }
    }

    fun startScan(): Boolean {
        ensureReceiver()
        val a = adapter ?: return false
        runCatching { if (a.isDiscovering) a.cancelDiscovery() }
        val ok = runCatching { a.startDiscovery() }
            .onFailure { log("✗ 주변 기기 찾기 오류: ${it.message}") }.getOrDefault(false)
        log(if (ok) "주변 기기 찾는 중… (아이폰 설정 → 블루투스 화면을 켜 두세요)" else "✗ 주변 기기 찾기를 시작하지 못했어요 (위치/주변 기기 권한 확인)")
        return ok
    }

    fun stopScan() { runCatching { adapter?.cancelDiscovery() } }

    /** Pair (if needed) and then connect the mouse profile to [d]. */
    fun pairAndConnect(d: BluetoothDevice) {
        ensureReceiver()
        stopScan()
        if (!registered) { log("먼저 '마우스 등록 성공'이 떠야 해요. '1. 시작'을 눌러 보세요."); return }
        val bonded = runCatching { d.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        if (bonded) { connect(d); return }
        pendingConnect = d.address
        val ok = runCatching { d.createBond() }.onFailure { log("✗ 페어링 오류: ${it.message}") }.getOrDefault(false)
        if (!ok) { pendingConnect = null; log("✗ 페어링 요청 실패: ${name(d)}") } else log("${name(d)}에 페어링 요청…")
    }

    /** Forget a pairing (an old phone-style pairing can block the mouse profile). */
    fun unpair(d: BluetoothDevice) {
        val ok = runCatching { d.javaClass.getMethod("removeBond").invoke(d) as? Boolean ?: false }.getOrDefault(false)
        log(if (ok) "페어링 지움: ${name(d)}" else "✗ 페어링을 지우지 못했어요. 블루투스 설정에서 직접 지워 주세요.")
    }

    fun bondedDevices(): List<BluetoothDevice> =
        runCatching { adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())

    fun connect(device: BluetoothDevice) {
        val ok = runCatching { hid?.connect(device) == true }
            .onFailure { log("✗ 연결 오류: ${it.message}") }.getOrDefault(false)
        log(if (ok) "${name(device)}에 연결 요청…" else "${name(device)} 연결 요청 실패 (먼저 마우스 등록이 돼야 해요)")
    }

    fun name(d: BluetoothDevice): String = runCatching { d.name }.getOrNull() ?: d.address

    // ---- input -----------------------------------------------------------------------------
    // Every report goes through one paced queue so scripted paths (corner homing, moving to a
    // spot) and live finger moves never overtake each other.

    private val queueLock = Any()
    private val QUEUE = Any()  // token: clearQueue drops only queued input, not reconnects
    private var nextAt = 0L

    private fun sendNow(buttons: Int, dx: Int, dy: Int, wheel: Int = 0) {
        val d = connected ?: return
        runCatching { hid?.sendReport(d, MouseReports.REPORT_ID, MouseReports.report(buttons, dx, dy, wheel)) }
            .onFailure { Log.w(TAG, "sendReport", it) }
    }

    private fun enqueue(buttons: Int, dx: Int, dy: Int, wheel: Int = 0, gapMs: Long = 4) {
        synchronized(queueLock) {
            val at = maxOf(SystemClock.uptimeMillis(), nextAt)
            sender.postAtTime({ sendNow(buttons, dx, dy, wheel) }, QUEUE, at)
            nextAt = at + gapMs
        }
    }

    private fun enqueueAction(delayAfterMs: Long, action: () -> Unit) {
        synchronized(queueLock) {
            val at = maxOf(SystemClock.uptimeMillis(), nextAt) + delayAfterMs
            sender.postAtTime(action, QUEUE, at)
            nextAt = at
        }
    }

    /** Relative move from a finger drag (already in mouse counts), optionally with a button held. */
    fun move(dx: Int, dy: Int, buttons: Int = 0, tracked: Boolean = false) {
        if (dx == 0 && dy == 0) return
        if (!tracked) tracker.invalidate()
        MouseReports.splitMove(dx, dy).forEach { (x, y) -> enqueue(buttons, x, y) }
    }

    fun scroll(wheel: Int) = enqueue(0, 0, 0, wheel)

    /** Press and release; [holdMs] longer than ~500ms acts as a long press on the iPhone. */
    fun click(button: Int = MouseReports.BUTTON_LEFT, holdMs: Long = 40) {
        enqueue(button, 0, 0, gapMs = holdMs.coerceIn(20, 2000))
        enqueue(0, 0, 0)
    }

    fun press(button: Int = MouseReports.BUTTON_LEFT) = enqueue(button, 0, 0, gapMs = 30)
    fun release() = enqueue(0, 0, 0, gapMs = 20)

    /** Queue a scripted path, each step [gapMs] apart (pacing matters more than speed here). */
    fun path(steps: List<Pair<Int, Int>>, gapMs: Long = 8, then: (() -> Unit)? = null) {
        steps.forEach { (x, y) -> enqueue(0, x, y, gapMs = gapMs) }
        if (then != null) enqueueAction(30, then)
    }

    /**
     * Pin the pointer into the top-left corner, then move to the fraction (fx, fy) of the screen,
     * where [fullX]/[fullY] are the calibrated counts for one full screen width/height.
     */
    fun goToFraction(fx: Float, fy: Float, fullX: Int, fullY: Int, forceHome: Boolean = false) {
        if (forceHome) tracker.invalidate()
        val plan = tracker.plan((fx * fullX).toInt(), (fy * fullY).toInt(), fullX, fullY, SystemClock.uptimeMillis())
        if (plan.home) {
            // Overshooting into the corner is harmless, so these go out fast.
            val homeCount = ((maxOf(fullX, fullY) * 1.5f) / 127f).toInt().coerceIn(8, 40) + 4
            path(MouseReports.homeMoves(homeCount), gapMs = 4)
        }
        path(MouseReports.splitMove(plan.dx, plan.dy, CALIB_STEP))
    }

    /** A tracked drag ended with the pointer at the fraction (fx, fy). */
    fun draggedTo(fx: Float, fy: Float, fullX: Int, fullY: Int) =
        tracker.movedTo((fx * fullX).toInt(), (fy * fullY).toInt())

    /** Where the pointer is believed to be, tracked by [goToFraction]. */
    private val tracker = PointerTracker()

    fun clearQueue() {
        tracker.invalidate()
        sender.removeCallbacksAndMessages(QUEUE)
        synchronized(queueLock) { nextAt = 0L }
    }

    fun stop() {
        sender.removeCallbacksAndMessages(null)
        stopScan()
        if (receiverOn) runCatching { ctx.unregisterReceiver(btReceiver) }
        receiverOn = false
        runCatching { hid?.let { h -> connected?.let { h.disconnect(it) }; h.unregisterApp() } }
        runCatching { hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } }
        hid = null; proxyReady = false; registered = false; connected = null; proxyRequested = false
        sendThread.quitSafely()
        callbackExec.shutdown()
    }

    companion object {
        private const val TAG = "HidMouse"
        /** Small, even steps keep iOS pointer acceleration predictable for calibration. */
        const val CALIB_STEP = 8
    }
}

/**
 * One shared mouse for the whole app: the test screen and the mirror screen use the same
 * registration, so the iPhone stays connected when you leave the test screen.
 */
object MouseHub {
    @Volatile private var inst: HidMouse? = null
    private val lines = ArrayDeque<String>()
    /** The screen currently showing mouse status (test screen); called on any change. */
    @Volatile var listener: (() -> Unit)? = null

    fun get(ctx: Context): HidMouse = inst ?: synchronized(this) {
        inst ?: HidMouse(ctx.applicationContext, ::log) { listener?.invoke() }.also { inst = it }
    }

    fun peek(): HidMouse? = inst

    fun release() = synchronized(this) { inst?.stop(); inst = null }

    fun log(s: String) {
        synchronized(lines) { lines.addFirst(s); while (lines.size > 14) lines.removeLast() }
        listener?.invoke()
    }

    fun logText(): String = synchronized(lines) { lines.joinToString("\n") }
}

/** Mouse settings (separate prefs file from [CarSettings]). */
object MousePrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("carmirror_mouse", Context.MODE_PRIVATE)

    fun fullX(ctx: Context) = p(ctx).getInt("fullX", 400)
    fun setFullX(ctx: Context, v: Int) = p(ctx).edit().putInt("fullX", v.coerceIn(20, 5000)).apply()
    fun fullY(ctx: Context) = p(ctx).getInt("fullY", 800)
    fun setFullY(ctx: Context, v: Int) = p(ctx).edit().putInt("fullY", v.coerceIn(20, 5000)).apply()

    /** Touching the mirrored iPhone screen drives the iPhone through the mouse. */
    // Defaults to on once an iPhone has been connected from the test screen.
    fun mirrorTouch(ctx: Context) = p(ctx).getBoolean("mirror_touch", lastHost(ctx) != null)
    fun setMirrorTouch(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("mirror_touch", on).apply()

    fun lastHost(ctx: Context): String? = p(ctx).getString("last_host", null)
    fun setLastHost(ctx: Context, addr: String) = p(ctx).edit().putString("last_host", addr).apply()
}
