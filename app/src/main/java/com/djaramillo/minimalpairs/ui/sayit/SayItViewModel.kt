package com.djaramillo.minimalpairs.ui.sayit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.djaramillo.minimalpairs.AppContainer
import com.djaramillo.minimalpairs.audio.AttemptAudio
import com.djaramillo.minimalpairs.audio.AttemptRecorder
import com.djaramillo.minimalpairs.clips.RenderPlan
import com.djaramillo.minimalpairs.domain.SayItNames
import com.djaramillo.minimalpairs.domain.SayItPlanner
import com.djaramillo.minimalpairs.domain.SayItScoring
import com.djaramillo.minimalpairs.domain.TimeUtil
import com.djaramillo.minimalpairs.domain.model.AttemptSidecar
import com.djaramillo.minimalpairs.domain.model.SayItResults
import com.djaramillo.minimalpairs.domain.model.SayItWord
import com.djaramillo.minimalpairs.speech.AzureAssessor
import com.djaramillo.minimalpairs.storage.DataFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

/** Which half of the comparison is sounding, so the screen can point at it. */
enum class ComparePart { NONE, ATTEMPT, MODEL }

/**
 * The one-line complaints the sentence screen can show. They are codes rather
 * than sentences because every user-facing string of this mode lives in
 * `strings.xml`; only [SayItNotice.detail] carries text, and that text is the
 * short sentence the audio layer already wrote for the learner.
 */
enum class SayItTrouble {
    /** The microphone would not start, or failed part-way through. */
    RECORDER,

    /** Under the recorder's minimum: nothing worth sending to the coach. */
    TOO_SHORT,

    /** A call, a focus loss or the app going away ended it; the partial file is gone. */
    INTERRUPTED,

    /** The recording could not be written into the synced folder. */
    NOT_SAVED,

    /** A clip or an attempt could not be played. */
    PLAYBACK,
}

/** One thing that went wrong, with the audio layer's own wording when it has any. */
data class SayItNotice(val trouble: SayItTrouble, val detail: String? = null)

/**
 * What became of one word's attempt in **this** sitting: the numbers Azure gave
 * it on the phone, or the reason it has none and waits for the coach. Exactly
 * one of [assessment] and [note] is set.
 */
data class SayItTodayScore(
    val assessment: SayItScoring.Assessment? = null,
    val note: SayItScoring.Unscored? = null,
    /** Its `sayit/scores/<ts>_<id>.json` reached the folder. */
    val saved: Boolean = false,
    /** The attempt stem this belongs to, so a slow first take cannot overwrite a fast second one. */
    val stem: String = "",
)

/**
 * One line of the end-of-run summary: what was practised and the last score the
 * cloud coach already had for it.
 *
 * [lastScore] is whatever `results.json` held when the run opened — the coach's
 * history, which the app never writes. Today's own score is **not** copied in
 * here: an assessment outlives the word it belongs to, so the last word of a
 * run is routinely still being scored when this page is built. It is looked up
 * from [SayItSentenceUi.todayScores] as the page draws, which is a live map,
 * and appears the moment Azure answers.
 */
data class SayItDoneRow(
    val id: String,
    val word: String,
    val sentence: String,
    /** A recording of this word reached the folder during this run. */
    val recorded: Boolean,
    val lastScore: Double?,
)

/**
 * Everything the Say-it sentence screens draw. [words] is the run itself —
 * active words only, already ordered and cut to `per_session` by
 * [SayItPlanner.select] — and [tutor] the words a human should hear, which are
 * outside that count and are listed on the done page rather than drilled.
 */
