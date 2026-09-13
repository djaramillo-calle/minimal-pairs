package com.djaramillo.minimalpairs.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Plays whole sentences from files: the coach's model clip and the learner's
 * own attempt, the two things the Say-it screen offers to listen to
 * (docs/CONTRACT.md, "`sayit/`").
 *
 * It is not [Player], which exists for the pairs drill's sub-second clips and
 * buys its latency with static `AudioTrack`s of decoded PCM. Here a "clip" is a
 * whole spoken sentence in one of two encoded formats — Ogg/Opus from
 * `sayit/clips/`, AAC/MP4 from `sayit/attempts/` — playing on a deliberate tap
 * where a few tens of milliseconds do not matter. `MediaPlayer` decodes both
 * without the app owning a decoder.
 *
 * [play] suspends until the sentence has been heard to the end, so a caller can
 * simply await it before enabling the record button, and [playAll] chains
 * sentences back to back for a model-then-attempt comparison. The continuation
 * is resumed exactly once on every path — completion, error, a superseding
 * [play], [stop], [release] and coroutine cancellation — because a caller left
 * waiting for ever would freeze the drill.
 *
 * A model clip the phone cannot decode is a message, never a crash: the
 * contract says a word with an unplayable clip is still practised, with Play
 * model disabled and saying so.
 */
class SentencePlayer(context: Context) {
    private val app = context.applicationContext

