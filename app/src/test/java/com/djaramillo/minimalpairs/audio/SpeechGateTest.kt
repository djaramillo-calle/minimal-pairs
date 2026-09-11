package com.djaramillo.minimalpairs.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpeechGateTest {
    private val rate = 16_000

    private fun ms(n: Int) = rate * n / 1000

    /** Deterministic "noise": a low-amplitude alternating pattern. */
    private fun noise(lengthMs: Int, amplitude: Int): ShortArray =
        ShortArray(ms(lengthMs)) { i -> (if (i % 3 == 0) amplitude else -amplitude / 2).toShort() }

    /** A 220 Hz tone standing in for speech. */
    private fun tone(lengthMs: Int, amplitude: Int): ShortArray =
        ShortArray(ms(lengthMs)) { i -> (amplitude * sin(2 * PI * 220 * i / rate)).toInt().toShort() }

    private fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) { p.copyInto(out, pos); pos += p.size }
        return out
    }

    @Test
    fun rms_of_a_constant_signal_is_its_amplitude() {
        assertEquals(1000.0, SpeechGate.rms(ShortArray(100) { 1000 }), 1e-9)
        assertEquals(0.0, SpeechGate.rms(ShortArray(0)), 0.0)
    }

    @Test
    fun stops_600ms_after_a_word_and_reports_the_speech() {
        // 150 ms of room noise (calibration), 500 ms of "speech", then quiet.
        val pcm = concat(noise(150, 40), tone(500, 6000), noise(1500, 40))
        val out = SpeechGate.analyse(pcm, rate)
        assertTrue(out.hasSpeech)
        assertEquals(500, out.speechMs, 40.0)
        // 150 + 500 + 600 = 1250 ms, within one or two frames.
        assertEquals(1250, out.stopAtMs, 40.0)
        assertNotNull(out.floor)
    }

    @Test
    fun pure_noise_runs_to_the_cap_with_no_speech() {
        val out = SpeechGate.analyse(noise(3500, 40), rate)
        assertEquals(3000, out.stopAtMs)
        assertFalse(out.hasSpeech)
        assertEquals(0, out.speechMs)
    }

    @Test
    fun digital_silence_never_counts_as_speech() {
        val out = SpeechGate.analyse(ShortArray(ms(3200)), rate)
        assertEquals(3000, out.stopAtMs)
        assertFalse(out.hasSpeech)
        assertEquals(1.0, out.floor!!, 0.0)
    }

    @Test
    fun a_short_click_is_not_speech_and_does_not_end_the_recording_early() {
        val pcm = concat(noise(150, 40), tone(60, 8000), noise(2900, 40))
        val out = SpeechGate.analyse(pcm, rate)
        assertFalse(out.hasSpeech)
        assertEquals(3000, out.stopAtMs)
    }

    @Test
    fun continuous_speech_is_cut_at_three_seconds() {
        val pcm = concat(noise(150, 40), tone(3500, 6000))
        val out = SpeechGate.analyse(pcm, rate)
        assertEquals(3000, out.stopAtMs)
        assertTrue(out.hasSpeech)
        assertEquals(2850, out.speechMs, 40.0)
    }

    @Test
    fun a_pause_inside_a_word_shorter_than_600ms_does_not_stop() {
        val pcm = concat(noise(150, 40), tone(300, 6000), noise(400, 40), tone(300, 6000), noise(1000, 40))
        val out = SpeechGate.analyse(pcm, rate)
        // Stop 600 ms after the second burst: 150 + 300 + 400 + 300 + 600 = 1750.
        assertEquals(1750, out.stopAtMs, 40.0)
        assertEquals(600, out.speechMs, 60.0)
    }

    @Test
    fun thresholds_follow_the_noise_floor() {
        // Loud room (floor ≈ 700): a 1500-amplitude hum (below 3 × floor) is not speech there…
        val loud = concat(noise(150, 1000), tone(800, 1500), noise(1000, 1000))
        assertFalse(SpeechGate.analyse(loud, rate).hasSpeech)
        // …but the same hum in a quiet room is.
        val quiet = concat(noise(150, 40), tone(800, 1500), noise(1000, 40))
        assertTrue(SpeechGate.analyse(quiet, rate).hasSpeech)
    }

    @Test
    fun talking_during_calibration_still_registers_thanks_to_the_floor_cap() {
        val pcm = concat(tone(150, 12000), tone(500, 12000), noise(1200, 40))
        val out = SpeechGate.analyse(pcm, rate)
        assertEquals(SpeechGate.MAX_FLOOR, out.floor!!, 0.0)
        assertTrue(out.hasSpeech)
        assertEquals(1250, out.stopAtMs, 40.0)
    }

    @Test
    fun a_word_that_starts_with_the_recording_is_still_heard() {
        // The learner taps and speaks at once: every calibration frame holds their voice, so the
        // floor can only come from the cap. RMS ≈ 2121 (amplitude 3000) is ordinary speech.
        val pcm = concat(tone(600, 3000), noise(1500, 40))
        val out = SpeechGate.analyse(pcm, rate)
        assertEquals(SpeechGate.MAX_FLOOR, out.floor!!, 0.0)
        assertTrue(out.hasSpeech)
        // Speech counts from the end of the calibration window: 160 ms … 600 ms.
        assertEquals(440, out.speechMs, 40.0)
        assertEquals(1200, out.stopAtMs, 40.0)
    }

    @Test
    fun a_word_that_starts_inside_the_calibration_window_is_heard_at_room_level() {
        // 60 ms of room, then the word: the quiet frames decide the floor, not the loud majority,
        // so even a quiet speaker (RMS ≈ 1060) is well above the onset.
        val pcm = concat(noise(60, 40), tone(500, 1500), noise(1200, 40))
        val out = SpeechGate.analyse(pcm, rate)
        assertTrue(out.floor!! < 100.0)
        assertTrue(out.hasSpeech)
        assertEquals(1160, out.stopAtMs, 40.0)
    }

    @Test
    fun the_floor_is_a_low_percentile_of_the_calibration_frames() {
        assertEquals(0.0, SpeechGate.percentile(emptyList(), 20), 0.0)
        assertEquals(1.0, SpeechGate.percentile(listOf(5.0, 1.0, 3.0), 20), 0.0)
        // Eight frames, one of them quiet noise and seven of them speech: the quiet one is taken.
        val frames = listOf(4000.0, 3000.0, 30.0, 5000.0, 4200.0, 3900.0, 4400.0, 5100.0)
        assertEquals(3000.0, SpeechGate.percentile(frames, 20), 0.0)
        assertEquals(30.0, SpeechGate.percentile(frames, 0), 0.0)
    }

    @Test
    fun level_is_bounded_and_speaking_flag_follows_the_frames() {
        val gate = SpeechGate(rate)
        val frame = ms(20)
        repeat(8) { gate.feed(ShortArray(frame) { 30 }) } // 160 ms calibration
        assertFalse(gate.speaking)
        gate.feed(ShortArray(frame) { 20000 })
        assertTrue(gate.speaking)
        assertEquals(1f, gate.level, 0f)
        gate.feed(ShortArray(frame) { 30 })
        assertFalse(gate.speaking)
        assertTrue(gate.level < 0.01f)
        assertEquals(20, gate.silenceRunMs)
    }

    @Test
    fun empty_frames_are_ignored() {
        val gate = SpeechGate(rate)
        assertFalse(gate.feed(ShortArray(0)))
        assertEquals(0, gate.elapsedMs)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Double) {
        assertTrue("expected $expected ± $tolerance but was $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }
}
