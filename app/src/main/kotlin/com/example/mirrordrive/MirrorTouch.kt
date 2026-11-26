package com.example.mirrordrive

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Experimental: touching the mirrored iPhone picture drives the iPhone through the Bluetooth
 * mouse (AssistiveTouch). The touched spot, as a fraction of the picture, becomes the same
 * fraction of the iPhone screen: the pointer is pinned to the top-left corner and then moved
 * the calibrated distance. Tap = tap, hold = long press, drag = press-and-drag (swipe/scroll).
 *
 * Only active when 설정 → 아이폰 터치 조작 → "미러링 화면을 눌러서 아이폰 조작하기" is on
 * and an iPhone is connected; otherwise touches pass through untouched.
 */
class MirrorTouch(
    private val ctx: Context,
    /** Current video frame size; landscape frames swap the portrait calibration. */
    private val videoSize: () -> Pair<Int, Int>,
) : View.OnTouchListener {
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var dragging = false
    private var tracking = false
    private val accX = MotionAccumulator()

    private fun mouse(): HidMouse? =
        MouseHub.peek()?.takeIf { MousePrefs.mirrorTouch(ctx) && it.connected != null }

    /** Calibrated full-screen counts for the iPhone's current orientation. */
    private fun full(): Pair<Int, Int> {
        val fx = MousePrefs.fullX(ctx)
        val fy = MousePrefs.fullY(ctx)
        val (w, h) = videoSize()
        return if (w > h && w > 0) fy to fx else fx to fy
    }

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) tracking = mouse() != null
        if (!tracking) return false
        val m = mouse() ?: run { tracking = false; return false }
        val w = v.width.coerceAtLeast(1)
        val h = v.height.coerceAtLeast(1)
        val (fullX, fullY) = full()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                downAt = SystemClock.uptimeMillis(); dragging = false
                accX.reset()
                // No clearQueue: a still-running move must finish so the tracked position holds.
                m.goToFraction((e.x / w).coerceIn(0f, 1f), (e.y / h).coerceIn(0f, 1f), fullX, fullY)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.x - downX, e.y - downY) > ctx.dpf(10)) {
                    dragging = true
                    m.press()
                }
                if (dragging) {
                    // Pixels on the car screen → mouse counts on the iPhone (same picture scale).
                    accX.sensitivity = 1f
                    val (dx, dy) = accX.add((e.x - lastX) * fullX / w, (e.y - lastY) * fullY / h)
                    lastX = e.x; lastY = e.y
                    m.move(dx, dy, MouseReports.BUTTON_LEFT, tracked = true)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    m.release()
                    m.draggedTo((e.x / w).coerceIn(0f, 1f), (e.y / h).coerceIn(0f, 1f), fullX, fullY)
                } else m.click(holdMs = (SystemClock.uptimeMillis() - downAt).coerceIn(40, 1500))
                tracking = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) m.release()
                tracking = false
            }
        }
        return true
    }
}
