package com.djaramillo.minimalpairs.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTest {
    private val rate = 24_000

    private fun clip(leadSilence: Int, loud: Int, tailSilence: Int, loudValue: Short = 8000, noise: Short = 0): ShortArray {
        val out = ShortArray(leadSilence + loud + tailSilence) { noise }
        for (i in leadSilence until leadSilence + loud) out[i] = if (i % 2 == 0) loudValue else (-loudValue).toShort()
        return out
    }

    @Test
    fun thresholds_match_dbfs() {
        assertEquals(104, Pcm.threshold(-50.0))
        assertEquals(33, Pcm.threshold(-60.0))
        assertEquals(32767, Pcm.threshold(0.0))
    }

    @Test
    fun trims_leading_silence_keeping_10ms() {
        val lead = 2400 // 100 ms
        val pcm = clip(lead, 4800, 0)
        val out = Pcm.trim(pcm, rate)
        // 10 ms at 24 kHz = 240 samples kept before the onset.
        assertEquals(240 + 4800, out.size)
        assertEquals(0, out[0].toInt())
        assertEquals(8000, out[240].toInt())
    }

    @Test
    fun trims_trailing_silence_keeping_30ms() {
        val pcm = clip(0, 4800, 4800)
        val out = Pcm.trim(pcm, rate)
        assertEquals(4800 + 720, out.size)
    }

    @Test
    fun low_noise_below_lead_threshold_is_trimmed_but_kept_within_tail_threshold() {
        // Noise at 50 (< -50 dBFS = 104, > -60 dBFS = 33): leading part is silence for the lead rule,
        // trailing part is NOT silence for the stricter tail rule.
        val pcm = clip(2400, 4800, 2400, noise = 50)
        val out = Pcm.trim(pcm, rate)
        assertEquals(240 + 4800 + 2400, out.size)
    }

    @Test
    fun silent_clip_is_returned_unchanged() {
        val pcm = ShortArray(1000) { 10 }
        assertSame(pcm, Pcm.trim(pcm, rate))
        val empty = ShortArray(0)
        assertSame(empty, Pcm.trim(empty, rate))
    }

    @Test
    fun clip_without_silence_is_returned_as_is() {
        val pcm = clip(0, 1000, 0)
        assertSame(pcm, Pcm.trim(pcm, rate))
    }

    @Test
    fun short_lead_is_kept_whole() {
        val pcm = clip(100, 1000, 0)
        val out = Pcm.trim(pcm, rate)
        assertEquals(1100, out.size) // 100 < 240 samples of keep: nothing to cut
    }

    @Test
    fun single_loud_sample_never_yields_empty() {
        val pcm = ShortArray(5000)
        pcm[2500] = 20000
        val out = Pcm.trim(pcm, rate)
        assertTrue(out.isNotEmpty())
        assertEquals(240 + 1 + 720, out.size)
    }

    @Test
    fun stereo_downmix_averages_channels() {
        val stereo = shortArrayOf(100, 300, -200, 200, 10, 20)
        assertArrayEquals(shortArrayOf(200, 0, 15), Pcm.toMono(stereo, 2))
        assertSame(stereo, Pcm.toMono(stereo, 1))
    }

    @Test
    fun duration() {
        assertEquals(500, Pcm.durationMs(12_000, 24_000))
        assertEquals(0, Pcm.durationMs(12_000, 0))
    }
}
