package com.example.mirrordrive

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure helpers for the experimental "car screen as a Bluetooth mouse" feature (no android.*).
 *
 * The box pretends to be a plain 3-button Bluetooth mouse; the iPhone takes it through
 * AssistiveTouch. A mouse only reports *relative* movement, so absolute positioning is done by
 * first slamming the pointer into a corner ([homeMoves]) and then moving a known distance.
 */
object MouseReports {
    const val REPORT_ID = 1

    const val BUTTON_LEFT = 1
    const val BUTTON_RIGHT = 2
    const val BUTTON_MIDDLE = 4

    /** HID report descriptor: report id 1 = buttons(3 bits + 5 pad), X, Y, wheel (signed 8-bit). */
    val DESCRIPTOR: ByteArray = intArrayOf(
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x02,       // Usage (Mouse)
        0xA1, 0x01,       // Collection (Application)
        0x85, REPORT_ID,  //   Report ID
        0x09, 0x01,       //   Usage (Pointer)
        0xA1, 0x00,       //   Collection (Physical)
        0x05, 0x09,       //     Usage Page (Buttons)
        0x19, 0x01,       //     Usage Minimum (1)
        0x29, 0x03,       //     Usage Maximum (3)
        0x15, 0x00,       //     Logical Minimum (0)
        0x25, 0x01,       //     Logical Maximum (1)
        0x95, 0x03,       //     Report Count (3)
        0x75, 0x01,       //     Report Size (1)
        0x81, 0x02,       //     Input (Data, Var, Abs)
        0x95, 0x01,       //     Report Count (1)
        0x75, 0x05,       //     Report Size (5)
        0x81, 0x03,       //     Input (Const) — padding
        0x05, 0x01,       //     Usage Page (Generic Desktop)
        0x09, 0x30,       //     Usage (X)
        0x09, 0x31,       //     Usage (Y)
        0x09, 0x38,       //     Usage (Wheel)
        0x15, 0x81,       //     Logical Minimum (-127)
        0x25, 0x7F,       //     Logical Maximum (127)
        0x75, 0x08,       //     Report Size (8)
        0x95, 0x03,       //     Report Count (3)
        0x81, 0x06,       //     Input (Data, Var, Rel)
        0xC0,             //   End Collection
        0xC0,             // End Collection
    ).map { it.toByte() }.toByteArray()

    /** One input report body (without the report id byte, which sendReport adds). */
    fun report(buttons: Int, dx: Int, dy: Int, wheel: Int = 0): ByteArray = byteArrayOf(
        (buttons and 0x07).toByte(),
        clamp(dx).toByte(),
        clamp(dy).toByte(),
        clamp(wheel).toByte(),
    )

    fun clamp(v: Int): Int = max(-127, min(127, v))

    /**
     * Split a movement into steps no larger than [maxStep] on either axis, moving both axes
     * together so the path stays straight. Small steps also keep iOS pointer acceleration low,
     * which makes the distance travelled more predictable.
     */
    fun splitMove(dx: Int, dy: Int, maxStep: Int = 127): List<Pair<Int, Int>> {
        val step = max(1, min(127, maxStep))
        val n = max((abs(dx) + step - 1) / step, (abs(dy) + step - 1) / step)
        if (n == 0) return emptyList()
        val out = ArrayList<Pair<Int, Int>>(n)
        var sx = 0
        var sy = 0
        for (i in 1..n) {
            val tx = dx * i / n
            val ty = dy * i / n
            out += (tx - sx) to (ty - sy)
            sx = tx; sy = ty
        }
        return out
    }

    /** Enough big moves to pin the pointer into the top-left corner from anywhere on screen. */
    fun homeMoves(count: Int = 40): List<Pair<Int, Int>> = List(count) { -127 to -127 }
}

/**
 * Turns finger movement (pixels, fractional) into whole mouse counts, carrying the remainder so
 * slow drags still move the pointer instead of rounding to zero.
 */
class MotionAccumulator(var sensitivity: Float = 1f) {
    private var rx = 0f
    private var ry = 0f

    fun add(dxPx: Float, dyPx: Float): Pair<Int, Int> {
        rx += dxPx * sensitivity
        ry += dyPx * sensitivity
        val ix = rx.toInt()
        val iy = ry.toInt()
        rx -= ix; ry -= iy
        return ix to iy
    }

    fun reset() { rx = 0f; ry = 0f }
}