data class SayItSentenceUi(
    val loading: Boolean = true,
    /** Non-null when the synced folder cannot be used; the mode then only says so. */
    val folderProblem: DataFolder.Status? = null,
    val words: List<SayItWord> = emptyList(),
    val index: Int = 0,
    val tutor: List<SayItWord> = emptyList(),
    /** `words.json` (or `results.json`) is there but unusable; shown on the empty page. */
    val listMessage: String? = null,
    /** The model clip has been copied out of the folder and can be played. */
    val clipReady: Boolean = false,
    /** The copy is still running; Play model waits rather than saying "no clip". */
    val clipBusy: Boolean = false,
    val recording: Boolean = false,
    /** The attempt is being written into `sayit/attempts/`. */
    val saving: Boolean = false,
    /** There is a recording of this word to compare with the model. */
    val attempt: Boolean = false,
    /** That recording reached the folder; false while it only exists in the cache. */
    val attemptSaved: Boolean = false,
    val playing: ComparePart = ComparePart.NONE,
    /** The attempt just written is being scored on the phone. */
    val scoring: Boolean = false,
    /** Today's score for the current word, when Azure returned one. */
    val score: SayItScoring.Assessment? = null,
    /** Why the current word's attempt has no score and waits for the coach. */
    val scoreNote: SayItScoring.Unscored? = null,
    /** The score file reached `sayit/scores/`; false when there is a score that could not be written. */
    val scoreSaved: Boolean = false,
    /**
     * This sitting's scores so far, by word id, for the done page. It is
     * refreshed as each assessment lands rather than snapshotted when the page
     * is built, because the last word of a run is normally still being scored
     * at that moment.
     */
    val todayScores: Map<String, SayItTodayScore> = emptyMap(),
    /** `RECORD_AUDIO` was refused: the mode degrades to reading along. */
    val micDenied: Boolean = false,
    val notice: SayItNotice? = null,
    val done: Boolean = false,
    val rows: List<SayItDoneRow> = emptyList(),
    /** Attempt audio removed by the 30-day sweep at the start of this run; null when it could not run. */
    val swept: Int? = null,
) {
    val word: SayItWord? get() = words.getOrNull(index)
    val total: Int get() = words.size
    /** 1-based, for "Word 2 of 5". */
    val position: Int get() = index + 1
    val last: Boolean get() = index >= words.size - 1
    /** Nothing to drill: the mode opens on the short page that says so. */
    val empty: Boolean get() = !loading && words.isEmpty()

    /**
     * This run is over and re-entering the mode must start a fresh one: it
     * finished, there was nothing to drill, or the folder was unusable. The
     * ViewModel is scoped to the activity, so without this the summary of the
     * last sitting would still be on screen the next time Say it is opened,
     * and a `sayit.zip` that arrived meanwhile would never be read. A run in
     * progress is deliberately not stale — re-entry also happens after a
     * rotation, which must not throw a sitting away.
     */
    val stale: Boolean get() = !loading && (done || empty || folderProblem != null)
}

/**
 * What the last retention sweep removed, so Settings can state the rule and
 * what it cost without running the sweep a second time. Process-wide and
 * deliberately tiny: the sweep happens once when the mode opens, in
 * [SayItViewModel], and Settings is drawn by a different ViewModel.
 */
object SayItSweep {
    @Volatile
    private var removed: Int? = null

    /** The count of a sweep that ran; a null (folder unavailable) leaves the last real figure alone. */
    fun record(files: Int?) {
        if (files != null) removed = files
    }

    /** Files the last sweep of this process removed, or null when none has run. */
    fun lastRemoved(): Int? = removed
}

/**
 * The Say-it sentence drill (docs/CONTRACT.md, "`sayit/` — Say it, the sentence
 * drill"): play the coach's model of a sentence, read it aloud, hear the two
 * back to back, and leave the recording in the synced folder for the cloud to
 * score.
 *
 * Two rules shape all of it.
 *
 * The unit is the word **in its sentence**. The [SayItWord] read from
 * `words.json` is carried through to [AttemptSidecar], to the reference text
 * Azure is given and to the score file untouched — every one of them scores or
 * describes that exact string, and a trimmed or re-wrapped one would silently
 * ruin the result.
 *
 * And a score is either real or absent. When the phone can assess the attempt —
 * a key is set, the network is there, Azure answers — it does so at once and
 * writes one immutable `sayit/scores/<ts>_<id>.json` beside the recording
 * (docs/CONTRACT.md). When it cannot, the recording and the sidecar are kept
 * exactly as before, **no** score file is written, and the screen says the
 * attempt was saved and the coach will score it at the next sync. Nothing here
 * ever estimates a number locally.
 *
 * It is strictly read-only on the coach's files. The only things it writes are
 * its own `sayit/attempts/` pair per recording, a score file for an attempt
 * that was scored, and the 30-day retention sweep it runs once when the mode
 * opens. `sayit.zip` — and the `words.json` and `results.json` inside it — is
 * the coach's.
 */