    private val audioManager: AudioManager? = app.getSystemService(AudioManager::class.java)

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // A permanent loss means another app took over; the next play asks again.
        // A sentence is a few seconds, so a transient loss simply passes.
        if (change == AudioManager.AUDIOFOCUS_LOSS) focusHeld = false
    }

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener)
            .build()

    @Volatile private var focusHeld = false

    private val lock = Any()
    private var current: Playback? = null
    private var closed = false

    /**
     * One `MediaPlayer` and the caller waiting on it. Ending and closing are
     * separate and both idempotent, so whoever gets there first — the
     * completion callback, [stop], a replacing [play], cancellation — settles
     * the wait, and the rest are no-ops.
     */
    private class Playback(val player: MediaPlayer) {
        private val lock = Any()
        private var waiter: CancellableContinuation<String?>? = null
        private var ended = false
        private var message: String? = null
        private var closed = false

        /** Hand the waiting coroutine over; resumes at once when playback already ended. */
        fun attach(cont: CancellableContinuation<String?>) {
            var done = false
            var msg: String? = null
            synchronized(lock) {
                if (ended) {
                    done = true
                    msg = message
                } else {
                    waiter = cont
                }
            }
            if (done && cont.isActive) cont.resume(msg)
        }

        /** Settle the wait with [msg] (`null` means "played, nothing to report"). Only the first call counts. */
        fun end(msg: String?) {
            var cont: CancellableContinuation<String?>? = null
            synchronized(lock) {
                if (ended) return
                ended = true
                message = msg
                cont = waiter
                waiter = null
            }
            val c = cont
            if (c != null && c.isActive) c.resume(msg)
        }

        /** Stop and release the player. Safe twice, safe from any thread, never throws. */
        fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
            }
            quiet { player.setOnCompletionListener(null) }
            quiet { player.setOnErrorListener(null) }
            quiet { if (player.isPlaying) player.stop() }
            quiet { player.reset() }
            quiet { player.release() }
        }

        private inline fun quiet(block: () -> Unit) {
            try {
                block()
            } catch (e: IllegalStateException) {
                // The player is already released or was never prepared.
            } catch (e: RuntimeException) {
                // Same: a dead MediaPlayer is allowed to complain.
            }
        }
    }

    /**
     * Play [file] to its end. Returns `null` when it was heard through (or was
     * deliberately stopped), or one short user-facing sentence when it could
     * not be played. Never throws.
     */
    suspend fun play(file: File): String? {
        ensureFocus()
        try {
            return playOne(file)
        } finally {
            abandonFocus()
        }
    }

    /**
     * Play [files] back to back, awaiting each one, and stop at the first
     * failure — which is what the screen wants for "model, then your attempt":
     * if the model will not play there is no point playing the rest. Focus is
     * taken once for the whole run so other apps duck once instead of flickering.
     */
    suspend fun playAll(files: List<File>): String? {
        if (files.isEmpty()) return null
        ensureFocus()
        try {
            for (file in files) {
                val failure = playOne(file)
                if (failure != null) return failure
            }
            return null
        } finally {
            abandonFocus()
        }
    }

    /** One sentence, without touching audio focus; [play] and [playAll] own that. */
    private suspend fun playOne(file: File): String? {
        if (synchronized(lock) { closed }) return CLOSED
        if (!file.isFile || file.length() == 0L) return MISSING
        val player = withContext(Dispatchers.IO) { open(file) } ?: return UNPLAYABLE
        val playback = Playback(player)
        if (!install(playback)) {
            playback.close()
            return CLOSED
        }
        try {
            return suspendCancellableCoroutine { cont ->
                player.setOnCompletionListener { finish(playback, null) }
                player.setOnErrorListener { _, _, _ ->
                    // Returning true: the error is handled here, so MediaPlayer
                    // does not also fire completion and no second resume follows.
                    finish(playback, UNPLAYABLE)
                    true
                }
                cont.invokeOnCancellation {
                    // The learner left the word, or the screen went away: silence
                    // the sentence at once rather than at its end.
                    finish(playback, null)
                }
                playback.attach(cont)
                try {
                    player.start()
                } catch (e: IllegalStateException) {
                    finish(playback, UNPLAYABLE)
                }
            }
        } finally {
            finish(playback, null)
        }
    }

    /** Stop whatever is playing. The waiting [play] returns `null`. Safe at any time, from any thread. */
    fun stop() {
        val playing = synchronized(lock) { current }
        if (playing != null) finish(playing, null)
    }

    /**
     * Stop and refuse further playback: call it when the Say-it screen is
     * finished with. Safe twice and safe while a [play] is in flight, which
     * returns immediately.
     */
    fun release() {
        val playing = synchronized(lock) {
            closed = true
            current
        }
        if (playing != null) finish(playing, null)
        abandonFocus()
    }

    /** Settle the wait, release the player and forget it. Idempotent by [Playback]. */
    private fun finish(playback: Playback, message: String?) {
        playback.end(message)
        playback.close()
        synchronized(lock) {
            if (current === playback) current = null
        }
    }

    /**
     * Make [playback] the one that is playing, ending and releasing whatever it
     * replaces, so two rapid taps can never leave a `MediaPlayer` running or
     * leaked. False when [release] has already been called.
     */
    private fun install(playback: Playback): Boolean {
        var previous: Playback? = null
        val ok = synchronized(lock) {
            if (closed) {
                false
            } else {
                previous = current
                current = playback
                true
            }
        }
        val old = previous
        if (old != null) finish(old, null)
        return ok
    }

    /**
     * Open and prepare [file]. A file descriptor rather than a path: the model
     * clip is a cache copy of `sayit/clips/<id>.ogg` and the attempt lives in
     * the app's own files, and the descriptor works for both without the
     * player needing its own access to the path. `null` when the format cannot
     * be decoded — Ogg/Opus, for one, needs Android 10 or newer.
     */
    private fun open(file: File): MediaPlayer? {
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(attributes)
            FileInputStream(file).use { input ->
                player.setDataSource(input.fd)
            }
            player.prepare()
            player
        } catch (e: IOException) {
            releaseQuietly(player)
            null
        } catch (e: RuntimeException) {
            releaseQuietly(player)
            null
        }
    }

    private fun releaseQuietly(player: MediaPlayer) {
        try {
            player.reset()
            player.release()
        } catch (e: RuntimeException) {
            // Nothing left to do with a player that will not even reset.
        }
    }

    /** Take transient focus with ducking, so music elsewhere is lowered, not talked over. Cheap when held. */
    private fun ensureFocus() {
        if (focusHeld) return
        val am = audioManager ?: return
        focusHeld = try {
            am.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: RuntimeException) {
            false
        }
    }

    /** Give focus back so other apps stop ducking. Safe when nothing is held. */
    private fun abandonFocus() {
        if (!focusHeld) return
        focusHeld = false
        try {
            audioManager?.abandonAudioFocusRequest(focusRequest)
        } catch (e: RuntimeException) {
            // Focus is gone either way.
        }
    }

    private companion object {
        const val MISSING = "That recording is no longer on the phone."
        const val UNPLAYABLE = "This clip could not be played."
        const val CLOSED = "The player has been closed."
    }
}
