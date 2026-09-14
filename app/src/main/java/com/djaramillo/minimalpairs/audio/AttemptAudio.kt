package com.djaramillo.minimalpairs.audio

import java.io.File

/**
 * The one step between the recording the coach gets and the bytes Azure takes.
 *
 * `sayit/attempts/<ts>_<id>.m4a` is AAC in an MP4 container, which is what the
 * coach's cloud pipeline wants and what the app has always written; the Speech
 * REST endpoint the phone now calls takes PCM16 WAV (or Ogg/Opus) and nothing
 * else, so the file is decoded and re-wrapped here rather than the recorder
 * being changed. The file on disk — the one that syncs — is untouched.
 *
 * The rate is read from the decoder, not assumed: [AttemptRecorder] asks for
 * 16 kHz and falls back to whatever the encoder chooses when it refuses, so
 * [Wav.forAssessment] resamples before wrapping and the header can never lie
 * about the audio inside it.
 *
 * Returns null rather than throwing: a recording the phone cannot decode is one
 * more reason for *no score*, and the coach still gets the recording.
 */
object AttemptAudio {

    /** The recording as PCM16 mono 16 kHz WAV, or null when it cannot be decoded. */
    fun wavForAssessment(file: File): ByteArray? {
        val decoded = try {
            if (!file.isFile || file.length() <= 0L) return null
            ClipDecoder.decode(file, trimSilence = false)
        } catch (e: Exception) {
            return null
        }
        if (decoded.pcm.isEmpty() || decoded.sampleRate <= 0) return null
        return try {
            Wav.forAssessment(decoded.pcm, decoded.sampleRate)
        } catch (e: OutOfMemoryError) {
            // 20 s of 16-bit audio is under a megabyte, so this is a phone
            // already in trouble; it is still not a reason to lose the take.
            null
        }
    }
}
