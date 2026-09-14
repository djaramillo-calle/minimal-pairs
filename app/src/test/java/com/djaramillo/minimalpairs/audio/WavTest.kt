package com.djaramillo.minimalpairs.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

class WavTest {
    private val rate = Wav.SAMPLE_RATE

    private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun ascii(bytes: ByteArray, from: Int, len: Int) = String(bytes, from, len, Charsets.US_ASCII)

    @Test
    fun header_fields_are_canonical_pcm16_mono() {
        val pcm = shortArrayOf(1, -2, 3)
        val wav = Wav.wrap(pcm, rate)
        assertEquals(Wav.HEADER_BYTES + 6, wav.size)
        val b = le(wav)
        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals(36 + 6, b.getInt(4))
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(rate, b.getInt(24))
        assertEquals(rate * 2, b.getInt(28)) // byte rate
        assertEquals(2, b.getShort(32).toInt()) // block align
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", ascii(wav, 36, 4))
        assertEquals(6, b.getInt(40))
    }

    @Test
    fun samples_are_little_endian_after_the_header() {
        val wav = Wav.wrap(shortArrayOf(0x1234, -1), rate)
        assertArrayEquals(byteArrayOf(0x34, 0x12, -1, -1), wav.copyOfRange(44, 48))
    }

    @Test
    fun padding_adds_400ms_of_zeros_on_both_sides() {
        val pcm = ShortArray(100) { 5 }
        val padded = Wav.pad(pcm, rate)
        val n = rate * 400 / 1000 // 6400 samples
        assertEquals(100 + 2 * n, padded.size)
        for (i in 0 until n) assertEquals(0, padded[i].toInt())
        for (i in n until n + 100) assertEquals(5, padded[i].toInt())
        for (i in n + 100 until padded.size) assertEquals(0, padded[i].toInt())
    }

    @Test
    fun no_padding_returns_the_input() {
        val pcm = shortArrayOf(1, 2)
        assertSame(pcm, Wav.pad(pcm, rate, 0))
        assertSame(pcm, Wav.pad(pcm, 0))
    }

    @Test
    fun padded_file_size_matches_the_bytes_produced() {
        val pcm = ShortArray(rate) // one second, already at 16 kHz
        val wav = Wav.forAssessment(pcm, rate)
        assertEquals(Wav.paddedFileBytes(pcm.size, rate), wav.size)
        assertEquals(44 + (rate + 2 * 6400) * 2, wav.size)
        assertEquals((rate + 2 * 6400) * 2, le(wav).getInt(40))
        assertEquals(36 + (rate + 2 * 6400) * 2, le(wav).getInt(4))
    }

    @Test
    fun empty_recording_still_yields_a_valid_file() {
        val wav = Wav.wrap(ShortArray(0), rate)
        assertEquals(44, wav.size)
        assertEquals(0, le(wav).getInt(40))
        assertEquals(36, le(wav).getInt(4))
    }

    // ---- resampling: the one path that could turn a good take into a bad score ----

    @Test
    fun a_rate_that_already_matches_is_left_alone() {
        val pcm = shortArrayOf(1, 2, 3)
        assertSame(pcm, Wav.resample(pcm, rate, rate))
    }

    @Test
    fun resampling_keeps_the_duration_and_changes_the_sample_count() {
        val oneSecondAt44k = ShortArray(44_100)
        val out = Wav.resample(oneSecondAt44k, 44_100, rate)
        assertEquals(rate, out.size)
    }

    @Test
    fun a_44k_recording_is_converted_not_relabelled() {
        // A 200 Hz tone recorded at 44.1 kHz must still be a 200 Hz tone after
        // conversion. Relabelling the header instead would make Azure hear it
        // at a third of its speed and score speech that was never spoken.
        val from = 44_100
        val seconds = 0.25
        val hz = 200.0
        val input = ShortArray((from * seconds).toInt()) {
            (sin(2.0 * Math.PI * hz * it / from) * 20000).toInt().toShort()
        }
        val out = Wav.resample(input, from, rate)
        assertEquals((rate * seconds).toInt(), out.size)
        assertEquals(hz, dominantHz(out, rate), 8.0)
    }

