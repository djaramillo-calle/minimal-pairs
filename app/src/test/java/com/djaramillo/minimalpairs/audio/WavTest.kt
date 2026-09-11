package com.djaramillo.minimalpairs.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    private val rate = 16_000

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
        val pcm = ShortArray(rate) // one second
        val wav = Wav.wrapPadded(pcm, rate)
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
}