class SayItViewModel(application: Application) : AndroidViewModel(application) {
    private val container = AppContainer.get(application)
    private val folder = container.folder
    private val recorder = container.attemptRecorder
    private val player = container.sentencePlayer
    private val pack = container.sayItPack

    private val _ui = MutableStateFlow(SayItSentenceUi())
    val ui: StateFlow<SayItSentenceUi> = _ui

    /** `results.json` as it stood when the run opened: the source of every score shown. */
    private var results: SayItResults? = null

    /** The model clip of the current word, copied into the app's cache; null when there is none. */
    private var modelClip: File? = null

    /** The current recording, in the app's own cache until the word is left behind. */
    private var attemptFile: File? = null
    private var startedAt: Instant? = null

    /**
     * Model plays for the current word so far. The sidecar's `clip_played`
     * counts only the plays that happened **before** the recording finished, so
     * it stops rising once the attempt has been written, and it starts again at
     * zero for the next word.
     */
    private var clipPlayed = 0
    private var attemptWritten = false

    /** Words whose recording reached the folder in this run; the done page marks them. */
    private val recorded = LinkedHashSet<String>()

    /**
     * This sitting's own scores, keyed by word id. Written from the scoring job,
     * which runs outside [viewModelScope] so that a finished assessment still
     * reaches `sayit/scores/`, and read from the main thread when the screen
     * moves between words — hence a concurrent map rather than a plain one.
     */
    private val todayScores = ConcurrentHashMap<String, SayItTodayScore>()

    /**
     * The newest attempt stem per word. A second take can be recorded while the
     * first is still being assessed, and the first must not then overwrite the
     * second's result on screen.
     */
    private val latestStem = ConcurrentHashMap<String, String>()

    /**
     * Which sitting is on screen. An assessment deliberately outlives the word
     * and the screen, so a learner who finishes a run and opens Say it again
     * can be inside a **new** run of the same five words when the old run's
     * answer lands. Without this the old score would be filed against the new
     * sitting's word and shown as today's reading of a sentence he has not read
     * yet. The score file itself is still written — it describes a real
     * attempt — only the in-memory result is dropped.
     */
    @Volatile
    private var run = 0

    private var playJob: Job? = null
    private var recordJob: Job? = null
    private var clipJob: Job? = null

    /**
     * The stop-and-write coroutine, from the tap that ends a recording until
     * the attempt is in `sayit/attempts/`. It is kept because the cache file it
     * is copying from must outlive it: see [clearAttempt].
     */
    private var saveJob: Job? = null

    /** Set between the tap that stops and the outcome, so a double tap cannot stop twice. */
    private var stopping = false

    init {
        viewModelScope.launch { open() }
        // The recorder stops itself at MAX_MS and when something takes the
        // microphone. Both leave a take waiting to be collected, so the screen
        // does not need the learner to tap a button that is no longer true.
        viewModelScope.launch {
            recorder.autoStopped.collect { if (it && _ui.value.recording) stopRecording() }
        }
        viewModelScope.launch {
            recorder.interrupted.collect { if (it && _ui.value.recording) stopRecording() }
        }
    }

    // ---- opening the mode -------------------------------------------------