    @Test
    fun the_assessment_bytes_always_declare_16k_whatever_the_recording_was() {
        val input = ShortArray(44_100) { 1000 }
        val wav = Wav.forAssessment(input, 44_100)
        assertEquals(rate, le(wav).getInt(24))
        // One second of audio plus the padding, at 16 kHz.
        assertEquals((rate + 2 * 6400) * 2, le(wav).getInt(40))
    }

    @Test
    fun energy_above_the_new_nyquist_is_filtered_out_not_folded_into_speech() {
        // A 12 kHz component of a 44.1 kHz take has nowhere to go at 16 kHz.
        // Plain decimation folds it to 4 kHz at nearly full amplitude, on top
        // of the vowel, and Azure scores that faithfully: the one way this
        // drill could mark the learner down for a noise the phone made.
        val from = 44_100
        val tone = ShortArray((from * 0.25).toInt()) {
            (sin(2.0 * Math.PI * 12_000.0 * it / from) * 20000).toInt().toShort()
        }
        val out = Wav.resample(tone, from, rate)
        assertEquals((rate * 0.25).toInt(), out.size)
        // Measured away from the first and last half-window, where repeating
        // the edge sample of a bare tone makes a step of its own. A real
        // recording begins and ends in silence, and gets 400 ms more of it
        // before it is sent.
        val edge = 40
        val peak = (edge until out.size - edge).maxOf { abs(out[it].toInt()) }
        assertTrue("out-of-band tone survived at $peak of 20000", peak < 400)
    }

    @Test
    fun speech_band_content_passes_through_at_full_amplitude() {
        // The filter must not eat the band being assessed.
        val from = 44_100
        val tone = ShortArray((from * 0.25).toInt()) {
            (sin(2.0 * Math.PI * 1_000.0 * it / from) * 20000).toInt().toShort()
        }
        val out = Wav.resample(tone, from, rate)
        val peak = out.maxOf { abs(it.toInt()) }
        assertTrue("1 kHz was attenuated to $peak of 20000", peak > 19_000)
        assertEquals(1_000.0, dominantHz(out, rate), 8.0)
    }

    @Test
    fun the_ends_of_a_recording_keep_their_level() {
        // The window runs off the array at both ends; normalising by the taps'
        // own sum keeps the gain at 1 there, or the first and last syllables
        // would fade and read as mumbling.
        val from = 48_000
        val flat = ShortArray(from / 4) { 12_000 }
        val out = Wav.resample(flat, from, rate)
        assertTrue(out.size > 100)
        for (i in listOf(0, 1, 2, out.size / 2, out.size - 3, out.size - 2, out.size - 1)) {
            assertTrue("sample $i was ${out[i]}", abs(out[i] - 12_000) < 400)
        }
    }

    @Test
    fun a_nonsense_rate_is_survived_rather_than_crashing() {
        val pcm = shortArrayOf(1, 2, 3)
        assertSame(pcm, Wav.resample(pcm, 0, rate))
        assertSame(pcm, Wav.resample(pcm, rate, 0))
        assertTrue(Wav.resample(ShortArray(0), 44_100, rate).isEmpty())
    }

    @Test
    fun upsampling_is_supported_too() {
        val out = Wav.resample(ShortArray(8_000) { 100 }, 8_000, rate)
        assertEquals(rate, out.size)
        assertNotEquals(0, out[100].toInt())
    }

    /** Crude peak-of-the-DFT frequency estimate; enough to prove the pitch survived. */
    private fun dominantHz(pcm: ShortArray, sampleRate: Int): Double {
        var bestHz = 0.0
        var best = -1.0
        var hz = 50.0
        while (hz < 1000.0) {
            var re = 0.0
            var im = 0.0
            for (i in pcm.indices) {
                val a = 2.0 * Math.PI * hz * i / sampleRate
                re += pcm[i] * kotlin.math.cos(a)
                im += pcm[i] * sin(a)
            }
            val mag = abs(re) + abs(im)
            if (mag > best) { best = mag; bestHz = hz }
            hz += 1.0
        }
        return bestHz
    }
}
