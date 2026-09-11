package com.djaramillo.minimalpairs.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * The Say-it microphone: one `AudioRecord` per recording, 16 kHz mono PCM16
 * from `VOICE_RECOGNITION` (no telephony processing), read in 20 ms frames on
 * `Dispatchers.IO` and fed to a [SpeechGate], which ends the recording after
 * 600 ms of quiet following ≥ 200 ms of speech or at 3 s; [stop] ends it on
 * the learner's tap. The `AudioRecord` is stopped and released on every exit,
 * including coroutine cancellation (the learner leaves mid-recording: the
 * read blocks at most one frame, so cancellation lands within ~20 ms).
 *
 * Callers must hold `RECORD_AUDIO` ([hasPermission]); without it [record]
 * fails with an [IOException] instead of crashing.
 */
class Recorder(context: Context) {
    private val app = context.applicationContext

    /** One finished recording: PCM at [sampleRate], the gate's speech tally. */
    class Recording(val pcm: ShortArray, val sampleRate: Int, val speechMs: Int) {
        /** Length before padding: the row's `ms`. */
        val durationMs: Int get() = Pcm.durationMs(pcm.size, sampleRate)
        /** Enough speech to assess (docs/CONTRACT.md: a recording without speech may be redone once). */
        val hasSpeech: Boolean get() = speechMs >= SpeechGate.DETECTED_MS
        /** The learner's own voice as a clip for the existing [Player]. */
        fun toClip(): PreparedClip = PreparedClip(pcm, sampleRate)
        /** The bytes the Azure calls are sent (padded, WAV-wrapped). */
        fun toWav(): ByteArray = Wav.wrapPadded(pcm, sampleRate)
    }

    @Volatile private var stopRequested = false

    fun hasPermission(): Boolean =
        app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** End the running recording at the next frame (the learner tapped the button again). */
    fun stop() { stopRequested = true }

    /**
     * Record until the gate fires, [stop] is called or the coroutine is
     * cancelled. [onLevel] gets the gate's 0–1 level per frame (on the IO
     * thread) for a meter. Throws [IOException] when the microphone cannot be
     * opened (no permission, in use by a call, unsupported format).
     */
    suspend fun record(onLevel: ((level: Float, speaking: Boolean) -> Unit)? = null): Recording = withContext(Dispatchers.IO) {
        if (!hasPermission()) throw IOException("microphone permission not granted")
        stopRequested = false
        val gate = SpeechGate(SAMPLE_RATE)
        val frameSamples = SAMPLE_RATE * SpeechGate.FRAME_MS / 1000
        val maxSamples = SAMPLE_RATE * SpeechGate.MAX_MS / 1000
        val out = ShortArray(maxSamples + frameSamples)
        var size = 0
        val record = open(frameSamples)
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IOException("the microphone did not start (in use by another app?)")
            }
            val frame = ShortArray(frameSamples)
            while (size < maxSamples) {
                coroutineContext.ensureActive()
                if (stopRequested) break
                val n = record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (n < 0) throw IOException("microphone read failed ($n)")
                if (n == 0) continue
                frame.copyInto(out, size, 0, n)
                size += n
                val stop = gate.feed(frame, n)
                onLevel?.invoke(gate.level, gate.speaking)
                if (stop) break
            }
        } finally {
            try { record.stop() } catch (_: IllegalStateException) { /* never started */ }
            record.release()
        }
        Recording(out.copyOf(size), SAMPLE_RATE, gate.speechMs)
    }

    /** Lint cannot see [hasPermission] from here; [record] checks it first. */
    @SuppressLint("MissingPermission")
    private fun open(frameSamples: Int): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IOException("16 kHz mono recording is not supported on this device")
        // A second of buffer: the UI thread never starves the reader, and a GC pause does not drop frames.
        val bufferBytes = maxOf(minBuf, SAMPLE_RATE * 2, frameSamples * 2 * 4)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (e: SecurityException) {
            throw IOException("microphone permission not granted")
        } catch (e: IllegalArgumentException) {
            throw IOException("microphone could not be opened: ${e.message}")
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("microphone could not be opened")
        }
        return record
    }

    companion object {
        /** Azure Speech expects 16 kHz PCM16 mono. */
        const val SAMPLE_RATE = 16_000
    }
}
