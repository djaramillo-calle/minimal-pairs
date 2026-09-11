package com.djaramillo.minimalpairs.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The end-of-utterance gate of the Say-it recorder (docs/DESIGN.md "Say it"):
 * a recording stops after [silenceMs] of quiet following at least
 * [minSpeechMs] of speech, or at [maxMs]. Speech is judged frame by frame on
 * RMS against thresholds relative to the noise floor measured over the first
 * [calibrationMs] (the room, the phone's hiss, the tap): a frame above
 * `onsetRatio × floor` (never below [minOnset], about −42 dBFS) starts or
 * continues speech; once speech has started, a frame above `holdRatio × floor`
 * still counts as speech so quiet word endings are not cut.
 *
 * The floor is the [FLOOR_PERCENTILE]-th percentile of the calibration frames,
 * not their mean, and is capped at [maxFloor]: a learner who taps and speaks at
 * once would otherwise raise the floor with their own voice and never be heard
 * over it. The percentile takes the quiet part of the calibration window when
 * there is one (speech that starts a few frames in), the cap takes over when
 * there is none (speech from the very first frame). Pure state machine (no
 * Android types): [Recorder] feeds it the frames it reads; tests feed synthetic
 * signals ([analyse]).
 */
class SpeechGate(
    private val sampleRate: Int,
    private val calibrationMs: Int = CALIBRATION_MS,
    private val minSpeechMs: Int = MIN_SPEECH_MS,
    private val silenceMs: Int = SILENCE_MS,
    private val maxMs: Int = MAX_MS,
    private val onsetRatio: Double = ONSET_RATIO,
    private val holdRatio: Double = HOLD_RATIO,
    private val minOnset: Double = MIN_ONSET,
    private val maxFloor: Double = MAX_FLOOR,
) {
    /** Audio fed so far, in ms (rounded per frame). */
    var elapsedMs: Int = 0
        private set

    /** Frames judged as speech so far, in ms. */
    var speechMs: Int = 0
        private set

    /** Quiet since the last speech frame, in ms (0 while nothing has been said yet). */
    var silenceRunMs: Int = 0
        private set

    /** The measured noise floor (RMS), `null` while still calibrating. */
    var floor: Double? = null
        private set

    /** RMS of the last frame, for a level meter. */
    var lastRms: Double = 0.0
        private set

    private val calibrationRms = ArrayList<Double>(16)

    /** The recording holds enough speech to be worth assessing (else "no speech"). */
    val hasSpeech: Boolean get() = speechMs >= DETECTED_MS

    /** The last frame was speech (the meter can show it). */
    val speaking: Boolean get() = speechMs > 0 && silenceRunMs == 0 && (floor != null)

    /** 0–1 level of the last frame relative to a comfortable speaking level, for the UI. */
    val level: Float get() = min(1.0, lastRms / LEVEL_FULL_SCALE).toFloat()

    /**
     * Feed the next frame ([n] samples of [frame]). Returns `true` when the
     * recording should stop now: [maxMs] reached, or speech of at least
     * [minSpeechMs] followed by [silenceMs] of quiet.
     */
    fun feed(frame: ShortArray, n: Int = frame.size): Boolean {
        val count = n.coerceIn(0, frame.size)
        if (count == 0) return elapsedMs >= maxMs
        val rms = rms(frame, count)
        lastRms = rms
        val ms = (count.toLong() * 1000 / sampleRate).toInt().coerceAtLeast(1)
        elapsedMs += ms
        val f = floor
        if (f == null) {
            calibrationRms.add(rms)
            if (elapsedMs >= calibrationMs) {
                floor = min(maxFloor, max(1.0, percentile(calibrationRms, FLOOR_PERCENTILE)))
            }
            return elapsedMs >= maxMs
        }
        val onset = max(f * onsetRatio, minOnset)
        val hold = max(f * holdRatio, minOnset / 2)
        val isSpeech = rms > (if (speechMs > 0) hold else onset)
        if (isSpeech) {
            speechMs += ms
            silenceRunMs = 0
        } else if (speechMs > 0) {
            silenceRunMs += ms
        }
        return elapsedMs >= maxMs || (speechMs >= minSpeechMs && silenceRunMs >= silenceMs)
    }

    /** Outcome of [analyse] on a whole signal. */
    data class Outcome(
        /** Where the gate would have stopped the recording, in ms of audio (the whole signal when it never fired). */
        val stopAtMs: Int,
        val speechMs: Int,
        val hasSpeech: Boolean,
        val floor: Double?,
    )

    companion object {
        const val CALIBRATION_MS = 150
        /** Speech needed before trailing silence may stop the recording. */
        const val MIN_SPEECH_MS = 200
        /** Quiet after speech that ends the recording. */
        const val SILENCE_MS = 600
        /** Hard cap on a recording. */
        const val MAX_MS = 3000
        /** Below this much speech the recording is reported as "no speech". */
        const val DETECTED_MS = 150
        const val ONSET_RATIO = 3.0
        const val HOLD_RATIO = 1.5
        /** Absolute onset floor, about −42 dBFS: digital silence must not make a whisper of hiss "speech". */
        const val MIN_ONSET = 250.0
        /**
         * The floor is never taken higher than this (≈ −36 dBFS), so speech
         * from about 1500 RMS (−27 dBFS) up registers even when the learner
         * spoke through the whole calibration window and there was no quiet
         * frame to measure.
         */
        const val MAX_FLOOR = 500.0
        /**
         * Percentile of the calibration frames taken as the noise floor: low
         * enough to ignore speech that starts inside the window, not the bare
         * minimum, so one dropped or clipped frame cannot put the floor at 0.
         */
        const val FLOOR_PERCENTILE = 20
        /** RMS shown as a full meter (≈ −14 dBFS). */
        const val LEVEL_FULL_SCALE = 6500.0
        /** Frame length the recorder reads and feeds. */
        const val FRAME_MS = 20

        /** The [p]-th percentile (0–100) of [values], nearest-rank on the sorted values. */
        fun percentile(values: List<Double>, p: Int): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val i = ((sorted.size - 1) * p / 100.0).toInt().coerceIn(0, sorted.size - 1)
            return sorted[i]
        }

        fun rms(frame: ShortArray, n: Int = frame.size): Double {
            if (n <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until n) {
                val v = frame[i].toDouble()
                sum += v * v
            }
            return sqrt(sum / n)
        }

        /** Run the gate over a whole signal in [FRAME_MS] frames (tests, offline checks). */
        fun analyse(pcm: ShortArray, sampleRate: Int, gate: SpeechGate = SpeechGate(sampleRate)): Outcome {
            val frame = (sampleRate.toLong() * FRAME_MS / 1000).toInt().coerceAtLeast(1)
            var i = 0
            var stopAt: Int? = null
            while (i < pcm.size) {
                val n = min(frame, pcm.size - i)
                val chunk = pcm.copyOfRange(i, i + n)
                i += n
                if (gate.feed(chunk, n)) { stopAt = gate.elapsedMs; break }
            }
            return Outcome(stopAt ?: gate.elapsedMs, gate.speechMs, gate.hasSpeech, gate.floor)
        }
    }
}
