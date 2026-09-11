package com.djaramillo.minimalpairs.audio

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure 16-bit PCM helpers (no Android types) so they can be unit tested on the JVM.
 */
object Pcm {
    /** Leading silence threshold (RMS over [LEAD_WINDOW_MS]): audio below this is trimmed at the start. */
    const val LEAD_DBFS = -50.0
    /**
     * Length of the RMS window used to find the onset. A single stray sample
     * (click, DC step, decoder pre-roll artefact) cannot lift a 5 ms window
     * above [LEAD_DBFS], so it does not disable the trim.
     */
    const val LEAD_WINDOW_MS = 5
    /** Keep this much audio before the onset. */
    const val LEAD_KEEP_MS = 10
    /** Trailing silence threshold. */
    const val TAIL_DBFS = -60.0
    /** Keep this much audio after the last sample above [TAIL_DBFS]. */
    const val TAIL_KEEP_MS = 30

    /** Absolute sample value corresponding to [dbfs] (0 dBFS = 32767). */
    fun threshold(dbfs: Double): Int = (32767.0 * 10.0.pow(dbfs / 20.0)).roundToInt()

    /**
     * Trim leading silence (keeping [LEAD_KEEP_MS] before the onset) and
     * trailing samples below [TAIL_DBFS] (keeping [TAIL_KEEP_MS] after the
     * last loud sample). The onset is the first sample above [LEAD_DBFS]
     * inside the first [LEAD_WINDOW_MS] window whose RMS exceeds [LEAD_DBFS],
     * so an isolated click at the head is ignored. A clip that never crosses
     * the lead threshold is returned unchanged; the result is never empty when
     * the input is not.
     */
    fun trim(
        pcm: ShortArray,
        sampleRate: Int,
        leadDbfs: Double = LEAD_DBFS,
        leadKeepMs: Int = LEAD_KEEP_MS,
        leadWindowMs: Int = LEAD_WINDOW_MS,
        tailDbfs: Double = TAIL_DBFS,
        tailKeepMs: Int = TAIL_KEEP_MS,
    ): ShortArray {
        if (pcm.isEmpty() || sampleRate <= 0) return pcm
        val leadT = threshold(leadDbfs)
        val tailT = threshold(tailDbfs)
        val first = onset(pcm, sampleRate, leadT, leadWindowMs)
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

    /**
     * Index of the first sample above [threshold] inside the first window of
     * [windowMs] whose RMS exceeds [threshold]; -1 when no window does.
     * Runs in O(n) with a running sum of squares.
     */
    fun onset(pcm: ShortArray, sampleRate: Int, threshold: Int, windowMs: Int): Int {
        if (pcm.isEmpty()) return -1
        val window = (sampleRate.toLong() * windowMs / 1000).toInt().coerceIn(1, pcm.size)
        // mean(square) > threshold^2  <=>  sumsq > threshold^2 * window
        val limit = threshold.toLong() * threshold.toLong() * window
        var sumsq = 0L
        for (i in pcm.indices) {
            val v = pcm[i].toLong()
            sumsq += v * v
            if (i >= window) {
                val out = pcm[i - window].toLong()
                sumsq -= out * out
            }
            if (sumsq > limit) {
                val start = (i - window + 1).coerceAtLeast(0)
                for (j in start..i) if (abs(pcm[j].toInt()) > threshold) return j
                return start
            }
        }
        return -1
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
