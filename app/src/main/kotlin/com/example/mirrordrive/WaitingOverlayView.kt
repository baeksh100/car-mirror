package com.example.mirrordrive

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The pre-mirror waiting state, shown on top of the black video root before any frames arrive
 * and again if the stream stops. Night-ink field with the pulsing CarMirror mark, the
 * CARMIRROR wordmark, a hint on how to connect from the iPhone, and — below — a live-signal dot
 * with the device id and address in monospace plus the diagnostic line.
 *
 * It owns no video/surface state: [MainActivity] fades it in/out purely from a first-frame
 * signal, so it can never affect how frames reach the decoder.
 */
class WaitingOverlayView(context: Context) : FrameLayout(context) {
    private val ripple = PulseIcon(context)
    private val deviceLine: TextView
    private val guideText: TextView
    private val checklistText: TextView
    /** Opened by long-pressing the wordmark. */
    var onSettings: (() -> Unit)? = null
    private val debugText: TextView
    private var deviceInfoComplete = false
    // Music mode: the connect column is swapped for a calm equalizer while only sound is playing.
    private val column: LinearLayout
    private val musicBox: LinearLayout
    private val visualizer = MusicVisualizerView(context)
    private val musicLabel: TextView
    private var musicMode = false

    private var fadeAnim: ValueAnimator? = null
    private var shown = true

    init {
        setBackgroundColor(Palette.GROUND)
        isClickable = false           // never steal touches from the 창 모드 pill above it
        contentDescription = "미러링 대기 중"

        // Centre stack: ripple + wordmark + connect hint.
        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        center.addView(
            ripple,
            LinearLayout.LayoutParams(context.dp(140), context.dp(140)),
        )
        center.addView(
            TextView(context).apply {
                text = "CARMIRROR"
                contentDescription = "CarMirror 설정 (길게 누르기)"
                // Long-press the wordmark for settings (receiver name, auto-start). Kept off the
                // primary tap path so a stray touch while driving never opens a dialog.
                setOnLongClickListener { onSettings?.invoke(); true }
                setTextColor(Palette.SILVER)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                letterSpacing = 0.35f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(10) },
        )
        guideText = TextView(context).apply {
                text = "준비 중이에요…"
                setTextColor(Palette.SILVER_DIM)
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                letterSpacing = 0.02f
                gravity = Gravity.CENTER
            }
        center.addView(
            guideText,
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(10) },
        )
        // Troubleshooting checklist, shown only after ~30 s without a stream (see checklistFor).
        checklistText = TextView(context).apply {
            setTextColor(Palette.SILVER)
            typeface = Typeface.SANS_SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(0f, 1.25f)
            gravity = Gravity.START
            visibility = GONE
        }
        center.addView(
            checklistText,
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(8) },
        )
        // Car-context caveat: the iPhone cannot run wireless CarPlay and screen mirroring at the
        // same time (an iOS restriction, not ours). On a CarPlay box that's the #1 reason the phone
        // never finds the receiver, so we say it here where the user is already looking + waiting.
        center.addView(
            TextView(context).apply {
                text = "무선 카플레이 사용 중엔 연결되지 않아요 · 카플레이를 끄고 연결하세요"
                setTextColor(Palette.SILVER_DIM)
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                alpha = 0.7f
                letterSpacing = 0.02f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(8) },
        )
        // car3: the centre stack and the bottom cluster used to be two independent overlays
        // (CENTER and BOTTOM gravity); on a short, wide car display they overlapped. They now
        // live in one vertical column: centre takes the remaining space, bottom wraps below it.

        // Bottom cluster (vertical, centred): device identity + diagnostics.
        val bottomStack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Live dot + device identity in monospace.
        val status = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        status.addView(
            LiveDotView(context),
            LinearLayout.LayoutParams(context.dp(18), context.dp(18)),
        )
        deviceLine = TextView(context).apply {
            setTextColor(Palette.SILVER_DIM)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.02f
            text = "device —\n—"
        }
        status.addView(
            deviceLine,
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = context.dp(8) },
        )
        bottomStack.addView(
            status,
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(4) },
        )

        // Tiny diagnostic line (test builds): decoder/surface state, so a stuck waiting screen can
        // be screenshotted and read. Invisible in normal use — if mirroring works this view is faded
        // out before it matters. Set via setDebug(); empty by default.
        debugText = TextView(context).apply {
            setTextColor(Palette.SILVER_DIM)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            alpha = 0.8f
            letterSpacing = 0.0f
            gravity = Gravity.CENTER
        }
        bottomStack.addView(
            debugText,
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(10) },
        )

        column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(10))
        }
        column.addView(center, LinearLayout.LayoutParams(WRAP, 0, 1f).apply { gravity = Gravity.CENTER_HORIZONTAL })
        (center as LinearLayout).gravity = Gravity.CENTER
        column.addView(bottomStack, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(column, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Music mode: equalizer + one quiet line. No buttons here, so there is nothing to tap
        // while driving; the settings long-press stays on the normal waiting screen.
        musicLabel = TextView(context).apply {
            setTextColor(Palette.SILVER_DIM)
            typeface = Typeface.SANS_SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            letterSpacing = 0.04f
            gravity = Gravity.CENTER
            text = "♪ 음악 재생 중"
        }
        musicBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(context.dp(48), context.dp(24), context.dp(48), context.dp(24))
            visibility = GONE
            addView(visualizer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(musicLabel, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(12) })
        }
        addView(musicBox, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /**
     * Swap between the connect/diagnostic column and the music visualizer (see
     * [showMusicVisualizer]). [iphoneName] is shown under the equalizer when known. Idempotent.
     */
    fun setMusicMode(on: Boolean, iphoneName: String? = null) {
        val label = if (iphoneName.isNullOrBlank()) "♪ 음악 재생 중" else "♪ ${iphoneName}에서 음악 재생 중"
        if (musicLabel.text != label) musicLabel.text = label
        if (on == musicMode) return
        musicMode = on
        if (on) {
            ripple.stop()
            column.visibility = GONE
            musicBox.visibility = VISIBLE
            visualizer.start()
            contentDescription = "음악 재생 중"
        } else {
            visualizer.stop()
            musicBox.visibility = GONE
            column.visibility = VISIBLE
            if (shown) ripple.start()
            contentDescription = "미러링 대기 중"
        }
    }

    /** Update the on-screen diagnostic line (test builds). No-op-safe to call every tick. */
    fun setDebug(text: String) { debugText.text = text }

    /** Troubleshooting lines (see [checklistFor]); empty hides the block. */
    fun setChecklist(lines: List<String>) {
        val t = lines.joinToString("\n")
        if (checklistText.text != t) checklistText.text = t
        checklistText.visibility = if (lines.isEmpty()) GONE else VISIBLE
    }

    /** Plain-language status for the driver (see [guidanceFor]). */
    fun setGuidance(g: Guidance) {
        if (guideText.text != g.text) guideText.text = g.text
        guideText.setTextColor(if (g.ok) Palette.MIRROR else Palette.SILVER_DIM)
    }

    /** Read device id / port / local address (safe before native init: shows "—"). */
    fun refreshDeviceInfoIfNeeded() {
        // CarMirror: keep refreshing — the address changes when the hotspot connects later.
        val id = runCatching { NativeBridge.nativeGetDeviceId() }.getOrNull()?.takeIf { it.isNotBlank() }
        val port = runCatching { NativeBridge.nativeGetPort() }.getOrDefault(0)
        val ip = localIpv4String()
        val addrText = if (ip != null && port > 0) "$ip:$port" else "—"
        deviceLine.text = "${CarSettings.receiverName(context)} · $addrText"
    }

    /** Fade the waiting screen out (first frame rendered). Idempotent. */
    fun fadeOut() {
        if (!shown) return
        shown = false
        ripple.stop()
        visualizer.stop()
        animateTo(0f) { visibility = GONE }
    }

    /** Fade the waiting screen back in (no stream / stream ended). Idempotent. */
    fun fadeIn() {
        if (shown && visibility == VISIBLE) return
        shown = true
        visibility = VISIBLE
        if (musicMode) visualizer.start() else ripple.start()
        animateTo(1f, null)
    }

    /** Hide without animation (e.g. while the mirror is in the floating overlay). */
    fun hideImmediate() {
        shown = false
        fadeAnim?.cancel()
        ripple.stop()
        visualizer.stop()
        alpha = 0f
        visibility = GONE
    }

    private fun animateTo(target: Float, onEnd: (() -> Unit)?) {
        fadeAnim?.cancel()
        fadeAnim = ValueAnimator.ofFloat(alpha, target).apply {
            duration = 380
            interpolator = LinearInterpolator()
            addUpdateListener { alpha = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { onEnd?.invoke() }
            })
            start()
        }
    }

    private companion object {
        const val WRAP = LayoutParams.WRAP_CONTENT
        const val MATCH_PARENT = LayoutParams.MATCH_PARENT
    }
}

