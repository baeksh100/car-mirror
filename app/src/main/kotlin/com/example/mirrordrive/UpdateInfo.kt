package com.example.mirrordrive

/**
 * Pure (android-free) pieces of the in-app updater, so they can be unit tested.
 *
 * The CI workflow writes a line `versionCode=<n>` into the body of the main-branch release; the
 * app compares that number with its own versionCode.
 */
data class RemoteBuild(val versionCode: Int, val versionName: String, val apkUrl: String)

private val VERSION_CODE_RE = Regex("""versionCode\s*=\s*(\d+)""")
private val VERSION_NAME_RE = Regex("""versionName\s*=\s*(\S+)""")

fun parseVersionCode(body: String?): Int? =
    body?.let { VERSION_CODE_RE.find(it)?.groupValues?.get(1)?.toIntOrNull() }

fun parseVersionName(body: String?): String? =
    body?.let { VERSION_NAME_RE.find(it)?.groupValues?.get(1) }

fun isNewer(remote: RemoteBuild?, localVersionCode: Int): Boolean =
    remote != null && remote.versionCode > localVersionCode

/** Auto-check at most this often (the dialog also has a manual "지금 확인" button). */
const val AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

fun shouldAutoCheck(nowMs: Long, lastCheckMs: Long): Boolean =
    lastCheckMs <= 0 || nowMs - lastCheckMs >= AUTO_CHECK_INTERVAL_MS || nowMs < lastCheckMs