    /**
     * The mode has been entered. This ViewModel is scoped to the activity, so
     * the same instance is handed back every time Say it is opened: a run that
     * is over is thrown away here and the folder read again, or the mode would
     * be a dead end after one sitting and would never see a `sayit.zip` that
     * DriveSync delivered while the app was open (docs/CONTRACT.md). A run
     * still in progress is left exactly as it was, because this also fires
     * when the screen is rebuilt after a rotation.
     */
    fun onEnter() {
        if (!_ui.value.stale) return
        endWord()
        run++
        recorded.clear()
        todayScores.clear()
        latestStem.clear()
        results = null
        _ui.value = SayItSentenceUi()
        viewModelScope.launch { open() }
    }

    /**
     * Read the folder once: the status, the coach's two files, the run and the
     * tutor list. Nothing here invents a word — an absent, empty or unparsable
     * `words.json` simply leaves [SayItSentenceUi.words] empty and the screen
     * says what produces it.
     */
    private suspend fun open() {
        val status = folder.status()
        val problem = if (status is DataFolder.Status.Ok) null else status
        // One read of sayit.zip: unpacked into private storage when it has
        // changed, reused as it stands when it has not (docs/CONTRACT.md).
        val payload = pack.refresh(folder)
        results = payload.results
        _ui.value = _ui.value.copy(
            loading = false,
            folderProblem = problem,
            words = SayItPlanner.select(payload.words, results),
            tutor = SayItPlanner.tutor(payload.words, results),
            listMessage = payload.message,
        )
        // The retention sweep is per run, not per word, and nothing waits for it.
        viewModelScope.launch {
            val files = folder.sweepSayItAttempts(results, Instant.now())
            SayItSweep.record(files)
            _ui.value = _ui.value.copy(swept = files)
        }
        prepareClip()
    }

    /**
     * Point the player at the current word's model clip, which the unpacked
     * `sayit.zip` already holds as an ordinary file.
     *
     * A word whose `clip` is empty, points outside `clips/`, or simply did not
     * arrive is still drilled: the sentence, the recording and the comparison
     * all work, and only Play model is off. That is the contract's rule — the
     * coach writes an empty `clip` for a word it could not render, and the app
     * must never offer a dead button.
     */
    private fun prepareClip() {
        clipJob?.cancel()
        val word = _ui.value.word
        modelClip = if (word == null) null else pack.clip(word.clip)
        _ui.value = _ui.value.copy(clipReady = modelClip != null, clipBusy = false)
    }

    // ---- listening --------------------------------------------------------

    /**
     * Play the coach's model. Replayable as often as he likes; each play before
     * the recording is written counts towards the sidecar's `clip_played`,
     * which is how the coach knows whether the sentence was read cold or after
     * three listens.
     */
    fun onPlayModel() {
        val file = modelClip ?: return
        if (_ui.value.recording) return
        if (!attemptWritten) clipPlayed++
        startPlay(listOf(ComparePart.MODEL to file))
    }

    /**
     * His attempt and then the model, back to back. This comparison is the only
     * immediate feedback the app can honestly give — everything else waits for
     * the cloud — so it runs by itself as soon as a recording is finished, and
     * again whenever he asks.
     */
    fun onCompare() {
        val attempt = attemptFile ?: return
        val model = modelClip
        val parts = ArrayList<Pair<ComparePart, File>>(2)
        parts.add(ComparePart.ATTEMPT to attempt)
        if (model != null) parts.add(ComparePart.MODEL to model)
        startPlay(parts)
    }

    /** Play the given files in order, showing which is sounding; stops at the first failure. */
    private fun startPlay(parts: List<Pair<ComparePart, File>>) {
        playJob?.cancel()
        player.stop()
        playJob = viewModelScope.launch {
            try {
                for ((part, file) in parts) {
                    // The notice is left alone: the comparison starts by itself
                    // right after a write that may have failed, and "this was
                    // not saved" must not be wiped out by the playback that
                    // follows it.
                    _ui.value = _ui.value.copy(playing = part)
                    val problem = player.play(file)
                    if (problem != null) {
                        _ui.value = _ui.value.copy(notice = SayItNotice(SayItTrouble.PLAYBACK, problem))
                        break
                    }
                }
            } finally {
                _ui.value = _ui.value.copy(playing = ComparePart.NONE)
            }
        }
    }

