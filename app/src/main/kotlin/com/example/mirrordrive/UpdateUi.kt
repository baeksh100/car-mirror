package com.example.mirrordrive

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * Dialogs for the in-app updater. [isMirroring] stops the automatic check from popping a dialog
 * over the iPhone screen while driving; it then waits for the next app start.
 */
class UpdateUi(private val activity: Activity, private val isMirroring: () -> Boolean) {
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    /** Quiet check on app start, at most every few hours. Says nothing unless there is an update. */
    fun autoCheck() {
        val now = System.currentTimeMillis()
        if (busy || !shouldAutoCheck(now, Updater.lastCheck(activity))) return
        check(manual = false)
    }

    /** "업데이트 확인" button: always checks and always reports the result. */
    fun manualCheck() = check(manual = true)

    private fun check(manual: Boolean) {
        if (busy) return
        busy = true
        if (manual) toast("새 버전 확인 중…")
        Thread {
            val result = runCatching { Updater.fetchLatest() }
            Updater.markChecked(activity, System.currentTimeMillis())
            ui.post {
                busy = false
                if (activity.isFinishing || activity.isDestroyed) return@post
                result.onFailure {
                    Updater.logFailure(it)
                    if (manual) toast((it as? UpdateError)?.message ?: "인터넷 연결을 확인해 주세요.")
                }
                result.onSuccess { remote ->
                    when {
                        isNewer(remote, Updater.localVersionCode) -> {
                            remote!!
                            if (manual || (!isMirroring() && Updater.skippedCode(activity) != remote.versionCode)) {
                                offer(remote)
                            }
                        }
                        manual -> toast("최신 버전이에요 (${Updater.localVersionName})")
                    }
                }
            }
        }.start()
    }

    private fun offer(remote: RemoteBuild) {
        AlertDialog.Builder(activity)
            .setTitle("새 버전이 있어요")
            .setMessage("지금: ${Updater.localVersionName}\n새 버전: ${remote.versionName}\n\n받아서 설치할까요? (Wi-Fi에서 1분 정도 걸려요)")
            .setPositiveButton("설치") { _, _ -> startInstall(remote) }
            .setNegativeButton("나중에", null)
            .setNeutralButton("이 버전 건너뛰기") { _, _ -> Updater.skip(activity, remote.versionCode) }
            .show()
    }

    private fun startInstall(remote: RemoteBuild) {
        if (!Updater.canInstall(activity)) {
            AlertDialog.Builder(activity)
                .setTitle("설치 허용이 필요해요")
                .setMessage("다음 화면에서 'CarMirror'의 '이 출처 허용'을 켠 뒤, 뒤로 돌아와 다시 '업데이트 확인'을 눌러 주세요. 처음 한 번만 하면 돼요.")
                .setPositiveButton("설정 열기") { _, _ -> Updater.openInstallPermissionSettings(activity) }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val label = TextView(activity).apply { text = "다운로드 중…" }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = true
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(label); addView(bar)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("업데이트 받는 중")
            .setView(box)
            .setCancelable(false)
            .show()
        busy = true
        Thread {
            val result = runCatching {
                val apk = Updater.download(activity, remote.apkUrl) { pct ->
                    ui.post {
                        if (pct >= 0) { bar.isIndeterminate = false; bar.progress = pct; label.text = "다운로드 중… $pct%" }
                    }
                }
                ui.post { label.text = "설치 준비 중…" }
                Updater.install(activity, apk)
            }
            ui.post {
                busy = false
                runCatching { dialog.dismiss() }
                result.onFailure {
                    Updater.logFailure(it)
                    toast((it as? UpdateError)?.message ?: "업데이트 실패: ${it.message}")
                }
            }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
