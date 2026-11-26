package com.example.mirrordrive

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import android.view.View

/**
 * Calm "mirror" equalizer shown while only music is coming from the iPhone: rounded cyan bars
 * that rise from a centre line, with a faint reflection below (the CarMirror glass motif).
 *
 * Reads [AudioMeter] each animation frame; it never touches the audio path. Motion is kept slow
 * on purpose (fast rise, slow fall, no flashing) so it doesn't pull a driver's eyes, and under
 * the system "remove animations" setting it refreshes only a few times a second.
 */
class MusicVisualizerView(context: Context) : View(context) {
    private var bars = FloatArray(SpectrumAnalyzer.DEFAULT_BANDS)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val reflectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.MIRROR_HAIRLINE
        strokeWidth = context.dpf(1)
    }
    private val rect = RectF()
    private var running = false
    private val reducedMotion: Boolean = Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    ) == 0f
    private val tick = Runnable { if (running) invalidate() }

    init {
        contentDescription = "음악 재생 중"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun start() {
        if (running) return
        running = true
        invalidate()
    }

    fun stop() {
        running = false
        removeCallbacks(tick)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val mid = h * MID
        barPaint.shader = LinearGradient(0f, 0f, 0f, mid, Palette.MIRROR, Palette.MIRROR_DIM, Shader.TileMode.CLAMP)
        reflectPaint.shader = LinearGradient(0f, mid, 0f, h.toFloat(), Palette.MIRROR_GLOW, 0x0055D6D0, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val target = AudioMeter.currentLevels()
        if (target.size != bars.size) bars = FloatArray(target.size)
        for (i in bars.indices) bars[i] = smoothBar(bars[i], target[i])

        val n = bars.size
        val w = width.toFloat()
        val h = height.toFloat()
        val mid = h * MID
        val slot = w / n
        val barW = slot * 0.56f
        val radius = barW / 2f
        val minH = context.dpf(4)
        val maxUp = mid - context.dpf(2)
        val maxDown = (h - mid) * 0.8f

        canvas.drawLine(0f, mid, w, mid, linePaint)
        for (i in 0 until n) {
            // Gentle arch: centre bars may reach a little higher than the edges.
            val shape = 0.75f + 0.25f * (1f - kotlin.math.abs((i - (n - 1) / 2f) / ((n - 1) / 2f)))
            val v = bars[i] * shape
            val left = i * slot + (slot - barW) / 2f
            val up = minH + v * (maxUp - minH)
            rect.set(left, mid - up, left + barW, mid - context.dpf(1))
            canvas.drawRoundRect(rect, radius, radius, barPaint)
            val down = minH * 0.6f + v * (maxDown - minH)
            rect.set(left, mid + context.dpf(3), left + barW, mid + context.dpf(3) + down)
            canvas.drawRoundRect(rect, radius, radius, reflectPaint)
        }

        if (running) {
            if (reducedMotion) postDelayed(tick, 250) else postInvalidateOnAnimation()
        }
    }

    private companion object {
        /** The reflection line sits two-thirds down. */
        const val MID = 0.66f
    }
}
