package com.example.mirrordrive

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Position calibration done on the mirror screen itself, so you watch the real iPhone pointer:
 * the pointer is pinned to the top-left corner and moved a known distance; you tap where it
 * stopped, and the full-screen counts are worked out from that. Then the pointer goes to the
 * centre to check. Only a thin bar at the top covers the picture.
 */
@SuppressLint("ViewConstructor")
class CalibrateOverlay(
    ctx: Context,
    private val video: View,
    private val videoSize: () -> Pair<Int, Int>,
    private val onDone: () -> Unit,
) : FrameLayout(ctx) {
    private val msg = TextView(ctx).apply {
        setTextColor(Color.WHITE); textSize = 16f
    }
    private var counts = 300
    private var waitingTap = false

    init {
        isClickable = true
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(8), ctx.dp(8))
            background = GradientDrawable().apply {
                setColor(0xCC0B0F14.toInt()); cornerRadius = ctx.dpf(14)
            }
        }
        bar.addView(msg, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(button("다시") { start() })
        bar.addView(button("완료") { finish() })
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(ctx.dp(16), ctx.dp(72), ctx.dp(16), 0)
        })
    }

    private fun button(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun mouse(): HidMouse? = MouseHub.peek()?.takeIf { it.connected != null }

    fun start() {
        val m = mouse()
        if (m == null) { msg.text = "아이폰이 연결돼 있지 않아요. ⚙ → 아이폰 터치 조작에서 먼저 연결하세요."; return }
        msg.text = "커서가 움직여요. 멈추면 커서 끝(화살표 끝)을 손가락으로 눌러 주세요."
        m.clearQueue()
        m.path(MouseReports.homeMoves(), gapMs = 4)
        m.path(MouseReports.splitMove(counts, counts, HidMouse.CALIB_STEP))
        waitingTap = true
    }

    private fun landscape(): Boolean { val (w, h) = videoSize(); return w > h && w > 0 }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP || !waitingTap) return true
        val loc = IntArray(2).also { video.getLocationOnScreen(it) }
        val me = IntArray(2).also { getLocationOnScreen(it) }
        val fx = (e.x + me[0] - loc[0]) / video.width.coerceAtLeast(1)
        val fy = (e.y + me[1] - loc[1]) / video.height.coerceAtLeast(1)
        val nx = calibrateFull(counts, fx)
        val ny = calibrateFull(counts, fy)
        if (nx == null || ny == null) {
            if (fx > 0.85f || fy > 0.85f) {
                counts = (counts / 2).coerceAtLeast(40)
                msg.text = "화면 끝에 너무 가까워요. 더 짧게 다시 해 볼게요."
                postDelayed({ start() }, 800)
            } else msg.text = "커서 끝을 정확히 눌러 주세요. '다시'를 누르면 처음부터 해요."
            return true
        }
        waitingTap = false
        // Stored in portrait terms; a landscape picture has the axes swapped.
        if (landscape()) { MousePrefs.setFullX(context, ny); MousePrefs.setFullY(context, nx) }
        else { MousePrefs.setFullX(context, nx); MousePrefs.setFullY(context, ny) }
        val (fullX, fullY) = if (landscape()) MousePrefs.fullY(context) to MousePrefs.fullX(context)
            else MousePrefs.fullX(context) to MousePrefs.fullY(context)
        mouse()?.goToFraction(0.5f, 0.5f, fullX, fullY, forceHome = true)
        msg.text = "맞췄어요 (가로 ${MousePrefs.fullX(context)} · 세로 ${MousePrefs.fullY(context)}). " +
            "커서가 화면 한가운데로 가면 '완료', 아니면 '다시'."
        return true
    }

    private fun finish() {
        waitingTap = false
        onDone()
    }
}
