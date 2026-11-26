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
            onChange()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> { connected = device; log("✓ 연결됨: ${name(device)}") }
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
        if (!a.isEnabled) { log("블루투스가 꺼져 있어요. 켜고 다시 시도하세요."); return }
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

    private fun sendNow(buttons: Int, dx: Int, dy: Int, wheel: Int = 0) {
        val d = connected ?: return
        runCatching { hid?.sendReport(d, MouseReports.REPORT_ID, MouseReports.report(buttons, dx, dy, wheel)) }
            .onFailure { Log.w(TAG, "sendReport", it) }
    }

    /** Relative move from a finger drag (already in mouse counts). */
    fun move(dx: Int, dy: Int, buttons: Int = 0) {
        if (dx == 0 && dy == 0) return
        sender.post { MouseReports.splitMove(dx, dy).forEach { (x, y) -> sendNow(buttons, x, y) } }
    }

    fun scroll(wheel: Int) { sender.post { sendNow(0, 0, 0, wheel) } }

    fun click(button: Int = MouseReports.BUTTON_LEFT) {
        sender.post { sendNow(button, 0, 0) }
        sender.postDelayed({ sendNow(0, 0, 0) }, 40)
    }

    /**
     * Queue a scripted path: each step is sent [gapMs] apart. Used by the corner-home and
     * calibration tests, where pacing matters more than speed.
     */
    fun path(steps: List<Pair<Int, Int>>, gapMs: Long = 8, then: (() -> Unit)? = null) {
        var t = 0L
        steps.forEach { (x, y) -> sender.postDelayed({ sendNow(0, x, y) }, t); t += gapMs }
        if (then != null) sender.postDelayed(then, t + 30)
    }

    fun clearQueue() = sender.removeCallbacksAndMessages(null)

    fun stop() {
        clearQueue()
        stopScan()
        if (receiverOn) runCatching { ctx.unregisterReceiver(btReceiver) }
        receiverOn = false
        runCatching { hid?.let { h -> connected?.let { h.disconnect(it) }; h.unregisterApp() } }
        runCatching { hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } }
        hid = null; proxyReady = false; registered = false; connected = null; proxyRequested = false
        sendThread.quitSafely()
        callbackExec.shutdown()
    }

    companion object { private const val TAG = "HidMouse" }
}
