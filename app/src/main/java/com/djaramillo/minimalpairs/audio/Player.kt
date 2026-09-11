package com.djaramillo.minimalpairs.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock

/**
 * Plays [PreparedClip]s through pre-created static-mode AudioTracks so that
 * sound starts within a few tens of milliseconds of [play].
 *
 * Every clip gets its own `AudioTrack(MODE_STATIC)` at preparation time (PCM
 * written once); a replay is `stop()` + `reloadStaticData()` + `play()`. The
 * caller ([ClipCache]) bounds how many tracks exist at once and releases them
 * when a session ends; AudioFlinger allows a few dozen tracks per app, so the
 * cache keeps well under ten.
 *
 * Audio focus: the first [play] of a session takes transient focus with
 * ducking (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`), so music or a podcast in
 * another app is lowered instead of talked over; it is held until
 * [abandonFocus] (session end / abandon / app to background) and re-taken by
 * the next [play].
 */
class Player(context: Context) {
    /** A clip bound to its AudioTrack. */
    class Loaded internal constructor(val clip: PreparedClip, internal val track: AudioTrack) {
        @Volatile internal var released = false
    }

    private val audioManager: AudioManager? = context.applicationContext.getSystemService(AudioManager::class.java)

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @Volatile private var focusHeld = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // A permanent loss means another app took over: request again on the next play.
        // Clips are < 1 s, so nothing is paused; transient losses simply pass.
        if (change == AudioManager.AUDIOFOCUS_LOSS) focusHeld = false
    }

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener(focusListener)
        .build()

    /** Take audio focus (ducking other apps) if not already held. Cheap when held. */
    fun ensureFocus() {
        if (focusHeld) return
        val am = audioManager ?: return
        focusHeld = try {
            am.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: RuntimeException) {
            false
        }
    }

    /** Give audio focus back so other apps stop ducking. Safe to call when not held. */
    fun abandonFocus() {
        if (!focusHeld) return
        focusHeld = false
        try {
            audioManager?.abandonAudioFocusRequest(focusRequest)
        } catch (e: RuntimeException) {
            // nothing to do: focus is gone either way
        }
    }

    /** Create the track and write the PCM. Safe to call from any thread. */
    fun prepare(clip: PreparedClip): Loaded {
        val bytes = clip.pcm.size * 2
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(clip.sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bytes.coerceAtLeast(2))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        if (track.state == AudioTrack.STATE_UNINITIALIZED) {
            track.release()
            throw IllegalStateException("AudioTrack could not be initialised (${clip.sampleRate} Hz, $bytes bytes)")
        }
        val written = track.write(clip.pcm, 0, clip.pcm.size, AudioTrack.WRITE_BLOCKING)
        if (written < clip.pcm.size) {
            track.release()
            throw IllegalStateException("AudioTrack accepted $written of ${clip.pcm.size} samples")
        }
        return Loaded(clip, track)
    }

    /**
     * Start (or restart) playback. Returns `SystemClock.elapsedRealtime()` at
     * the moment `play()` was called: the audio onset used for reaction times.
     */
    fun play(loaded: Loaded): Long {
        val t = loaded.track
        check(!loaded.released) { "track released" }
        if (t.playState != AudioTrack.PLAYSTATE_STOPPED) t.stop()
        // Rewind the static buffer for a replay; harmless before the first play.
        t.reloadStaticData()
        ensureFocus()
        val at = SystemClock.elapsedRealtime()
        t.play()
        return at
    }

    fun stop(loaded: Loaded) {
        if (loaded.released) return
        try {
            if (loaded.track.playState != AudioTrack.PLAYSTATE_STOPPED) loaded.track.stop()
        } catch (e: IllegalStateException) {
            // already released by the system
        }
    }

    fun release(loaded: Loaded) {
        if (loaded.released) return
        loaded.released = true
        try { loaded.track.stop() } catch (e: IllegalStateException) { /* ignore */ }
        loaded.track.release()
    }

    companion object {
        /** Stream type used for volume keys while a session runs. */
        const val VOLUME_STREAM = AudioManager.STREAM_MUSIC
    }
}
