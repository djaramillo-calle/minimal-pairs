package com.djaramillo.minimalpairs.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RIFF/WAVE wrapping of 16-bit mono PCM for the Azure Speech REST calls
 * (`Content-Type: audio/wav; codecs=audio/pcm; samplerate=16000`), plus the
 * silence padding docs/CONTRACT.md asks for: Azure drops clips that start or
 * end abruptly, so every recording gets [PAD_MS] of digital silence on both
 * sides before it is sent. Pure (no Android types), unit tested.
 */
object Wav {
    /** Silence added before and after the recording (docs/CONTRACT.md "Say it"). */
    const val PAD_MS = 400
    /** Canonical PCM header size: RIFF chunk + `fmt ` chunk + `data` header. */
    const val HEADER_BYTES = 44
    private const val PCM_FORMAT: Short = 1
    private const val BITS_PER_SAMPLE: Short = 16

    /** [pcm] with [padMs] of zeros on each side. The input is returned as is when [padMs] ≤ 0. */
    fun pad(pcm: ShortArray, sampleRate: Int, padMs: Int = PAD_MS): ShortArray {
        if (padMs <= 0 || sampleRate <= 0) return pcm
        val n = (sampleRate.toLong() * padMs / 1000).toInt()
        val out = ShortArray(pcm.size + 2 * n)
        pcm.copyInto(out, n)
        return out
    }

    /** A complete little-endian PCM16 mono WAV file. */
    fun wrap(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = pcm.size * 2
        val buf = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(PCM_FORMAT)
        buf.putShort(1) // channels
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2) // byte rate: rate × channels × 2 bytes
        buf.putShort(2) // block align
        buf.putShort(BITS_PER_SAMPLE)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataBytes)
        for (s in pcm) buf.putShort(s)
        return buf.array()
    }

    /** [pad] then [wrap]: the bytes the recording is sent as. */
    fun wrapPadded(pcm: ShortArray, sampleRate: Int, padMs: Int = PAD_MS): ByteArray = wrap(pad(pcm, sampleRate, padMs), sampleRate)

    /** Size in bytes of the file [wrapPadded] produces for [samples] samples. */
    fun paddedFileBytes(samples: Int, sampleRate: Int, padMs: Int = PAD_MS): Int {
        val n = if (padMs <= 0 || sampleRate <= 0) 0 else (sampleRate.toLong() * padMs / 1000).toInt()
        return HEADER_BYTES + (samples + 2 * n) * 2
    }
}
