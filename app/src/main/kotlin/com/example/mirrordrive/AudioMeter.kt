package com.example.mirrordrive

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns decoded mirroring PCM into a few slow-moving loudness bands for the music visualizer.
 *
 * Pure Kotlin (no Android types) so it is unit-tested on the JVM. It is fed from the audio
 * decode thread ([AudioRenderer]) and read from the UI thread ([MusicVisualizerView]); the only
 * shared state is the volatile [levels] array reference and timestamps, swapped whole.
 *
 * Cost: one 1024-point FFT roughly every 46 ms of audio, which is negligible next to decoding.
 * Any failure here is swallowed by the caller so it can never interrupt playback.
 */
class SpectrumAnalyzer(
    val bands: Int = DEFAULT_BANDS,
    private val sampleRate: Int = 44_100,
) {
    private val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1))).toFloat() }
    private val ring = FloatArray(FFT_SIZE)
    private var filled = 0
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val edges: IntArray = bandEdges(bands, sampleRate)

    /**
     * Append interleaved stereo 16-bit samples (mixed to mono). Returns a fresh band array in
     * 0..1 each time a full FFT window has been collected, otherwise null.
     */
    fun feedStereo(samples: ShortArray, count: Int = samples.size): FloatArray? {
        var out: FloatArray? = null
        var i = 0
        while (i + 1 < count) {
            ring[filled++] = (samples[i] + samples[i + 1]) / 65536f   // mono, -1..1
            i += 2
            if (filled == FFT_SIZE) {
                out = analyze()
                filled = 0
            }
        }
        return out
    }

    /** Band levels 0..1 for the current window (dB scaled, floor [FLOOR_DB]). */
    private fun analyze(): FloatArray {
        for (k in 0 until FFT_SIZE) { re[k] = ring[k] * window[k]; im[k] = 0f }
        fft(re, im)
        val result = FloatArray(bands)
        for (b in 0 until bands) {
            var peak = 0f
            for (k in edges[b] until edges[b + 1]) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                if (mag > peak) peak = mag
            }
            // A full-scale sine through a Hann window peaks at ~N/4.
            val db = 20f * log10(max(peak / (FFT_SIZE / 4f), 1e-9f))
            result[b] = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
        }
        return result
    }

    companion object {
        const val FFT_SIZE = 1024
        const val DEFAULT_BANDS = 16
        /** Quietest level drawn; anything below reads as a flat bar. */
        const val FLOOR_DB = -60f
        private const val LOW_HZ = 60.0
        private const val HIGH_HZ = 14_000.0

        /** Log-spaced FFT bin boundaries, each band at least one bin wide. */
        fun bandEdges(bands: Int, sampleRate: Int): IntArray {
            val binHz = sampleRate.toDouble() / FFT_SIZE
            val edges = IntArray(bands + 1)
            for (b in 0..bands) {
                val hz = LOW_HZ * (HIGH_HZ / LOW_HZ).pow(b.toDouble() / bands)
                edges[b] = (hz / binHz).toInt().coerceIn(1, FFT_SIZE / 2)
            }
            for (b in 1..bands) if (edges[b] <= edges[b - 1]) edges[b] = min(edges[b - 1] + 1, FFT_SIZE / 2)
            return edges
        }

        /** In-place iterative radix-2 FFT; [re].size must be a power of two. */
        fun fft(re: FloatArray, im: FloatArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang).toFloat()
                val wi = sin(ang).toFloat()
                var i = 0
                while (i < n) {
                    var cr = 1f
                    var ci = 0f
                    for (k in 0 until len / 2) {
                        val a = i + k
                        val b = a + len / 2
                        val xr = re[b] * cr - im[b] * ci
                        val xi = re[b] * ci + im[b] * cr
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] += xr; im[a] += xi
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}

/** Process-wide latest audio levels, written by the decoder thread, read by the UI. */
object AudioMeter {
    private val analyzer = SpectrumAnalyzer()

    /** Latest band levels (0..1). Replaced whole, never mutated after publishing. */
    @Volatile var levels: FloatArray = FloatArray(analyzer.bands)
        private set
    /** Clock (ms) of the last published analysis, and of the last one that was audibly loud. */
    @Volatile var updatedAtMs = 0L
        private set
    @Volatile var loudAtMs = 0L
        private set

    /** Test seam: the clock used for timestamps. */
    @Volatile var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    /** Feed interleaved stereo 16-bit PCM. Synchronized: hw and sw decode paths never overlap, but be safe. */
    @Synchronized
    fun feed(samples: ShortArray, count: Int = samples.size) {
        val bands = analyzer.feedStereo(samples, count) ?: return
        val now = clock()
        levels = bands
        updatedAtMs = now
        if (isAudible(bands)) loudAtMs = now
    }

    /** Feed little/native-endian 16-bit PCM bytes as produced by MediaCodec. */
    fun feedBytes(pcm: ByteArray, order: java.nio.ByteOrder = java.nio.ByteOrder.nativeOrder()) {
        val sb = java.nio.ByteBuffer.wrap(pcm).order(order).asShortBuffer()
        val shorts = ShortArray(sb.remaining())
        sb.get(shorts)
        feed(shorts)
    }

    /** True when music has been audible within [holdMs] (so short gaps between songs don't flicker). */
    fun musicPlaying(now: Long = clock(), holdMs: Long = MUSIC_HOLD_MS): Boolean =
        loudAtMs != 0L && now - loudAtMs < holdMs

    /** Levels to draw now: fall to silence if the decoder has gone quiet. */
    fun currentLevels(now: Long = clock()): FloatArray =
        if (updatedAtMs != 0L && now - updatedAtMs < STALE_MS) levels else FloatArray(levels.size)

    /** Test helper. */
    @Synchronized
    fun reset() {
        levels = FloatArray(analyzer.bands); updatedAtMs = 0L; loudAtMs = 0L
    }

    const val MUSIC_HOLD_MS = 3_000L
    const val STALE_MS = 400L
    /** A band above this (≈ -42 dBFS) counts as real sound rather than codec noise. */
    const val AUDIBLE_LEVEL = 0.3f

    fun isAudible(bands: FloatArray): Boolean = bands.any { it >= AUDIBLE_LEVEL }
}

/**
 * Whether the waiting screen should show the music visualizer instead of the connect guidance:
 * only when the feature is on, the video has stopped (iPhone screen idle/blank), and music is
 * actually playing. Pure so it is unit-tested.
 */
fun showMusicVisualizer(enabled: Boolean, videoLive: Boolean, musicPlaying: Boolean): Boolean =
    enabled && !videoLive && musicPlaying

/**
 * One animation step for a bar: rise quickly toward a louder target, fall slowly when it gets
 * quieter. Slow falls keep motion calm, which matters on a screen next to the road.
 */
fun smoothBar(current: Float, target: Float, rise: Float = 0.35f, fall: Float = 0.06f): Float =
    if (target > current) current + (target - current) * rise
    else current + (target - current) * fall
