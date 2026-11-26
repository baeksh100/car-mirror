package com.example.mirrordrive

import kotlin.math.roundToInt

/**
 * The display size to advertise to the iPhone: landscape, scaled down (aspect preserved) to fit
 * 1920x1080 — the largest the iPhone's mirroring encoder targets — and rounded to even numbers for
 * the H.264 encoder. Car screens are often very wide (e.g. 1920x720); advertising the real shape
 * lets the iPhone fill it instead of encoding a 16:9 frame that is then letterboxed.
 * Returns null for nonsense input so the engine keeps its 1920x1080 default.
 */
fun advertisedSize(w: Int, h: Int): Pair<Int, Int>? {
    if (w <= 0 || h <= 0) return null
    val lw = maxOf(w, h).toDouble()
    val lh = minOf(w, h).toDouble()
    val scale = minOf(1.0, MAX_W / lw, MAX_H / lh)
    val ow = (lw * scale / 2).roundToInt() * 2
    val oh = (lh * scale / 2).roundToInt() * 2
    if (ow < 320 || oh < 240) return null
    return ow to oh
}

private const val MAX_W = 1920.0
private const val MAX_H = 1080.0

/**
 * Night detection for the auto-dim layer. Uses the ambient light sensor when the box has one
 * (with hysteresis so headlights/streetlights don't make it flicker), otherwise approximate Seoul
 * sunrise/sunset by month, shifted ~20 min for civil twilight.
 *
 * @param month        1..12 (Asia/Seoul)
 * @param minuteOfDay  0..1439 (Asia/Seoul)
 * @param lux          ambient light, or null when there is no sensor
 * @param wasNight     previous decision, for sensor hysteresis
 */
fun isNight(month: Int, minuteOfDay: Int, lux: Float?, wasNight: Boolean): Boolean {
    if (lux != null) {
        return when {
            lux < LUX_NIGHT -> true
            lux > LUX_DAY -> false
            else -> wasNight
        }
    }
    val (rise, set) = SEOUL_SUN[(month - 1).coerceIn(0, 11)]
    return minuteOfDay >= set + TWILIGHT_MIN || minuteOfDay < rise - TWILIGHT_MIN
}

private const val LUX_NIGHT = 8f
private const val LUX_DAY = 40f
private const val TWILIGHT_MIN = 20

// Approximate Seoul sunrise / sunset (minutes after midnight, KST), mid-month.
private val SEOUL_SUN = listOf(
    7 * 60 + 45 to 17 * 60 + 40, // Jan
    7 * 60 + 25 to 18 * 60 + 10, // Feb
    6 * 60 + 50 to 18 * 60 + 40, // Mar
    6 * 60 + 5 to 19 * 60 + 5,   // Apr
    5 * 60 + 30 to 19 * 60 + 30, // May
    5 * 60 + 12 to 19 * 60 + 55, // Jun
    5 * 60 + 20 to 19 * 60 + 55, // Jul
    5 * 60 + 50 to 19 * 60 + 30, // Aug
    6 * 60 + 15 to 18 * 60 + 50, // Sep
    6 * 60 + 40 to 18 * 60 + 5,  // Oct
    7 * 60 + 10 to 17 * 60 + 30, // Nov
    7 * 60 + 35 to 17 * 60 + 15, // Dec
)

/** Black-layer opacity used when night dimming is active. */
const val NIGHT_DIM_ALPHA = 0.4f
