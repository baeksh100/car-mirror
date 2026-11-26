package com.example.mirrordrive

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
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
        runCatching { hid?.let { h -> connected?.let { h.disconnect(it) }; h.unregisterApp() } }
        runCatching { hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } }
        hid = null; proxyReady = false; registered = false; connected = null; proxyRequested = false
        sendThread.quitSafely()
        callbackExec.shutdown()
    }

    companion object { private const val TAG = "HidMouse" }
}
