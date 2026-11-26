package com.example.mirrordrive

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrumAnalyzerTest {
    private fun stereoSine(hz: Double, amp: Double, frames: Int): ShortArray {
        val s = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (amp * 32767 * sin(2 * PI * hz * i / 44_100)).toInt().toShort()
            s[2 * i] = v; s[2 * i + 1] = v
        }
        return s
    }

    private fun bandOf(hz: Double): Int {
        val edges = SpectrumAnalyzer.bandEdges(SpectrumAnalyzer.DEFAULT_BANDS, 44_100)
        val bin = (hz / (44_100.0 / SpectrumAnalyzer.FFT_SIZE)).toInt()
        return (0 until SpectrumAnalyzer.DEFAULT_BANDS).first { bin >= edges[it] && bin < edges[it + 1] }
    }

    @Test fun needsAFullWindowBeforeReporting() {
        val a = SpectrumAnalyzer()
        assertEquals(null, a.feedStereo(stereoSine(440.0, 0.5, 500)))
        assertTrue(a.feedStereo(stereoSine(440.0, 0.5, 600)) != null)
    }

    @Test fun loudToneLightsItsOwnBand() {
        val levels = SpectrumAnalyzer().feedStereo(stereoSine(1000.0, 0.8, 1024))!!
        val b = bandOf(1000.0)
        assertEquals(b, levels.indices.maxByOrNull { levels[it] })
        assertTrue("peak ${levels[b]}", levels[b] > 0.85f)
        assertTrue("far band ${levels[0]}", levels[0] < 0.5f)
    }

    @Test fun silenceIsFlat() {
        val levels = SpectrumAnalyzer().feedStereo(ShortArray(2048))!!
        assertTrue(levels.all { it == 0f })
        assertFalse(AudioMeter.isAudible(levels))
    }

    @Test fun bandEdgesStrictlyIncrease() {
        val e = SpectrumAnalyzer.bandEdges(16, 44_100)
        for (i in 1 until e.size) assertTrue(e[i] > e[i - 1])
        assertTrue(e.last() <= SpectrumAnalyzer.FFT_SIZE / 2)
    }
}

class AudioMeterTest {
    private var now = 10_000L

    @Before fun setUp() { AudioMeter.reset(); AudioMeter.clock = { now } }
    @After fun tearDown() { AudioMeter.reset(); AudioMeter.clock = { System.nanoTime() / 1_000_000 } }

    private fun loud(): ShortArray = ShortArray(2048) { i -> (20000 * sin(2 * PI * 440 * (i / 2) / 44_100)).toInt().toShort() }

    @Test fun musicHoldsBrieflyAfterSoundStops() {
        assertFalse(AudioMeter.musicPlaying())
        AudioMeter.feed(loud())
        assertTrue(AudioMeter.musicPlaying())
        now += 2_000; assertTrue(AudioMeter.musicPlaying())
        now += 1_500; assertFalse(AudioMeter.musicPlaying())
    }

    @Test fun quietAudioIsNotMusic() {
        AudioMeter.feed(ShortArray(2048) { if (it % 2 == 0) 3 else -3 })
        assertFalse(AudioMeter.musicPlaying())
    }

    @Test fun levelsFallToZeroWhenStale() {
        AudioMeter.feed(loud())
        assertTrue(AudioMeter.currentLevels().any { it > 0f })
        now += AudioMeter.STALE_MS + 1
        assertTrue(AudioMeter.currentLevels().all { it == 0f })
    }

    @Test fun bytesMatchShorts() {
        val s = loud()
        val bb = java.nio.ByteBuffer.allocate(s.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        s.forEach { bb.putShort(it) }
        AudioMeter.feedBytes(bb.array(), java.nio.ByteOrder.LITTLE_ENDIAN)
        val fromBytes = AudioMeter.levels.copyOf()
        AudioMeter.reset(); AudioMeter.feed(s)
        assertTrue(fromBytes.contentEquals(AudioMeter.levels))
    }
}

class VisualizerRulesTest {
    @Test fun onlyWhenVideoIdleAndMusicPlaying() {
        assertTrue(showMusicVisualizer(enabled = true, videoLive = false, musicPlaying = true))
        assertFalse(showMusicVisualizer(enabled = true, videoLive = true, musicPlaying = true))
        assertFalse(showMusicVisualizer(enabled = true, videoLive = false, musicPlaying = false))
        assertFalse(showMusicVisualizer(enabled = false, videoLive = false, musicPlaying = true))
    }

    @Test fun barsRiseFastFallSlow() {
        val up = smoothBar(0f, 1f)
        val down = 1f - smoothBar(1f, 0f)
        assertTrue(up > down * 3)
        assertEquals(0.5f, smoothBar(0.5f, 0.5f), 0f)
    }
}
