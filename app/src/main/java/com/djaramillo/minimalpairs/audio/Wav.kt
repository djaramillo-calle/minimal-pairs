package com.djaramillo.minimalpairs.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RIFF/WAVE wrapping of 16-bit mono PCM for the Azure Speech REST call the
 * Say-it drill makes with the learner's own phone key (docs/CONTRACT.md,
 * "`sayit/scores/`"), plus the resampling and the silence padding that call
 * needs: the REST endpoint takes PCM16 mono at 16 kHz, and Azure scores a clip
 * that starts or ends abruptly worse than it deserves, so every recording gets
 * [PAD_MS] of digital silence on both sides before it is sent.
 *
 * Pure (no Android types), unit tested on the JVM.
 */
object Wav {
    /** The only sample rate the assessment call is made at. */
    const val SAMPLE_RATE = 16_000

    /** Silence added before and after the recording. */
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

    /**
     * Linear resampling of mono PCM to [to] Hz.
     *
     * The recorder asks for 16 kHz but not every encoder accepts an explicit
     * rate, and [AttemptRecorder] then lets it pick its own
     * ([AttemptRecorder.start]). A 44.1 kHz take sent to an endpoint told it is
     * 16 kHz would be scored as speech at a third of its proper speed — the one
     * way this drill could produce a wrong score rather than no score — so the
     * samples are converted here instead of the header being relabelled.
     * Equal rates return the input untouched.
     */
    fun resample(pcm: ShortArray, from: Int, to: Int = SAMPLE_RATE): ShortArray {
        if (from <= 0 || to <= 0 || from == to || pcm.isEmpty()) return pcm
        val outSize = ((pcm.size.toLong() * to) / from).toInt().coerceAtLeast(1)
        val out = ShortArray(outSize)
        for (i in 0 until outSize) {
            // Position of output sample i on the input timeline.
            val pos = i.toDouble() * from / to
            val left = pos.toInt()
            if (left >= pcm.size - 1) {
                out[i] = pcm[pcm.size - 1]
            } else {
                val frac = pos - left
                val a = pcm[left].toDouble()
                val b = pcm[left + 1].toDouble()
                out[i] = (a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
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

    /**
     * The bytes one recording is sent as: resampled to [SAMPLE_RATE], padded
     * and wrapped. This is the only function the assessment path uses, so the
     * rate in the header and the rate in the `Content-Type` can never disagree.
     */
    fun forAssessment(pcm: ShortArray, sampleRate: Int, padMs: Int = PAD_MS): ByteArray =
        wrap(pad(resample(pcm, sampleRate, SAMPLE_RATE), SAMPLE_RATE, padMs), SAMPLE_RATE)

    /** Size in bytes of the file [forAssessment] produces for [samples] samples already at [SAMPLE_RATE]. */
    fun paddedFileBytes(samples: Int, sampleRate: Int, padMs: Int = PAD_MS): Int {
        val n = if (padMs <= 0 || sampleRate <= 0) 0 else (sampleRate.toLong() * padMs / 1000).toInt()
        return HEADER_BYTES + (samples + 2 * n) * 2
    }
}
