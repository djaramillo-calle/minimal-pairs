package com.djaramillo.minimalpairs.audio

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure 16-bit PCM helpers (no Android types) so they can be unit tested on the JVM.
 */
object Pcm {
    /** Leading silence threshold: samples below this are trimmed at the start. */
    const val LEAD_DBFS = -50.0
    /** Keep this much audio before the first sample above [LEAD_DBFS]. */
    const val LEAD_KEEP_MS = 10
    /** Trailing silence threshold. */
    const val TAIL_DBFS = -60.0
    /** Keep this much audio after the last sample above [TAIL_DBFS]. */
    const val TAIL_KEEP_MS = 30

    /** Absolute sample value corresponding to [dbfs] (0 dBFS = 32767). */
    fun threshold(dbfs: Double): Int = (32767.0 * 10.0.pow(dbfs / 20.0)).roundToInt()

    /**
     * Trim leading samples below [LEAD_DBFS] (keeping [LEAD_KEEP_MS] before the
     * first loud sample) and trailing samples below [TAIL_DBFS] (keeping
     * [TAIL_KEEP_MS] after the last loud sample). A clip that never crosses the
     * lead threshold is returned unchanged; the result is never empty when the
     * input is not.
     */
    fun trim(
        pcm: ShortArray,
        sampleRate: Int,
        leadDbfs: Double = LEAD_DBFS,
        leadKeepMs: Int = LEAD_KEEP_MS,
        tailDbfs: Double = TAIL_DBFS,
        tailKeepMs: Int = TAIL_KEEP_MS,
    ): ShortArray {
        if (pcm.isEmpty() || sampleRate <= 0) return pcm
        val leadT = threshold(leadDbfs)
        val tailT = threshold(tailDbfs)
        var first = -1
        for (i in pcm.indices) {
            if (abs(pcm[i].toInt()) > leadT) { first = i; break }
        }
        if (first < 0) return pcm
        var last = pcm.size - 1
        while (last > first && abs(pcm[last].toInt()) <= tailT) last--
        val leadKeep = sampleRate * leadKeepMs / 1000
        val tailKeep = sampleRate * tailKeepMs / 1000
        val start = (first - leadKeep).coerceAtLeast(0)
        val endExclusive = (last + 1 + tailKeep).coerceAtMost(pcm.size)
        if (start == 0 && endExclusive == pcm.size) return pcm
        return pcm.copyOfRange(start, endExclusive)
    }

    /** Average interleaved channels down to mono. `channels <= 1` returns the input. */
    fun toMono(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            val base = f * channels
            for (c in 0 until channels) sum += interleaved[base + c]
            out[f] = (sum / channels).toShort()
        }
        return out
    }

    /** Duration in milliseconds of [samples] mono samples at [sampleRate]. */
    fun durationMs(samples: Int, sampleRate: Int): Int =
        if (sampleRate <= 0) 0 else (samples.toLong() * 1000L / sampleRate).toInt()
}
