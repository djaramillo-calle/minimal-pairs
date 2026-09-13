package com.djaramillo.minimalpairs.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The Say-it microphone: one whole sentence per recording, written straight to
 * an AAC/MP4 file for the cloud to score.
 *
 * This is deliberately not [Recorder], which streams 16 kHz PCM16 for the
 * pairs drill's own Azure calls. Say-it never scores anything on the phone and
 * makes no network call (docs/CONTRACT.md, "`sayit/`"): the attempt is a file,
 * `sayit/attempts/<ts>_<id>.m4a`, that syncs to the coach. So we want a
 * container the coach can hand to Azure as it stands, which is what
 * `MediaRecorder` produces and `AudioRecord` does not.
 *
 * What the learner reads is the whole sentence, a few seconds of speech, so
 * there is no speech gate here and no auto-stop on silence: the learner taps to
 * stop, or [MAX_MS] ends it. A recording cut in half is worse than no recording
 * — the cloud would score the missing words as errors and the coach would act
 * on a number that means nothing — so every path that ends a recording early
 * ([cancel], audio-focus loss, a recorder error) deletes the partial file
 * instead of writing it.
 *
 * Threading: [start], [stop] and [cancel] arrive from the UI in any order and
 * as fast as a thumb can double-tap. A small state machine under [lock] decides
 * who owns the recorder; whoever claims it does the blocking `MediaRecorder`
 * work on `Dispatchers.IO`, and nobody else can touch it meanwhile. Callbacks
 * from the framework (info, error, focus) land on the main thread, so they only
 * claim the recorder and hand the teardown to [scope].
 */
class AttemptRecorder(context: Context) {
    private val app = context.applicationContext

    /** What one finished recording came to. */
    sealed class Outcome {
        /** A usable take: [file] holds [durationMs] of speech and may be written into `sayit/attempts/`. */
        data class Ok(val file: File, val durationMs: Long) : Outcome()

        /** Nothing usable was produced. [reason] is one short sentence, shown to the learner as it is. */
        data class Failed(val reason: String) : Outcome()

        /** A call, a focus loss or the app going away ended it; the partial file has been deleted. */
        data object Interrupted : Outcome()

        /** Under [MIN_MS], or no frame was captured at all (a start/stop double tap); the file is gone. */
        data object TooShort : Outcome()
    }

    private enum class State {
        /** No recorder exists. */
        IDLE,

        /** [start] is opening the recorder; it alone may finish or discard it. */
        STARTING,

        /** The microphone is live and the file is growing. */
        RECORDING,

        /** Somebody has claimed the recorder and is stopping or discarding it. */
        FINISHING,
    }

    private val lock = Any()
    private var state = State.IDLE
    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAt = 0L

    /**
     * Set when the recording is ended during [State.STARTING] — [cancel], or a
     * focus loss while the microphone is still opening. [start] owns the
     * recorder at that point, so it discards its own work and returns this as
     * its message.
     */
    private var abandonReason: String? = null

    /** An end that happened without the learner asking, reported by the next [stop]. */
    private var pending: Outcome? = null

    private val autoStoppedFlow = MutableStateFlow(false)
    private val interruptedFlow = MutableStateFlow(false)

    /**
     * True once [MAX_MS] was reached and the recorder stopped by itself. The
     * screen watches this so the button can return to "stopped" without the
     * learner tapping; the take is complete and the following [stop] returns
     * [Outcome.Ok]. Cleared by the next [start] and by the [stop] that collects it.
     */
    val autoStopped: StateFlow<Boolean> = autoStoppedFlow.asStateFlow()

    /**
     * True once the recording was ended for the learner — audio focus lost to a
     * call or another recorder, or the recorder itself failed. The partial file
     * is already deleted; the following [stop] returns [Outcome.Interrupted] or
     * [Outcome.Failed] so the screen can say why.
     */
    val interrupted: StateFlow<Boolean> = interruptedFlow.asStateFlow()

