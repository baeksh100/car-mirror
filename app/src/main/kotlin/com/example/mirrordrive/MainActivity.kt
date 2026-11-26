package com.example.mirrordrive

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {
    private lateinit var videoRenderer: VideoRenderer
    private lateinit var audioRenderer: AudioRenderer
    private lateinit var overlayController: OverlayController
    private lateinit var root: FrameLayout
    private lateinit var aspectView: AspectRatioFrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var waitingOverlay: WaitingOverlayView

    // Real decoded video size (portrait for a portrait iPhone), reported by the renderer.
    private var videoW = 0
    private var videoH = 0
    // True while the mirror is showing in the floating overlay window rather than fullscreen.
    private var overlayActive = false

    // uptimeMillis of the most recent rendered frame (written on the codec thread via the
    // renderer's onFrameRendered hook — a cheap volatile write that never touches decoding).
    // A UI-thread watchdog reads it to fade the waiting overlay in/out.
    @Volatile private var lastFrameMs = 0L
    private val ui = Handler(Looper.getMainLooper())
    // In-app updater; the automatic check never interrupts a live mirror.
    private val updateUi = UpdateUi(this) { SystemClock.uptimeMillis() - lastFrameMs < 5_000 }
    private val waitingTick = object : Runnable {
        override fun run() { updateWaiting(); ui.postDelayed(this, 700) }
    }

    // Grant flow for SYSTEM_ALERT_WINDOW: after the settings screen returns, retry if granted.
    private val overlayPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) enterOverlay()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen on while mirroring (user request).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Night-ink system bars for the transient-swipe case (bars are otherwise hidden).
        window.statusBarColor = Palette.GROUND
        window.navigationBarColor = Palette.GROUND

        // Black container so the letterbox/pillarbox bars are black; the aspect view sizes
        // itself to the fitted video rect (in its onMeasure) and is centred within root, and
        // the SurfaceView fills the aspect view (Feature 1).
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        aspectView = AspectRatioFrameLayout(this)
        surfaceView = SurfaceView(this)
        aspectView.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            aspectView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        // Waiting state: a night-ink overlay with a radiating signal, shown until the first
        // video frame renders. It sits ABOVE the video root but BELOW the 창 모드 pill so the
        // control stays tappable, and owns no surface/video state.
        waitingOverlay = WaitingOverlayView(this)
        waitingOverlay.onSettings = { showSettings() }
        root.addView(
            waitingOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // Night auto-dim: a black layer over the video and waiting screen (below the buttons).
        // The box's own backlight setting may not reach the car screen (it is a video feed into
        // the head unit), so dimming the pixels themselves is the reliable way.
        dimView = android.view.View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            isClickable = false
            isFocusable = false
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(dimView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // "창 모드" (window mode) toggle -> floating overlay window (Feature 2).
        root.addView(
            buildModeButton(this) { requestOverlay() },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = dp(16)
                marginEnd = dp(16)
            },
        )
        // ✕ quit control, sat just to the left of 창 모드 in the top-end area. Same restrained
        // night-dashboard chip; tapping it stops mirroring and closes the app entirely.
        root.addView(
            buildQuitButton(this) { closeApp() },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = dp(16)
                // Clear the 창 모드 button (48dp touch target) plus a small gap.
                marginEnd = dp(16) + dp(48) + dp(4)
            },
        )
        // ⚙ settings, left of ✕: the waiting-screen long-press is hidden while mirroring.
        root.addView(
            buildSettingsButton(this) { showSettings() },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = dp(16)
                marginEnd = dp(16) + 2 * (dp(48) + dp(4))
            },
        )
        setContentView(root)

        overlayController = OverlayController(this)
        videoRenderer = VideoRenderer(
            // Fired after each frame is released to the Surface; just stamp the time so the
            // waiting-overlay watchdog knows frames are flowing. Runs on the codec thread.
            onFrameRendered = { lastFrameMs = SystemClock.uptimeMillis() },
            onVideoSize = { w, h ->
                runOnUiThread {
                    videoW = w; videoH = h
                    aspectView.setAspect(w, h)
                    if (overlayActive) overlayController.updateAspect(w, h)
                }
            },
        )
        // Native gate: if libmirrordrive failed to load (e.g. an x86 cast box with an arm-only
        // .so, or a locked ROM missing a system lib), do NOT touch any native/service wiring —
        // that would throw and insta-crash. Instead show a screenshot-able report naming the
        // device's ABIs and let onCreate complete. The waiting UI stays up behind it.
        if (!NativeBridge.available) {
            showNativeUnavailable()
            return
        }

        // Register the renderer as the native video sink before any stream arrives.
        NativeBridge.setVideoSink(videoRenderer)

        // Audio is independent of the surface/overlay and purely additive: create the
        // renderer once, then decode + play AAC-ELD frames as they arrive. The ASC for
        // 44100/stereo AirPlay-mirroring AAC-ELD is the fixed constant F8 E8 50 00.
        audioRenderer = AudioRenderer()
        audioRenderer.configure(byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00))
        NativeBridge.setAudioSink(audioRenderer)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // First launch: records the Surface (codec not yet configured). Return from window
                // mode: the running codec swaps onto this fullscreen Surface and THEN the overlay is
                // dismissed. No reconfigure either way.
                enterFullscreenWith(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // The fullscreen Surface is destroyed transiently on a mode switch (into overlay
                // mode, and on some devices during the return transition) while the decoder keeps
                // running into the other Surface. Releasing on any such transient loss would
                // permanently kill mirroring — so tear the decoder down only on a genuine app exit
                // (isFinishing). Re-attaching to a fresh fullscreen Surface is handled by
                // surfaceCreated / onResume.
                if (isFinishing) videoRenderer.release()
            }
        })

        // A customized ROM can reject the notification-permission prompt or the foreground-service
        // start outright; neither must be allowed to crash onCreate. Wrap both so the UI survives
        // and the failure is logged rather than fatal.
        runCatching {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }.onFailure { Log.e(TAG, "notification permission request failed", it) }
        runCatching {
            startForegroundService(android.content.Intent(this, ReceiverService::class.java))
        }.onFailure { Log.e(TAG, "startForegroundService failed", it) }
    }

    /**
     * Native library unavailable: pop the bulletproof [ErrorActivity] with a clear, screenshot-able
     * message naming the device's CPU ABIs (the usual culprit — an arm-only build on an x86 box).
     * Never touches native code. Falls back silently if even launching the activity fails.
     */
    private fun showNativeUnavailable() {
        val msg = buildString {
            append("이 기기의 CPU(ABI)는 CarMirror를 지원하지 않아요.\n\n")
            append("device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("sdk: ${Build.VERSION.SDK_INT}\n")
            append("abis: ${Build.SUPPORTED_ABIS.joinToString(",")}\n\n")
            append("CarMirror 네이티브 라이브러리를 이 기기에서 불러오지 못했어요. ")
            append("이 화면을 사진으로 찍어 두세요.")
        }
        Log.e(TAG, "native unavailable: $msg")
        runCatching {
            startActivity(
                Intent(this, ErrorActivity::class.java)
                    .putExtra(ErrorActivity.EXTRA_MESSAGE, msg),
            )
        }.onFailure { Log.e(TAG, "could not show ErrorActivity", it) }
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            enterOverlay()
        } else {
            overlayPermLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    /** Move the mirror into the floating overlay and send the app to the background. */
    private fun enterOverlay() {
        if (overlayActive) return
        overlayController.show(
            videoW = videoW,
            videoH = videoH,
            onSurface = { s -> videoRenderer.setSurface(s) },
            onReturnToFullscreen = {
                // ⤢ — return to fullscreen. Do NOT dismiss the overlay here: the decoder is still
                // rendering into the overlay Surface, and destroying it first makes the codec render
                // into a dead Surface — which errors it irrecoverably, so no later setOutputSurface
                // can revive it and fullscreen stays black. Instead just bring this activity to the
                // front; enterFullscreenWith() (from surfaceCreated when the fullscreen Surface was
                // recreated, or from onResume when it survived) swaps the codec onto the fullscreen
                // Surface FIRST and only THEN hides the overlay — the mirror image of how entering
                // window mode works.
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            },
            onClose = { closeApp() },   // ✕ — quit the app entirely.
        )
        overlayActive = true
        moveTaskToBack(true)
    }

    /**
     * Full, clean shutdown (the ✕ quit action, from both the overlay and fullscreen): stop
     * mirroring and leave the app. Dismiss any floating overlay, stop the receiver service — its
     * onDestroy tears down mDNS discovery and the native AirPlay session — and drop the task from
     * recents. The video/audio renderers are released by the normal activity lifecycle
     * (surfaceDestroyed / onDestroy while isFinishing), which finishAndRemoveTask triggers.
     */
    private fun closeApp() {
        overlayActive = false
        overlayController.hide()
        stopService(Intent(this, ReceiverService::class.java))
        finishAndRemoveTask()
    }

    /**
     * Attach the decoder to the (valid) fullscreen [surface] and, if the floating overlay is up,
     * dismiss it — always swapping the codec onto the fullscreen Surface BEFORE the overlay Surface
     * is destroyed, so the codec never renders into a dead Surface (which errors it irrecoverably →
     * black fullscreen). Invariant: a foregrounded MainActivity means fullscreen mode, no overlay.
     * Idempotent: safe to call on every surfaceCreated / onResume.
     */
    private fun enterFullscreenWith(surface: android.view.Surface) {
        if (!::videoRenderer.isInitialized) return
        videoRenderer.setSurface(surface)
        if (overlayActive) {
            overlayController.hide()
            overlayActive = false
        }
    }

    override fun onResume() {
        super.onResume()
        // Complete a return from window mode when the fullscreen Surface SURVIVED backgrounding, so
        // surfaceCreated won't re-fire. Using the still-valid Surface, enterFullscreenWith swaps the
        // codec onto fullscreen and then dismisses the overlay. If the Surface was instead destroyed
        // it isn't valid yet here — surfaceCreated will do it on recreation.
        if (::surfaceView.isInitialized) {
            surfaceView.holder.surface?.takeIf { it.isValid }?.let { enterFullscreenWith(it) }
        }
        ui.removeCallbacks(waitingTick)
        ui.post(waitingTick)
        // Give the receiver a few seconds to start (and a reconnecting iPhone to resume) first.
        ui.postDelayed({ if (!isFinishing) updateUi.autoCheck() }, 8_000)
        runCatching {
            sensorManager()?.let { sm ->
                sm.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT)?.let {
                    sm.registerListener(lightListener, it, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
        }
    }

    override fun onPause() {
        ui.removeCallbacks(waitingTick)
        runCatching { sensorManager()?.unregisterListener(lightListener) }
        super.onPause()
    }

    /**
     * UI-thread watchdog (every ~700ms): the waiting overlay is visible whenever no frames are
     * currently arriving and the mirror is fullscreen. Frames flowing -> fade it out; frames
     * stalled (stream ended) -> fade it back in; floating-overlay mode -> keep it hidden. Reads
     * only [lastFrameMs]; it never influences how frames reach the decoder.
     */
    private fun updateWaiting() {
        val live = lastFrameMs != 0L && SystemClock.uptimeMillis() - lastFrameMs < 1500
        when {
            overlayActive -> waitingOverlay.hideImmediate()
            live -> waitingOverlay.fadeOut()
            else -> waitingOverlay.fadeIn()
        }
        waitingOverlay.refreshDeviceInfoIfNeeded()
        runCatching {
            val snap = Session.snapshot()
            val ifs = interfaceDump()
            waitingOverlay.setGuidance(
                guidanceFor(DiscoveryService.status, ifs,
                    CarSettings.receiverName(this), NativeBridge.available, snap),
            )
            waitingOverlay.setChecklist(checklistFor(DiscoveryService.status, ifs, snap))
        }
        updateNightDim()
        // On-screen diagnostic (test builds): if fullscreen ever gets stuck on the waiting screen,
        // this line names why — decoder configured/released/error, whether the fullscreen Surface is
        // valid, the last setSurface outcome, and how long since the last rendered frame.
        runCatching {
            val age = if (lastFrameMs == 0L) "-" else "${SystemClock.uptimeMillis() - lastFrameMs}ms"
            val fsSurf = if (::surfaceView.isInitialized && surfaceView.holder.surface?.isValid == true) 1 else 0
            val codec = if (::videoRenderer.isInitialized) videoRenderer.debugLine() else "-"
            waitingOverlay.setDebug("car7 net=${DiscoveryService.status} err=${DiscoveryService.lastError} audio=${AudioState.mode} rec=${Session.decoderRecoveries}\n${sourceDump()}\nifs=${interfaceDump()}\nov=${if (overlayActive) 1 else 0} fsSurf=$fsSurf f=$age $codec")
        }
    }

    // ---- Night auto-dim ----------------------------------------------------------------------
    private lateinit var dimView: android.view.View
    private var night = false
    @Volatile private var lastLux: Float? = null
    private val lightListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) { lastLux = e.values.firstOrNull() }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }

    private fun sensorManager() = getSystemService(SENSOR_SERVICE) as? android.hardware.SensorManager

    private fun updateNightDim() {
        if (!::dimView.isInitialized) return
        val now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
        night = CarSettings.nightDim(this) &&
            isNight(now.monthValue, now.hour * 60 + now.minute, lastLux, night)
        val target = if (night) NIGHT_DIM_ALPHA else 0f
        if (dimView.alpha != target) dimView.animate().alpha(target).setDuration(1500).start()
    }

    /** Receiver name + auto-start, opened by long-pressing the CARMIRROR wordmark. */
    private fun showSettings() {
        val pad = dp(20)
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }
        val nameEdit = android.widget.EditText(this).apply {
            setText(CarSettings.receiverName(this@MainActivity))
            hint = CarSettings.DEFAULT_NAME
            setSingleLine()
            selectAll()
        }
        val auto = android.widget.CheckBox(this).apply {
            text = "차 시동이 켜지면 자동으로 실행"
            isChecked = CarSettings.autoStart(this@MainActivity)
        }
        box.addView(android.widget.TextView(this).apply { text = "아이폰 화면 미러링 목록에 보일 이름" })
        box.addView(nameEdit)
        box.addView(auto)
        val dim = android.widget.CheckBox(this).apply {
            text = "밤에 화면 자동으로 어둡게"
            isChecked = CarSettings.nightDim(this@MainActivity)
        }
        box.addView(dim)
        box.addView(android.widget.TextView(this).apply {
            text = "버전 ${Updater.localVersionName}"
            setPadding(0, dp(12), 0, 0)
        })
        box.addView(android.widget.Button(this).apply {
            text = "업데이트 확인"
            setOnClickListener { updateUi.manualCheck() }
        })
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("CarMirror 설정")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val old = CarSettings.receiverName(this)
                CarSettings.setReceiverName(this, nameEdit.text.toString())
                CarSettings.setAutoStart(this, auto.isChecked)
                CarSettings.setNightDim(this, dim.isChecked)
                updateNightDim()
                if (CarSettings.receiverName(this) != old) {
                    runCatching {
                        startService(Intent(this, ReceiverService::class.java).setAction(ReceiverService.ACTION_RENAME))
                    }
                }
            }
            .setNegativeButton("취소", null)
            // Experimental: drive the iPhone from the car screen as a Bluetooth mouse.
            .setNeutralButton("블루투스 마우스 시험") { _, _ ->
                runCatching { startActivity(Intent(this, MouseTestActivity::class.java)) }
            }
            .show()
    }

    override fun onDestroy() {
        ui.removeCallbacks(waitingTick)
        if (isFinishing) {
            overlayController.hide()
            // Genuine exit: release the decoder here too, not only via surfaceDestroyed — with the
            // isFinishing-gated teardown in surfaceDestroyed, a finish that arrives without a
            // surfaceDestroyed callback would otherwise leak the codec. release() is idempotent.
            if (::videoRenderer.isInitialized) videoRenderer.release()
            // Audio has no surface; tear it down only when genuinely leaving mirroring
            // (never on the overlay/window-mode transition, which keeps the activity alive).
            if (::audioRenderer.isInitialized) audioRenderer.stop()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
