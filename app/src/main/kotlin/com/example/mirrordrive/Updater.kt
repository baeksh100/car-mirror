package com.example.mirrordrive

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater: looks at the GitHub Release that CI publishes for the main branch, downloads
 * its APK and hands it to the system installer (PackageInstaller). The user still taps "설치"
 * once on Android's own confirmation screen; that cannot be skipped for a sideloaded app.
 */
object Updater {
    private const val TAG = "Updater"
    private const val APK_NAME = "CarMirror-debug.apk"
    private const val PREFS = "carmirror_update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_SKIPPED = "skipped_code"
    const val ACTION_INSTALL_RESULT = "app.carmirror.INSTALL_RESULT"

    val localVersionCode: Int get() = BuildConfig.VERSION_CODE
    val localVersionName: String get() = BuildConfig.VERSION_NAME

    /** Network call: run off the main thread. Returns null when there is no usable release. */
    fun fetchLatest(): RemoteBuild? {
        val api = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/tags/${BuildConfig.UPDATE_TAG}"
        val conn = (URL(api).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CarMirror-updater")
        }
        try {
            val code = conn.responseCode
            if (code == 404) throw UpdateError("업데이트 서버를 찾지 못했어요. 저장소가 공개인지 확인해 주세요.")
            if (code != 200) throw UpdateError("업데이트 확인 실패 (HTTP $code)")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val body = json.optString("body")
            val vc = parseVersionCode(body) ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == APK_NAME) {
                    return RemoteBuild(vc, parseVersionName(body) ?: "#$vc", a.getString("browser_download_url"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Network call: run off the main thread. [progress] gets 0..100, or -1 if size unknown. */
    fun download(ctx: Context, url: String, progress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, APK_NAME)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CarMirror-updater")
        }
        try {
            if (conn.responseCode != 200) throw UpdateError("다운로드 실패 (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -2
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) { lastPct = pct; progress(pct) }
                    }
                }
            }
            if (total > 0 && done != total) throw UpdateError("다운로드가 중간에 끊겼어요.")
        } finally {
            conn.disconnect()
        }
        return out
    }

    /** True if Android lets this app install APKs ("출처를 알 수 없는 앱" 허용). */
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(activity: Activity) {
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
        }.onFailure {
            Toast.makeText(activity, "설정 > 앱 > CarMirror에서 '출처를 알 수 없는 앱 설치'를 허용해 주세요.", Toast.LENGTH_LONG).show()
        }
    }

    /** Streams the APK into a PackageInstaller session. Result arrives at [InstallResultReceiver]. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("CarMirror.apk", 0, apk.length()).use { os ->
                apk.inputStream().use { it.copyTo(os) }
                session.fsync(os)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                ctx, id,
                Intent(ctx, InstallResultReceiver::class.java).setAction(ACTION_INSTALL_RESULT),
                flags,
            )
            session.commit(pi.intentSender)
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun lastCheck(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_CHECK, 0)
    fun markChecked(ctx: Context, now: Long) = prefs(ctx).edit().putLong(KEY_LAST_CHECK, now).apply()
    fun skippedCode(ctx: Context): Int = prefs(ctx).getInt(KEY_SKIPPED, 0)
    fun skip(ctx: Context, code: Int) = prefs(ctx).edit().putInt(KEY_SKIPPED, code).apply()

    fun logFailure(t: Throwable) = Log.w(TAG, "update failed", t)
}

class UpdateError(msg: String) : Exception(msg)

/** Receives PackageInstaller status: shows Android's confirm screen, or a readable failure. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app restarts as the new version
            else -> {
                val raw = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                val msg = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT,
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "설치 실패: 지금 앱과 서명이 달라요. 이번 한 번만 CarMirror를 지우고 새로 설치해 주세요."
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "설치를 취소했어요."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "설치 실패: 저장 공간이 부족해요."
                    else -> "설치 실패 ($status) $raw"
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