/**
 * A small connected-signal dot: a solid green core inside a soft green halo that gently
 * breathes. Static under reduced motion.
 */
private class LiveDotView(context: Context) : View(context) {
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Palette.LIVE
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Palette.LIVE
    }
    private var pulse = 1f
    private val reducedMotion: Boolean = Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    ) == 0f
    private val anim = ValueAnimator.ofFloat(0.35f, 1f).apply {
        duration = 1300
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!reducedMotion) anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val coreR = context.dpf(3.5f)
        val haloR = context.dpf(8f)
        halo.alpha = ((if (reducedMotion) 0.45f else pulse) * 90f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, haloR, halo)
        core.alpha = 255
        canvas.drawCircle(cx, cy, coreR, core)
    }
}

/** The CarMirror mark (launcher foreground) with a slow breathing pulse while waiting. */
private class PulseIcon(context: Context) : ImageView(context) {
    private var anim: ValueAnimator? = null
    init {
        setImageResource(R.drawable.ic_launcher_foreground)
        scaleType = ScaleType.FIT_CENTER
        contentDescription = "CarMirror"
        start()
    }
    fun start() {
        if (anim?.isRunning == true) return
        anim = ValueAnimator.ofFloat(0.55f, 1f).apply {
            duration = 1400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { alpha = it.animatedValue as Float }
            start()
        }
    }
    fun stop() { anim?.cancel(); anim = null; alpha = 1f }
}
