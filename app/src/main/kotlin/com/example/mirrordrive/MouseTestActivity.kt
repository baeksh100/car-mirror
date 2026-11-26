package com.example.mirrordrive

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.hypot

/**
 * Experimental test screen (opened from 설정 → "블루투스 마우스 시험"): checks whether this box
 * can act as a Bluetooth mouse for the iPhone, and lets you drive the iPhone's AssistiveTouch
 * pointer from a touch pad on the car screen. Nothing here touches mirroring.
 */
class MouseTestActivity : AppCompatActivity() {
    private lateinit var mouse: HidMouse
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var calibText: TextView
    private lateinit var absMode: CheckBox
    private lateinit var pad: View
    private val logLines = ArrayDeque<String>()

    private val prefs by lazy { getSharedPreferences("carmirror_mouse", Context.MODE_PRIVATE) }
    private var sensitivity: Float
        get() = prefs.getFloat("sens", 1.0f)
        set(v) { prefs.edit().putFloat("sens", v).apply() }
    // Mouse counts that carry the pointer from the left edge to the right edge (and top→bottom),
    // found with the calibration buttons. Depends on the iPhone's AssistiveTouch tracking speed.
    private var fullX: Int
        get() = prefs.getInt("fullX", 400)
        set(v) { prefs.edit().putInt("fullX", v.coerceIn(20, 5000)).apply() }
    private var fullY: Int
        get() = prefs.getInt("fullY", 800)
        set(v) { prefs.edit().putInt("fullY", v.coerceIn(20, 5000)).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mouse = HidMouse(applicationContext, ::log) { runOnUiThread { refresh() } }
        setContentView(buildUi())
        log("기기: ${Build.MANUFACTURER} ${Build.MODEL}, 안드로이드 SDK ${Build.VERSION.SDK_INT}")
        log("블루투스 이름: ${runCatching { mouse.adapter?.name }.getOrNull() ?: "-"}")
        if (hasPerms()) mouse.start() else requestPerms()
        refresh()
    }

    // ---- permissions (Android 12+) ---------------------------------------------------------