    // ---- recording --------------------------------------------------------

    /** Whether `RECORD_AUDIO` is granted; the route asks before the first recording. */
    fun hasMicPermission(): Boolean = recorder.hasPermission()

    /**
     * The answer to the system prompt. A grant starts the recording he asked
     * for; a refusal degrades the mode rather than ending it — he can still
     * play the model, read along and reach the done page.
     */
    fun onMicPermission(granted: Boolean) {
        _ui.value = _ui.value.copy(micDenied = !granted)
        if (granted) onRecordTapped()
    }

    /** One control: press to start, press again to stop. */
    fun onRecordTapped() {
        if (_ui.value.recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val current = _ui.value
        if (current.folderProblem != null || current.word == null || current.saving) return
        // The microphone takes tens of milliseconds to open and the button still
        // reads "Record" meanwhile, so a second tap is easy. The coroutine
        // already running owns that recorder — cancelling it here would leave a
        // live MediaRecorder holding the microphone with nobody able to stop it.
        if (recordJob?.isActive == true) return
        if (!recorder.hasPermission()) {
            _ui.value = current.copy(micDenied = true)
            return
        }
        playJob?.cancel()
        player.stop()
        recordJob?.cancel()
        recordJob = viewModelScope.launch {
            clearAttempt()
            // A private cache file: nothing reaches the synced folder until the
            // take is known to be good, so a half recording never syncs.
            val target = withContext(Dispatchers.IO) {
                val dir = cacheDir()
                dir.mkdirs()
                File(dir, ATTEMPT_FILE).also { it.delete() }
            }
            val began = Instant.now()
            val problem = recorder.start(target)
            if (problem != null) {
                _ui.value = _ui.value.copy(notice = SayItNotice(SayItTrouble.RECORDER, problem))
                return@launch
            }
            attemptFile = target
            startedAt = began
            stopping = false
            // A new take supersedes the last one's verdict: whatever is on
            // screen describes a recording that is about to be replaced. The
            // empty stem marks "a newer take is coming", so an assessment of
            // the old one that lands first is dropped rather than shown
            // against the new recording.
            current.word?.let {
                todayScores.remove(it.id)
                latestStem[it.id] = ""
            }
            _ui.value = _ui.value.copy(
                recording = true,
                notice = null,
                playing = ComparePart.NONE,
                micDenied = false,
                scoring = false,
                score = null,
                scoreNote = null,
                scoreSaved = false,
            )
        }
    }

    private fun stopRecording() {
        if (stopping) return
        stopping = true
        // The word, when it began and how often the model was played are read
        // here, before anything suspends. They describe the take that was just
        // made, and the fields they come from belong to the current word, which
        // is put down the moment he moves on (docs/CONTRACT.md: the sidecar
        // must match the audio, not whatever the screen shows when the write
        // finally lands).
        val word = _ui.value.word
        val began = startedAt
        val plays = clipPlayed
        _ui.value = _ui.value.copy(recording = false, saving = true)
        saveJob = viewModelScope.launch {
            when (val outcome = recorder.stop()) {
                is AttemptRecorder.Outcome.Ok ->
                    if (word == null) fail(SayItNotice(SayItTrouble.NOT_SAVED)) else save(outcome, word, began, plays)
                is AttemptRecorder.Outcome.Failed -> fail(SayItNotice(SayItTrouble.RECORDER, outcome.reason))
                AttemptRecorder.Outcome.Interrupted -> fail(SayItNotice(SayItTrouble.INTERRUPTED))
                AttemptRecorder.Outcome.TooShort -> fail(SayItNotice(SayItTrouble.TOO_SHORT))
            }
            stopping = false
            _ui.value = _ui.value.copy(saving = false)
        }
    }

    private fun fail(notice: SayItNotice) {
        clearAttempt()
        _ui.value = _ui.value.copy(notice = notice)
    }

    /**
     * Write the take into `sayit/attempts/`: the audio first and the sidecar
     * second, both under a stem the folder has confirmed is free, so the name
     * and `started` can never disagree (docs/CONTRACT.md).
     *
     * The sentence comes from the [SayItWord] this screen was built from, never
     * from anything the screen laid out, because the cloud scores the audio
     * against that exact string. [word], [begunAt] and [clipPlays] are the
     * snapshot [stopRecording] took before it suspended, for the same reason.
     */
    private suspend fun save(
        take: AttemptRecorder.Outcome.Ok,
        word: SayItWord,
        begunAt: Instant?,
        clipPlays: Int,
    ) {
        val began = (begunAt ?: Instant.now()).truncatedTo(ChronoUnit.SECONDS)
        val at = folder.freeAttemptStart(word.id, began)
        val sidecar = AttemptSidecar(
            id = word.id,
            word = word.word,
            sentence = word.sentence,
            started = TimeUtil.formatIso(at),
            // Clamped: the take is measured as wall time, so the stop latency after
            // MAX_MS can push it a tenth over the cap docs/CONTRACT.md states and
            // scripts/validate-contract.py enforces.
            durationS = (take.durationMs.coerceAtMost(AttemptRecorder.MAX_MS) / 100.0).roundToLong() / 10.0,
            appVersion = container.appVersion,
            clipPlayed = clipPlays,
        )
        val stem = folder.writeSayItAttempt(take.file, sidecar)
        attemptWritten = stem != null
        if (stem != null) recorded.add(word.id)
        _ui.value = _ui.value.copy(
            attempt = true,
            attemptSaved = stem != null,
            notice = if (stem == null) SayItNotice(SayItTrouble.NOT_SAVED) else null,
        )
        // The comparison is the point of the mode, so it starts by itself.
        onCompare()
        // Scoring comes after the attempt is safely in the folder and never
        // instead of it. An attempt that did not land is not scored and shows
        // no score card at all: the record row already says it is not in the
        // folder, and "saved, the coach will score it" would be a lie.
        if (stem != null) startScoring(word, stem, take.file, sidecar.started)
    }

    // ---- scoring on the phone ---------------------------------------------

    /**
     * Assess the attempt just written, with the learner's own phone-only Azure
     * Speech resource (docs/CONTRACT.md, "`sayit/scores/`").
     *
     * The recording is decoded here, inside the write coroutine, because the
     * cache file it reads is deleted the moment the word is left behind and
     * [clearAttempt] waits only for that coroutine. The request itself runs in
     * the container's scope so that an assessment in flight still finishes — and
     * still writes its file — when the learner moves on or the screen goes away.
     *
     * Every early return is a *no score*: the recording and the sidecar stay,
     * nothing is written, and the screen says the coach will score it.
     */
    private suspend fun startScoring(word: SayItWord, stem: String, audio: File, startedIso: String) {
        val forRun = run
        latestStem[word.id] = stem
        val credentials = withContext(Dispatchers.IO) { azureCredentials() }
        if (credentials == null) {
            publish(word.id, SayItTodayScore(note = SayItScoring.Unscored.NO_KEY, stem = stem), false, forRun)
            return
        }
        val wav = withContext(Dispatchers.IO) { AttemptAudio.wavForAssessment(audio) }
        if (wav == null) {
            publish(word.id, SayItTodayScore(note = SayItScoring.Unscored.NO_AUDIO, stem = stem), false, forRun)
            return
        }
        publish(word.id, null, true, forRun)
        val (region, key) = credentials
        container.scope.launch {
            // Nothing in here may escape: this job runs outside the screen's
            // scope, so an exception would reach the process's default handler
            // rather than a catch, and the learner would lose the app over an
            // attempt that was already safely in the folder.
            val result = try {
                val answer = AzureAssessor(region, key).assess(wav, word)
                val outcome = when (answer) {
                    is AzureAssessor.Answer.Body -> SayItScoring.outcome(word, stem, startedIso, answer.text)
                    is AzureAssessor.Answer.Failed ->
                        SayItScoring.outcome(word, stem, startedIso, null, answer.reason)
                }
                when (outcome) {
                    is SayItScoring.Outcome.NotScored ->
                        SayItTodayScore(note = outcome.reason, stem = stem)
                    is SayItScoring.Outcome.Scored ->
                        SayItTodayScore(
                            assessment = outcome.assessment,
                            saved = folder.writeSayItScore(outcome.fileName, outcome.json),
                            stem = stem,
                        )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SayItTodayScore(note = SayItScoring.Unscored.AZURE_ERROR, stem = stem)
            }
            // Back to the main thread for the one line that touches the UI
            // state, so every writer of it is still the same thread.
            withContext(Dispatchers.Main) { publish(word.id, result, false, forRun) }
        }
    }

    /**
     * The learner's own region and key from Settings, or null when either is
     * missing or malformed — the ordinary state of a phone that has not been
     * set up, and the commonest reason an attempt waits for the coach.
     *
     * This is the phone's **own** Speech resource, a second one created for it
     * alone; the coach's key runs the coach's pipeline in the cloud and is never
     * stored here (Settings says so where the key is entered).
     */
    private fun azureCredentials(): Pair<String, String>? {
        val region = RenderPlan.normalizeRegion(container.prefs.azureRegion.orEmpty()) ?: return null
        val key = RenderPlan.normalizeKey(container.prefs.azureKey.orEmpty()) ?: return null
        return region to key
    }

    /**
     * Record what became of one word's attempt and, when that word is still the
     * one on screen and the result belongs to its newest take, show it.
     * Callable from any thread.
     */
    private fun publish(id: String, result: SayItTodayScore?, scoring: Boolean, forRun: Int) {
        // A sitting that has been left behind files nothing and shows nothing.
        if (forRun != run) return
        if (result != null) {
            val newest = latestStem[id]
            // A slow first take must not overwrite a faster second one, nor one
            // that is still being recorded (marked with an empty stem).
            if (newest != null && newest != result.stem) return
            todayScores[id] = result
        }
        // One atomic read-modify-write, and the "is this still the word on
        // screen?" test inside it: this is the only place that touches the UI
        // state from a background thread, and a plain `copy` here would race
        // Next, which is a tap away while the assessment is in flight.
        _ui.update { ui ->
            val refreshed = if (result == null) ui else ui.copy(todayScores = HashMap(todayScores))
            if (ui.word?.id != id) {
                refreshed
            } else {
                refreshed.copy(
                    scoring = scoring,
                    score = result?.assessment,
                    scoreNote = result?.note,
                    scoreSaved = result?.saved ?: false,
                )
            }
        }
    }

    /** Show whatever this run already knows about the word now on screen. */
    private fun showScoreOf(word: SayItWord?) {
        val known = word?.let { todayScores[it.id] }
        _ui.update {
            it.copy(
                scoring = false,
                score = known?.assessment,
                scoreNote = known?.note,
                scoreSaved = known?.saved ?: false,
            )
        }
    }

    // ---- moving on --------------------------------------------------------

    /** Next word, or the done page after the last one. */
    fun onNext() {
        val current = _ui.value
        if (current.last) {
            finish()
            return
        }
        endWord()
        _ui.value = _ui.value.copy(index = current.index + 1, notice = null)
        prepareClip()
        showScoreOf(_ui.value.word)
    }

    /** Leaving mid-run (the Back confirmation) still ends on the summary: the saved attempts are real. */
    fun onLeaveRun() = finish()

    private fun finish() {
        endWord()
        val current = _ui.value
        _ui.value = current.copy(
            done = true,
            notice = null,
            rows = current.words.map { w ->
                SayItDoneRow(
                    id = w.id,
                    word = w.word,
                    sentence = w.sentence,
                    recorded = w.id in recorded,
                    lastScore = SayItPlanner.lastScore(w.id, results),
                )
            },
        )
    }

    /**
     * Put the current word down: stop everything it started and delete the two
     * cache files it needed. The attempt is deleted here rather than the moment
     * it is written, because the comparison plays from it and that is the whole
     * point of the screen; either way it never outlives the word.
     */
    private fun endWord() {
        recorder.cancel()
        clipJob?.cancel()
        playJob?.cancel()
        recordJob?.cancel()
        player.stop()
        stopping = false
        clearAttempt()
        // The model clip belongs to the unpacked sayit.zip and is not ours to delete.
        modelClip = null
        clipPlayed = 0
        attemptWritten = false
        _ui.value = _ui.value.copy(
            recording = false,
            saving = false,
            playing = ComparePart.NONE,
            clipReady = false,
            clipBusy = false,
            // The score card describes the word being put down; the next word
            // fills it again from [todayScores] if it has one of its own.
            scoring = false,
            score = null,
            scoreNote = null,
            scoreSaved = false,
        )
    }

    /**
     * Drop the cached recording. The deletion runs in the container's scope
     * rather than [viewModelScope] so that it still happens when the ViewModel
     * is being cleared — a recording of the learner's voice must not be left in
     * the cache because the screen went away mid-word.
     *
     * It waits for [saveJob] first. There is only ever one cache file
     * ([ATTEMPT_FILE]), so the file this deletes is the very one an in-flight
     * write is copying into `sayit/attempts/`; deleting it under that write
     * would lose the take he had just made, and he would not be told, because
     * the page that would carry the notice has already been left behind.
     */
    private fun clearAttempt() {
        val file = attemptFile
        val writing = saveJob
        attemptFile = null
        attemptWritten = false
        startedAt = null
        if (file != null) {
            container.scope.launch(Dispatchers.IO) {
                writing?.join()
                file.delete()
            }
        }
        _ui.value = _ui.value.copy(attempt = false, attemptSaved = false)
    }

    /**
     * The app went to the background, the screen went off or a call arrived.
     * A recording of half a sentence would be scored as a sentence with words
     * missing, so it is thrown away rather than kept, and playback stops.
     *
     * A write already under way is neither cancelled nor hidden: it runs on
     * [viewModelScope], which the background does not end, and only it clears
     * [SayItSentenceUi.saving]. Clearing that flag here used to re-enable Next
     * over a live write, which is how a finished take was lost.
     */
    fun onBackground() {
        val was = _ui.value.recording
        recorder.cancel()
        recordJob?.cancel()
        playJob?.cancel()
        player.stop()
        stopping = false
        if (was) clearAttempt()
        _ui.value = _ui.value.copy(
            recording = false,
            playing = ComparePart.NONE,
            notice = if (was) SayItNotice(SayItTrouble.INTERRUPTED) else _ui.value.notice,
        )
    }

    /**
     * Back in the foreground. The only thing to re-check is the microphone: he
     * may have granted it in Android's own settings, which the "recording is
     * off" card sends him to, and granting a permission does not restart the
     * process, so nothing else in the mode would ever notice.
     *
     * It only ever clears the flag. A refusal is not inferred from a permission
     * that has simply never been asked for, so a learner who has not reached
     * the system prompt still meets the plain Record control.
     */
    fun onForeground() {
        if (_ui.value.micDenied && recorder.hasPermission()) {
            _ui.value = _ui.value.copy(micDenied = false)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // The recorder and the player are process-wide singletons: hand them
        // back rather than release them, or the next run would find them dead.
        recorder.cancel()
        player.stop()
        endWord()
    }

    private fun cacheDir(): File = File(getApplication<Application>().cacheDir, CACHE_DIR)

    private companion object {
        /** One name, reused: only one recording is ever in flight. */
        const val ATTEMPT_FILE = "attempt.m4a"
        const val CACHE_DIR = "sayit"
    }
}
