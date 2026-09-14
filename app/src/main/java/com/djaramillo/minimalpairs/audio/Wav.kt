package com.djaramillo.minimalpairs.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

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

    /**
     * Low-pass cutoff as a fraction of the target rate when converting down.
     * 0.45 leaves the whole of the band speech assessment cares about and puts
     * the stopband under the new Nyquist.
     */
    const val CUTOFF = 0.45

    /**
     * Half-width of the resampling filter, in input samples. Ninety-seven taps
     * with a Hamming window put the transition band inside the ~800 Hz between
     * [CUTOFF] and Nyquist at the rates a phone actually records at, at about
     * thirty million multiply-adds for a twenty-second take — a fraction of a
     * second, and only when the encoder refused 16 kHz in the first place.
     */
    private const val HALF_TAPS = 48

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
     * Resample mono PCM to [to] Hz. Equal rates return the input untouched.
     *
     * The recorder asks for 16 kHz but not every encoder accepts an explicit
     * rate, and [AttemptRecorder] then lets it pick its own
     * ([AttemptRecorder.start]) — in practice 44.1 or 48 kHz from a full-band
     * microphone. A take like that sent to an endpoint told it is 16 kHz would
     * be scored as speech at a third of its proper speed, so the samples are
     * converted here rather than the header being relabelled.
     *
     * **Going down, the conversion filters first.** Picking every n-th sample —
     * which is what plain interpolation amounts to — folds everything above the
     * new 8 kHz Nyquist straight back into the speech band at nearly full
     * amplitude: a 12 kHz sibilant lands at 4 kHz, on top of the vowel. Azure
     * would score that faithfully, and the learner would be marked down for a
     * noise the phone made. This is the one path in the whole drill that could
     * produce a **wrong** score instead of no score, so the downward direction
     * goes through a windowed-sinc low-pass at [CUTOFF] of the target rate,
     * evaluated straight at the output positions ([interpolate]).
     *
     * Going up there is nothing to fold, so plain linear interpolation is used.
     */
    fun resample(pcm: ShortArray, from: Int, to: Int = SAMPLE_RATE): ShortArray {
        if (from <= 0 || to <= 0 || from == to || pcm.isEmpty()) return pcm
        val outSize = ((pcm.size.toLong() * to) / from).toInt().coerceAtLeast(1)
        val out = ShortArray(outSize)
        val step = from.toDouble() / to
        // Cutoff as cycles per INPUT sample: the band the output can carry.
        val fc = if (to < from) CUTOFF * to / from else 0.5
        val down = to < from
        for (i in 0 until outSize) {
            val pos = i * step
            val v = if (down) interpolate(pcm, pos, fc) else linear(pcm, pos)
            out[i] = v.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /**
     * One output sample, as a band-limited interpolation of the input around
     * [pos] (a fractional input index): a sinc at cutoff [fc] cycles per input
     * sample, under a Hamming window [HALF_TAPS] samples wide on each side. The
     * taps carry their own gain (`2·fc`), so a steady level comes through at
     * exactly its own level and nothing has to be renormalised per sample —
     * renormalising would make the gain depend on where the output sample falls
     * between two input samples, and that wobble puts back some of the
     * out-of-band energy the filter is here to remove.
     *
     * Where the window runs off the array the edge sample is repeated rather
     * than treated as silence, so the first and last syllables keep their level
     * instead of fading into a word Azure would read as mumbled.
     */
    private fun interpolate(pcm: ShortArray, pos: Double, fc: Double): Double {
        val centre = floor(pos).toInt()
        val last = pcm.size - 1
        var sum = 0.0
        for (j in -HALF_TAPS..HALF_TAPS) {
            val n = centre + j
            val t = pos - n
            if (abs(t) > HALF_TAPS) continue
            val h = 2.0 * fc * sinc(2.0 * fc * t) * hamming(t / HALF_TAPS)
            sum += pcm[n.coerceIn(0, last)] * h
        }
        return sum
    }

    /** Plain linear interpolation; used going up, where there is nothing to fold. */
    private fun linear(pcm: ShortArray, pos: Double): Double {
        val left = floor(pos).toInt()
        if (left >= pcm.size - 1) return pcm[pcm.size - 1].toDouble()
        if (left < 0) return pcm[0].toDouble()
        val frac = pos - left
        val a = pcm[left].toDouble()
        val b = pcm[left + 1].toDouble()
        return a + (b - a) * frac
    }

    /** `sin(pi x) / (pi x)`, and 1 at zero. */
    private fun sinc(x: Double): Double {
        if (x == 0.0) return 1.0
        val px = PI * x
        return sin(px) / px
    }

    /** Hamming window over [-1, 1]; zero outside. */
    private fun hamming(x: Double): Double =
        if (abs(x) > 1.0) 0.0 else 0.54 + 0.46 * cos(PI * x)

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
