package com.example.mirrordrive

import android.content.Context

/** Small persisted user settings for CarMirror. */
object CarSettings {
    private const val PREFS = "carmirror_settings"
    private const val KEY_NAME = "receiver_name"
    private const val KEY_AUTOSTART = "auto_start"
    const val DEFAULT_NAME = "CarMirror"

    fun receiverName(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, null)
            ?.let { sanitizeName(it) } ?: DEFAULT_NAME

    fun setReceiverName(ctx: Context, name: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, sanitizeName(name) ?: DEFAULT_NAME).apply()
    }

    fun autoStart(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTOSTART, true)

    fun setAutoStart(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTOSTART, on).apply()
    }

    fun nightDim(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NIGHT_DIM, true)

    fun setNightDim(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NIGHT_DIM, on).apply()
    }

    private const val KEY_NIGHT_DIM = "night_dim"
    private const val KEY_REMOTE = "remote_key"
    private const val KEY_REMOTE_HOME = "remote_home_pkg"

    /** App the remote's Home button opens (the car's own launcher); null = Android home. */
    fun remoteHome(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REMOTE_HOME, null)

    fun setRemoteHome(ctx: Context, pkg: String?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_REMOTE_HOME, pkg).apply()
    }

    /** Secret part of the iPhone remote's address; made once and kept. */
    fun remoteKey(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY_REMOTE, null)?.let { return it }
        return newRemoteKey().also { p.edit().putString(KEY_REMOTE, it).apply() }
    }
}

/**
 * A DNS-SD instance name is one DNS label: at most 63 UTF-8 bytes. Dots are legal but confuse some
 * resolvers, and '@' is the RAOP "deviceid@name" separator, so both are replaced. Returns null for
 * a blank result so callers fall back to the default.
 */
fun sanitizeName(raw: String): String? {
    var s = raw.trim().replace('.', ' ').replace('@', ' ').replace(Regex("\\s+"), " ")
    while (s.toByteArray(Charsets.UTF_8).size > 63) s = s.dropLast(1)
    s = s.trim()
    return s.ifEmpty { null }
}