    /** Teardown asked for by a framework callback, which must not block the main thread. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val audioManager: AudioManager? = app.getSystemService(AudioManager::class.java)

    // Metadata for the focus request only: this class plays nothing.
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // A call, an alarm or another recorder took the microphone. Either loss
        // means the rest of the sentence is missing, so end here and throw the
        // fragment away rather than let the cloud score half a sentence.
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            endInBackground(Outcome.Interrupted)
        }
    }

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener)
            .build()

    @Volatile private var focusHeld = false

    /** Whether `RECORD_AUDIO` has been granted; [start] refuses politely without it. */
    fun hasPermission(): Boolean =
        app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** True while the microphone is live. False during start-up and teardown, so a tap never races the state machine. */
    val isRecording: Boolean get() = synchronized(lock) { state == State.RECORDING }

    /**
     * Open the microphone and record into [target], replacing whatever was
     * there. Returns `null` once the recorder is running, or one short
     * user-facing sentence when it could not be started — no permission, the
     * microphone busy, a codec the phone refuses. Never throws.
     */
    suspend fun start(target: File): String? {
        if (!hasPermission()) return "Allow the microphone to record your sentence."
        synchronized(lock) {
            if (state != State.IDLE) return "A recording is already running."
            state = State.STARTING
            this.target = target
            recorder = null
            pending = null
            abandonReason = null
        }
        autoStoppedFlow.value = false
        interruptedFlow.value = false
        return withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            deleteQuietly(target)
            if (!requestFocus()) {
                // Exclusive focus is normally granted at once; a refusal means a
                // call or another recorder already owns the microphone.
                settle(State.IDLE)
                return@withContext "Something else is using the microphone just now."
            }
            // Some encoders refuse an explicit 16 kHz AAC configuration and fail
            // in prepare(); the second attempt lets the encoder pick its own
            // rate and bitrate, which costs a slightly larger file and nothing else.
            var mr = open(target, tuned = true)
            if (mr == null) {
                deleteQuietly(target)
                mr = open(target, tuned = false)
            }
            if (mr == null) {
                abandonFocus()
                settle(State.IDLE)
                return@withContext "This phone would not start a recording."
            }
            try {
                mr.start()
            } catch (e: RuntimeException) {
                releaseQuietly(mr)
                deleteQuietly(target)
                abandonFocus()
                settle(State.IDLE)
                return@withContext "The microphone could not be opened."
            }
            val at = SystemClock.elapsedRealtime()
            val abandon = synchronized(lock) {
                val why = abandonReason
                if (why != null) {
                    state = State.FINISHING
                    this@AttemptRecorder.target = null
                    why
                } else {
                    recorder = mr
                    startedAt = at
                    state = State.RECORDING
                    null
                }
            }
            if (abandon != null) {
                // The app went away, or the microphone was taken, while it was opening.
                discard(mr, target)
                return@withContext abandon
            }
            null
        }
    }

    /**
     * End the recording and finish the file. Returns what it came to; safe to
     * call when nothing is running, and the place where an end that happened by
     * itself (maximum duration, focus loss, recorder error) is reported.
     *
     * The teardown runs `NonCancellable`: once the recorder has been claimed it
     * must be released whatever happens to the caller's scope, or the next
     * [start] meets a microphone the system still thinks is ours.
     */
    suspend fun stop(): Outcome {
        var mr: MediaRecorder? = null
        var file: File? = null
        var since = 0L
        val early = synchronized(lock) {
            val ready = pending
            if (ready != null) {
                pending = null
                ready
            } else if (state != State.RECORDING) {
                Outcome.Failed("Nothing was being recorded.")
            } else {
                state = State.FINISHING
                mr = recorder
                recorder = null
                file = target
                target = null
                since = startedAt
                null
            }
        }
        if (early != null) {
            clearSignals()
            return early
        }
        val recording = mr ?: return Outcome.Failed("Nothing was being recorded.")
        val out = file ?: return Outcome.Failed("Nothing was being recorded.")
        return withContext(Dispatchers.IO + NonCancellable) {
            val elapsed = SystemClock.elapsedRealtime() - since
            var captured = true
            try {
                quiet { recording.setOnInfoListener(null) }
                quiet { recording.setOnErrorListener(null) }
                recording.stop()
            } catch (e: RuntimeException) {
                // MediaRecorder.stop() throws when it is stopped before a single
                // frame was encoded — the start/stop double tap. There is no
                // valid MP4 on disk in that case.
                captured = false
            } finally {
                releaseQuietly(recording)
                abandonFocus()
                settle(State.IDLE)
                clearSignals()
            }
            if (!out.isFile || out.length() == 0L) {
                deleteQuietly(out)
                return@withContext Outcome.TooShort
            }
            val meta = durationOf(out)
            if (!captured && meta == null) {
                // stop() threw and the file is not a readable MP4: nothing was
                // encoded. (When [MAX_MS] ended the recording the recorder has
                // already finalised the file, so a throwing stop() there still
                // leaves a good take, which meta proves.)
                deleteQuietly(out)
                return@withContext Outcome.TooShort
            }
            val ms = meta ?: elapsed
            if (ms < MIN_MS) {
                deleteQuietly(out)
                return@withContext Outcome.TooShort
            }
            Outcome.Ok(out, ms)
        }
    }

    /**
     * Abandon a running recording and delete the partial file: called when the
     * app goes to the background or the screen turns off, where the sentence is
     * certainly unfinished. Returns at once — the blocking teardown runs on
     * [scope] — and never throws, whatever state the recorder is in.
     */
    fun cancel() {
        var mr: MediaRecorder? = null
        var file: File? = null
        val claimed = synchronized(lock) {
            when (state) {
                State.IDLE -> {
                    pending = null
                    false
                }
                // start() is mid-prepare and owns the recorder: it discards it itself.
                State.STARTING -> {
                    abandonReason = CANCELLED
                    false
                }
                // Somebody else is already tearing it down.
                State.FINISHING -> false
                State.RECORDING -> {
                    state = State.FINISHING
                    mr = recorder
                    recorder = null
                    file = target
                    target = null
                    pending = null
                    true
                }
            }
        }
        clearSignals()
        if (!claimed) return
        val recording = mr
        val out = file
        scope.launch { discard(recording, out) }
    }

    /**
     * Claim a running recorder from a framework callback and throw the take
     * away, leaving [outcome] for the next [stop]. Runs on the main thread, so
     * the teardown itself goes to [scope].
     */
    private fun endInBackground(outcome: Outcome) {
        var mr: MediaRecorder? = null
        var file: File? = null
        val claimed = synchronized(lock) {
            if (state == State.STARTING) {
                // The microphone is still opening; start() finishes the job.
                abandonReason = messageOf(outcome)
                interruptedFlow.value = true
                return
            }
            if (state != State.RECORDING) return
            state = State.FINISHING
            mr = recorder
            recorder = null
            file = target
            target = null
            pending = outcome
            true
        }
        if (!claimed) return
        interruptedFlow.value = true
        val recording = mr
        val out = file
        scope.launch { discard(recording, out) }
    }

    /**
     * Build and prepare a recorder writing to [target]. `tuned` asks for the
     * 16 kHz mono ~32 kbps the scorer likes; without it the encoder's own
     * defaults are used. Returns `null` when the phone refuses the settings.
     */
    @SuppressLint("MissingPermission")
    private fun open(target: File, tuned: Boolean): MediaRecorder? {
        val mr = newRecorder()
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioChannels(1)
            if (tuned) {
                mr.setAudioSamplingRate(SAMPLE_RATE)
                mr.setAudioEncodingBitRate(BIT_RATE)
            }
            mr.setMaxDuration(MAX_MS.toInt())
            mr.setOutputFile(target)
            mr.setOnInfoListener { who, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    // The recorder has stopped capturing and the file is whole;
                    // it still needs the stop() that the screen now makes.
                    val live = synchronized(lock) { state == State.RECORDING && recorder === who }
                    if (live) autoStoppedFlow.value = true
                }
            }
            mr.setOnErrorListener { who, _, _ ->
                val live = synchronized(lock) { state == State.RECORDING && recorder === who }
                if (live) endInBackground(Outcome.Failed("The recording failed part-way through."))
            }
            mr.prepare()
            return mr
        } catch (e: IOException) {
            releaseQuietly(mr)
            return null
        } catch (e: RuntimeException) {
            releaseQuietly(mr)
            return null
        }
    }

    /** `MediaRecorder()` without a context is deprecated from API 31; minSdk is 26, so both forms are kept. */
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(app)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    /** Stop, release and delete, in that order and without ever throwing. Leaves the machine idle. */
    private fun discard(mr: MediaRecorder?, file: File?) {
        if (mr != null) {
            quiet { mr.setOnInfoListener(null) }
            quiet { mr.setOnErrorListener(null) }
            quiet { mr.stop() }
            releaseQuietly(mr)
        }
        if (file != null) deleteQuietly(file)
        abandonFocus()
        settle(State.IDLE)
    }

    private fun settle(next: State) {
        synchronized(lock) {
            state = next
            if (next == State.IDLE) {
                recorder = null
                target = null
                abandonReason = null
            }
        }
    }

    /** What [start] says when a recording was ended before it ever ran. */
    private fun messageOf(outcome: Outcome): String = when (outcome) {
        is Outcome.Failed -> outcome.reason
        else -> "The recording was interrupted."
    }

    private fun clearSignals() {
        autoStoppedFlow.value = false
        interruptedFlow.value = false
    }

    private fun requestFocus(): Boolean {
        val am = audioManager ?: return true
        focusHeld = try {
            am.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: RuntimeException) {
            false
        }
        return focusHeld
    }

    /** Give the microphone back so other apps resume. Safe when nothing is held. */
    private fun abandonFocus() {
        if (!focusHeld) return
        focusHeld = false
        try {
            audioManager?.abandonAudioFocusRequest(focusRequest)
        } catch (e: RuntimeException) {
            // Focus is gone either way.
        }
    }

    /**
     * The recorded length as the file itself reports it, which is what the
     * sidecar's `duration_s` must say. `null` when the file cannot be read, and
     * the caller falls back to elapsed wall time.
     */
    private fun durationOf(file: File): Long? {
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(file.absolutePath)
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: RuntimeException) {
            null
        } finally {
            quiet { reader.release() }
        }
    }

    private fun releaseQuietly(mr: MediaRecorder) {
        quiet { mr.reset() }
        quiet { mr.release() }
    }

    private fun deleteQuietly(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (e: SecurityException) {
            // Nothing else to try; the sweep in DataFolder will not miss it either,
            // because an attempt without a sidecar is ignored by the coach.
        }
    }

    /** Run a teardown step that is allowed to fail because the object may already be dead. */
    private inline fun quiet(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            // Expected on an already-released or never-started recorder.
        } catch (e: IOException) {
            // MediaMetadataRetriever.release() declares it.
        }
    }

    companion object {
        /** A sentence is a few seconds; twenty is generous and stops a pocket recording forever. */
        const val MAX_MS = 20_000L

        /** Below this there is no sentence to score, only a tap. */
        const val MIN_MS = 400L

        /** [cancel] during start-up: the learner is no longer on the screen. */
        private const val CANCELLED = "The recording was cancelled."

        /** What the cloud scorer wants, when the encoder will give it. */
        private const val SAMPLE_RATE = 16_000

        /** About 32 kbps: speech-clean and a ~20 KB attempt, which syncs over anything. */
        private const val BIT_RATE = 32_000
    }
}