    private val perms: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31) arrayOf(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_SCAN,
        ) else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms() = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPerms() {
        runCatching { requestPermissions(perms, 7) }.onFailure { log("권한 요청 실패: ${it.message}") }
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (hasPerms()) mouse.start() else { log("✗ 블루투스 권한을 다 받지 못했어요. 일부 기능이 안 될 수 있어요."); mouse.start() }
    }

    // ---- UI ----------------------------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.GROUND)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(col) }
        root.addView(scroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.42f))

        col.addView(text("블루투스 마우스 시험 (실험)", 20f, Palette.MIRROR))
        status = text("", 15f, Palette.SILVER).also { col.addView(it) }
        col.addView(text(
            "아이폰 준비: 설정 → 손쉬운 사용 → 터치 → AssistiveTouch 켜기. " +
                "그다음 아이폰 설정 → 블루투스 화면을 켜 둔 채로 아래 2번을 누르세요. " +
                "전에 이 기기와 아이폰을 연결한 적이 있으면 양쪽에서 먼저 '이 기기 지우기'를 하세요.",
            13f, Palette.SILVER_DIM,
        ))
        col.addView(button("1. 시작 / 다시 시작") { if (hasPerms()) mouse.start() else requestPerms() })
        col.addView(button("2. 아이폰 찾아서 연결 (추천)") { scanAndPick() })
        col.addView(button("연결했던 기기에 다시 연결") { pickBonded() })
        col.addView(button("다른 방법: 아이폰에서 찾게 하기 (2분)") { makeDiscoverable() })
        col.addView(row(
            button("클릭") { mouse.click() },
            button("왼쪽 위 구석으로") { mouse.path(MouseReports.homeMoves()) },
        ))
        col.addView(row(
            button("감도 −") { sensitivity = (sensitivity - 0.25f).coerceAtLeast(0.25f); refresh() },
            button("감도 +") { sensitivity = (sensitivity + 0.25f).coerceAtMost(5f); refresh() },
        ))

        col.addView(text("위치 맞추기 시험", 16f, Palette.MIRROR).apply { setPadding(0, dp(14), 0, 0) })
        col.addView(text(
            "'가로 끝까지'를 누르면 커서가 왼쪽 위 구석에 붙었다가 오른쪽으로 칸 수만큼 가요. " +
                "딱 오른쪽 끝에 멈출 때까지 칸 수를 조절하세요. 세로도 같은 방법이에요.",
            13f, Palette.SILVER_DIM,
        ))
        calibText = text("", 14f, Palette.SILVER).also { col.addView(it) }
        col.addView(row(
            button("가로 −") { fullX -= 25; refresh() },
            button("가로 +") { fullX += 25; refresh() },
            button("가로 끝까지") { goTo(1f, 0f, click = false) },
        ))
        col.addView(row(
            button("세로 −") { fullY -= 25; refresh() },
            button("세로 +") { fullY += 25; refresh() },
            button("세로 끝까지") { goTo(0f, 1f, click = false) },
        ))
        absMode = CheckBox(this).apply {
            text = "패드를 누른 곳으로 커서 보내고 누르기 (아이폰 화면 비율로)"
            setTextColor(Palette.SILVER)
            isChecked = prefs.getBoolean("abs", false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("abs", on).apply(); if (::pad.isInitialized) pad.invalidate() }
        }
        col.addView(absMode)

        logView = text("", 12f, Palette.SILVER_DIM).apply { setPadding(0, dp(10), 0, 0) }
        col.addView(logView)
        col.addView(button("닫기") { finish() })

        pad = TrackpadView(this)
        root.addView(pad, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.58f).apply {
            marginStart = dp(12)
        })
        return root
    }

    private fun text(s: String, size: Float, color: Int) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        val dev = mouse.connected
        status.text = when {
            dev != null -> "● 연결됨: ${mouse.name(dev)}"
            mouse.registered -> "○ 마우스 준비됨 — 아이폰에서 연결하세요"
            mouse.proxyReady -> "○ 마우스 기능 있음 — 등록 중"
            else -> "○ 확인 중… (아래 기록을 보세요)"
        }
        status.setTextColor(if (dev != null) Palette.LIVE else Palette.SILVER)
        calibText.text = "가로 끝까지 ${fullX}칸 · 세로 끝까지 ${fullY}칸 · 감도 ${"%.2f".format(sensitivity)}"
    }

    private fun log(s: String) {
        runOnUiThread {
            logLines.addFirst(s)
            while (logLines.size > 14) logLines.removeLast()
            if (::logView.isInitialized) logView.text = "기록\n" + logLines.joinToString("\n")
        }
    }

    private fun makeDiscoverable() {
        runCatching {
            startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120),
            )
        }.onFailure { log("✗ 검색 허용 화면을 열 수 없어요: ${it.message}") }
    }

    /** Scan for nearby devices; the list fills in live and tapping one pairs + connects. */
    private fun scanAndPick() {
        val found = ArrayList<android.bluetooth.BluetoothDevice>()
        val names = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        mouse.onFound = { d ->
            runOnUiThread {
                if (found.none { it.address == d.address }) {
                    found += d
                    names.add(mouse.name(d))
                }
            }
        }
        if (!mouse.startScan()) return
        AlertDialog.Builder(this)
            .setTitle("아이폰을 고르세요 (찾는 중…)")
            .setAdapter(names) { _, i -> mouse.pairAndConnect(found[i]) }
            .setNegativeButton("취소") { _, _ -> mouse.stopScan() }
            .setOnDismissListener { mouse.onFound = null }
            .show()
    }

    private fun pickBonded() {
        val list = mouse.bondedDevices()
        if (list.isEmpty()) { log("연결했던 기기가 없어요. 아이폰에서 먼저 연결하세요."); return }
        AlertDialog.Builder(this)
            .setTitle("어느 기기에 연결할까요?")
            .setItems(list.map { mouse.name(it) }.toTypedArray()) { _, i -> mouse.pairAndConnect(list[i]) }
            .setNeutralButton("페어링 지우기…") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("어느 기기의 페어링을 지울까요?")
                    .setItems(list.map { mouse.name(it) }.toTypedArray()) { _, i -> mouse.unpair(list[i]) }
                    .setNegativeButton("취소", null)
                    .show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** Home to the top-left corner, then move to the fraction (fx, fy) of the iPhone screen. */
    private fun goTo(fx: Float, fy: Float, click: Boolean) {
        if (mouse.connected == null) { log("먼저 아이폰과 연결하세요"); return }
        mouse.clearQueue()
        val steps = MouseReports.homeMoves() +
            MouseReports.splitMove((fx * fullX).toInt(), (fy * fullY).toInt(), CALIB_STEP)
        mouse.path(steps, then = if (click) ({ mouse.click() }) else null)
    }

    override fun onDestroy() {
        mouse.stop()
        super.onDestroy()
    }

    /**
     * The touch pad. Drag = move the pointer, quick tap = click, two-finger drag = scroll.
     * In "absolute" mode a tap instead sends the pointer to the same spot on the iPhone screen
     * (pad position as a fraction) and clicks there.
     */
    private inner class TrackpadView(ctx: Context) : View(ctx) {
        private val acc = MotionAccumulator()
        private var lastX = 0f
        private var lastY = 0f
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var moved = false
        private var scrollAcc = 0f
        private var twoFinger = false
        private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.SILVER_DIM; textAlign = Paint.Align.CENTER; textSize = dpf(16)
        }

        init {
            background = GradientDrawable().apply {
                setColor(Palette.SURFACE)
                cornerRadius = dpf(16)
                setStroke(dp(1), Palette.MIRROR_HAIRLINE)
            }
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val msg = if (absMode.isChecked) "누른 곳으로 커서 이동 + 누르기" else "끌기 = 커서 이동 · 톡 = 누르기 · 두 손가락 = 스크롤"
            c.drawText(msg, width / 2f, height / 2f, hint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; downX = e.x; downY = e.y
                    downAt = SystemClock.uptimeMillis(); moved = false; twoFinger = false
                    acc.reset(); acc.sensitivity = sensitivity
                }
                MotionEvent.ACTION_POINTER_DOWN -> { twoFinger = true; lastY = avgY(e); scrollAcc = 0f }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(e.x - downX, e.y - downY) > dpf(8)) moved = true
                    if (absMode.isChecked) return true
                    if (twoFinger && e.pointerCount >= 2) {
                        val y = avgY(e)
                        scrollAcc += (y - lastY) / dpf(24)
                        lastY = y
                        val n = scrollAcc.toInt()
                        if (n != 0) { mouse.scroll(n); scrollAcc -= n }
                    } else if (!twoFinger) {
                        val (dx, dy) = acc.add(e.x - lastX, e.y - lastY)
                        lastX = e.x; lastY = e.y
                        mouse.move(dx, dy)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val quick = SystemClock.uptimeMillis() - downAt < 300
                    if (!moved && quick && !twoFinger) {
                        if (absMode.isChecked) {
                            goTo((e.x / width).coerceIn(0f, 1f), (e.y / height).coerceIn(0f, 1f), click = true)
                        } else mouse.click()
                    }
                }
            }
            return true
        }

        private fun avgY(e: MotionEvent): Float {
            var s = 0f
            for (i in 0 until e.pointerCount) s += e.getY(i)
            return s / e.pointerCount
        }
    }

    companion object {
        /** Small, even steps keep iOS pointer acceleration predictable for calibration. */
        const val CALIB_STEP = 8
    }
}
