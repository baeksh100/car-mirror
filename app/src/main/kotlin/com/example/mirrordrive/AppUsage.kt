package com.example.mirrordrive

import android.content.Context
import kotlin.math.pow

/**
 * Order for the remote's app list: pinned apps first (in the order they were pinned), then the
 * apps used most on the cast lately, then by name. The car launcher's own icon layout lives in
 * another app's private data, so it cannot be read; this learns from use instead.
 *
 * Use is counted when an app comes to the front on the cast (the accessibility service sees
 * every app switch) and when it is opened from the remote. Old use fades with a 14-day half-life.
 */
object AppUsage {
    private const val PREFS = "carmirror_app_usage"
    private const val KEY_PINS = "pins"
    private const val HALF_LIFE_MS = 14L * 24 * 3600 * 1000

    @Volatile private var lastPkg: String? = null

    /** Called for each app switch; repeated events for the same app count once. */
    fun record(ctx: Context, pkg: String, now: Long = System.currentTimeMillis()) {
        if (pkg == lastPkg) return
        lastPkg = pkg
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val (s, t) = parse(p.getString(pkg, null))
        p.edit().putString(pkg, "${decayed(s, t, now) + 1.0}|$now").apply()
    }

    fun score(ctx: Context, pkg: String, now: Long = System.currentTimeMillis()): Double {
        val (s, t) = parse(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(pkg, null))
        return decayed(s, t, now)
    }

    fun pins(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PINS, "")!!
            .split(',').filter { it.isNotEmpty() }

    fun setPinned(ctx: Context, pkg: String, on: Boolean) {
        val now = pins(ctx).filter { it != pkg } + if (on) listOf(pkg) else emptyList()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PINS, now.joinToString(",")).apply()
    }

    private fun parse(v: String?): Pair<Double, Long> {
        val parts = v?.split('|') ?: return 0.0 to 0L
        return (parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    /** [score] last updated at [at], faded to [now]. */
    fun decayed(score: Double, at: Long, now: Long): Double {
        if (score <= 0.0) return 0.0
        val age = (now - at).coerceAtLeast(0L)
        return score * 0.5.pow(age.toDouble() / HALF_LIFE_MS)
    }
}

/** Sorts (package, name) pairs: pinned in pin order, then higher [score], then name. */
fun orderApps(apps: List<Pair<String, String>>, pins: List<String>, score: (String) -> Double): List<Pair<String, String>> {
    val pinIndex = pins.withIndex().associate { (i, p) -> p to i }
    return apps.sortedWith(
        compareBy<Pair<String, String>>({ pinIndex[it.first] ?: Int.MAX_VALUE })
            .thenByDescending { score(it.first) }
            .thenBy { it.second.lowercase() }
    )
}
