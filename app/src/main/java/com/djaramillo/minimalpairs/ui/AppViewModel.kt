package com.djaramillo.minimalpairs.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.djaramillo.minimalpairs.AppContainer
import com.djaramillo.minimalpairs.audio.ClipCache
import com.djaramillo.minimalpairs.audio.Player
import com.djaramillo.minimalpairs.clips.ClipDownloader
import com.djaramillo.minimalpairs.clips.MergedIndex
import com.djaramillo.minimalpairs.domain.Ipa
import com.djaramillo.minimalpairs.domain.PlannedTrial
import com.djaramillo.minimalpairs.domain.RecordBuilder
import com.djaramillo.minimalpairs.domain.SessionScheduler
import com.djaramillo.minimalpairs.domain.StateUpdater
import com.djaramillo.minimalpairs.domain.model.Catalog
import com.djaramillo.minimalpairs.domain.model.EffectivePlan
import com.djaramillo.minimalpairs.domain.model.Feedback
import com.djaramillo.minimalpairs.domain.model.LearnerState
import com.djaramillo.minimalpairs.domain.model.Override
import com.djaramillo.minimalpairs.domain.model.Pair
import com.djaramillo.minimalpairs.domain.model.SessionRecord
import com.djaramillo.minimalpairs.domain.model.effectivePlan
import com.djaramillo.minimalpairs.storage.DataFolder
import com.djaramillo.minimalpairs.storage.FolderLayout
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/** The four screens; a tiny state machine held in [AppViewModel.screen]. */
enum class Screen { HOME, TRIAL, SUMMARY, SETTINGS }

data class ContrastRow(val id: String, val label: String, val lastUntrainedPct: Double?, val trials: Int)

data class HomeUi(
    val loading: Boolean = true,
    val streakDays: Int = 0,
    val sessionsCompleted: Int = 0,
    val note: String? = null,
    val planSource: String = "default",
    val rows: List<ContrastRow> = emptyList(),
    val folderStatus: DataFolder.Status = DataFolder.Status.NotChosen,
    /** Words with a clip in every voice / trainable words in the catalog. */
    val packWordsAvailable: Int = 0,
    val packWordsTotal: Int = 0,
    val packComplete: Boolean = false,
    val packEmpty: Boolean = true,
    val canDownload: Boolean = false,
    /** Non-null when a session cannot start ("Nothing to schedule…"). */
    val schedulerError: String? = null,
    val planMessage: String? = null,
    val lastError: String? = null,
)

sealed class TrialPhase {
    data object Loading : TrialPhase()
    /** Clip ready; playing or waiting for the answer. */
    data object Listening : TrialPhase()
    data class Answered(val correct: Boolean, val chosen: String) : TrialPhase()
}

data class TrialUi(
    val index: Int,
    val total: Int,
    val leftWord: String,
    val rightWord: String,
    val target: String,
    val other: String,
    val contrastLabel: String,
    val feedback: Feedback,
    val phase: TrialPhase = TrialPhase.Loading,
    val replays: Int = 0,
    val leftIpa: Ipa.Highlight? = null,
    val rightIpa: Ipa.Highlight? = null,
    val isLast: Boolean = false,
    val error: String? = null,
)

data class SummaryContrastRow(val id: String, val label: String, val trials: Int, val untrainedPct: Double?, val untrainedTrials: Int)

data class SummaryUi(
    val record: SessionRecord,
    val rows: List<SummaryContrastRow>,
    val fileName: String,
    val folderOutcome: DataFolder.WriteOutcome,
    val mirrored: Boolean,
    val stateOutcome: DataFolder.WriteOutcome,
)

data class ContrastInfo(val id: String, val label: String, val defaultWeight: Double, val usable: Boolean)

data class SettingsUi(
    val folderStatus: DataFolder.Status = DataFolder.Status.NotChosen,
    val plan: EffectivePlan? = null,
    val planPresent: Boolean = false,
    val planMessage: String? = null,
    val override: Override = Override(),
    val contrasts: List<ContrastInfo> = emptyList(),
    val pack: MergedIndex = MergedIndex(null, null),
    val packWordsAvailable: Int = 0,
    val packWordsTotal: Int = 0,
    val downloadedBytes: Long = 0,
    val appVersion: String = "",
    val catalogVersion: String = "",
    val message: String? = null,
)

/**
 * Holds the whole app state: bootstrap on launch (catalog, pack, state,
 * plan, catalog-version.txt, republish), the session loop, and the
 * Settings actions. Everything observable is a StateFlow.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = AppContainer.get(application)
    private val prefs = container.prefs
    private val folder = container.folder
    private val pack = container.pack
    private val player: Player = container.player
    val downloader: ClipDownloader = container.downloader
    val downloadState: StateFlow<ClipDownloader.State> = downloader.state

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen
    private val _home = MutableStateFlow(HomeUi())
    val home: StateFlow<HomeUi> = _home
    private val _trial = MutableStateFlow<TrialUi?>(null)
    val trial: StateFlow<TrialUi?> = _trial
    private val _summary = MutableStateFlow<SummaryUi?>(null)
    val summary: StateFlow<SummaryUi?> = _summary
    private val _settings = MutableStateFlow(SettingsUi())
    val settings: StateFlow<SettingsUi> = _settings

    // Loaded once.
    private var catalog: Catalog? = null
    private var state: LearnerState = LearnerState()
    private var planResult = DataFolder.PlanResult(null, null)
    private var effective: EffectivePlan? = null
    private var folderStatus: DataFolder.Status = DataFolder.Status.NotChosen

    // Session runtime.
    private var scheduler: SessionScheduler? = null
    private var cache: ClipCache? = null
    private var started: Instant? = null
    private var current: PlannedTrial? = null
    private var currentLoaded: Player.Loaded? = null
    private var next: PlannedTrial? = null
    private var nextTarget: Deferred<Player.Loaded>? = null
    private var firstPlayAt: Long? = null
    private var replays = 0
    private var autoAdvance: Job? = null
    private var sessionJob: Job? = null

    init {
        viewModelScope.launch { bootstrap() }
        viewModelScope.launch {
            downloader.state.collect { s ->
                if (s is ClipDownloader.State.Done) { pack.reload(); recompute() }
            }
        }
    }

    // ---- bootstrap --------------------------------------------------------

    private suspend fun bootstrap() {
        try {
            val cat = container.catalog()
            catalog = cat
            pack.reload()
            state = folder.readMirrorState()
                ?: folder.readFolderState()?.also { folder.writeMirrorState(it) }
                ?: LearnerState()
            syncFolder(cat)
        } catch (e: Exception) {
            _home.value = _home.value.copy(loading = false, lastError = e.message ?: e.javaClass.simpleName)
        } finally {
            recompute()
        }
    }

    /** Read the plan, write catalog-version.txt, republish sessions, refresh the status. */
    private suspend fun syncFolder(cat: Catalog) {
        planResult = folder.readPlan()
        folder.writeCatalogVersion(cat.version)
        folder.republishMissingSessions()
        folderStatus = folder.status()
    }

    private fun recompute() {
        val cat = catalog
        val merged = pack.index.value
        val override = prefs.override
        val catalogWords = cat?.allTrainableWords() ?: emptySet()
        val available = merged.words.count { it in catalogWords }
        if (cat == null) {
            _home.value = _home.value.copy(loading = false)
            return
        }
        val plan = effectivePlan(planResult.plan, cat, merged.voices, override)
        effective = plan
        var schedulerError: String? = null
        try {
            SessionScheduler(cat, plan, state, merged.words, Random)
        } catch (e: IllegalStateException) {
            schedulerError = e.message
        }
        val rows = cat.trainableContrasts.map { c ->
            val cs = state.contrasts[c.id]
            ContrastRow(c.id, c.label, cs?.lastUntrainedPct, cs?.trials ?: 0)
        }
        _home.value = HomeUi(
            loading = false,
            streakDays = state.streakDays,
            sessionsCompleted = state.sessionsCompleted,
            note = plan.note.takeIf { planResult.plan != null && it.isNotBlank() },
            planSource = plan.planSource,
            rows = rows,
            folderStatus = folderStatus,
            packWordsAvailable = available,
            packWordsTotal = catalogWords.size,
            packComplete = catalogWords.isNotEmpty() && available == catalogWords.size,
            packEmpty = merged.isEmpty || merged.words.isEmpty(),
            canDownload = merged.downloaded == null && !downloader.state.value.isRunning,
            schedulerError = schedulerError,
            planMessage = planResult.message,
            lastError = _home.value.lastError,
        )
        _settings.value = _settings.value.copy(
            folderStatus = folderStatus,
            plan = plan,
            planPresent = planResult.plan != null,
            planMessage = planResult.message,
            override = override,
            contrasts = cat.contrasts.map { c ->
                ContrastInfo(c.id, c.label, c.defaultWeight, c.trainable && c.trainablePairs.isNotEmpty())
            },
            pack = merged,
            packWordsAvailable = available,
            packWordsTotal = catalogWords.size,
            appVersion = container.appVersion,
            catalogVersion = cat.version,
        )
        viewModelScope.launch {
            val bytes = pack.downloadedBytes()
            _settings.value = _settings.value.copy(downloadedBytes = bytes)
        }
    }

    // ---- navigation -------------------------------------------------------

    fun openSettings() { _screen.value = Screen.SETTINGS }

    fun goHome() {
        _screen.value = Screen.HOME
        _settings.value = _settings.value.copy(message = null)
    }

    // ---- settings actions -------------------------------------------------

    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val st = folder.onFolderPicked(uri)
            folderStatus = st
            val cat = catalog
            if (st is DataFolder.Status.Ok && cat != null) {
                // First run with an existing folder state and no mirror: adopt the folder copy.
                if (!folder.mirrorState.isFile) {
                    folder.readFolderState()?.let { state = it; folder.writeMirrorState(it) }
                }
                syncFolder(cat)
            }
            recompute()
        }
    }

    fun forgetFolder() {
        viewModelScope.launch {
            folder.forgetFolder()
            folderStatus = folder.status()
            recompute()
        }
    }

    fun setOverride(value: Override) {
        prefs.override = value
        recompute()
    }

    fun startDownload() {
        downloader.reset()
        downloader.start(viewModelScope)
        recompute()
    }

    fun cancelDownload() = downloader.cancel()

    fun deleteDownloaded() {
        viewModelScope.launch {
            downloader.deleteDownloaded()
            recompute()
        }
    }

    fun republish() {
        viewModelScope.launch {
            val n = folder.republishMissingSessions()
            folderStatus = folder.status()
            recompute()
            _settings.value = _settings.value.copy(message = if (n == null) "republish:unavailable" else "republish:$n")
        }
    }

    fun refreshFolderStatus() {
        viewModelScope.launch {
            folderStatus = folder.status()
            recompute()
        }
    }

    // ---- session ----------------------------------------------------------

    fun startSession() {
        if (sessionJob?.isActive == true) return
        val cat = catalog ?: return
        val plan = effective ?: return
        sessionJob = viewModelScope.launch {
            _summary.value = null
            _trial.value = null
            _screen.value = Screen.TRIAL
            val merged = pack.index.value
            val sch = try {
                withContext(Dispatchers.Default) { SessionScheduler(cat, plan, state, merged.words, Random) }
            } catch (e: IllegalStateException) {
                _home.value = _home.value.copy(schedulerError = e.message)
                _screen.value = Screen.HOME
                return@launch
            }
            scheduler = sch
            cache?.releaseAll()
            cache = ClipCache(pack, player, viewModelScope)
            started = Instant.now()
            next = null
            nextTarget = null
            val first = sch.next(1)
            showTrial(first, prefetchOf(first))
        }
    }

    /** Kick off decoding of a trial's target and foil (same voice); returns the target's deferred. */
    private fun prefetchOf(t: PlannedTrial): Deferred<Player.Loaded> {
        val c = cache!!
        val d = c.prefetch(t.target, t.voice)
        c.prefetch(t.other, t.voice)
        return d
    }

    private fun trialUiFor(t: PlannedTrial): TrialUi {
        val cat = catalog!!
        val contrast = cat.contrast(t.contrast)
        val pair: Pair? = contrast?.pairs?.firstOrNull { it.id == t.pair }
        var left: Ipa.Highlight? = null
        var right: Ipa.Highlight? = null
        if (pair != null) {
            val h = Ipa.highlight(pair.a.ipa, pair.b.ipa, pair.diff)
            val forWord = { w: String -> if (w == pair.a.word) h.a else h.b }
            left = forWord(t.leftWord)
            right = forWord(t.rightWord)
        }
        val total = scheduler!!.plan.trialsPerSession
        return TrialUi(
            index = t.index,
            total = total,
            leftWord = t.leftWord,
            rightWord = t.rightWord,
            target = t.target,
            other = t.other,
            contrastLabel = contrast?.label ?: t.contrast,
            feedback = scheduler!!.plan.feedback,
            leftIpa = left,
            rightIpa = right,
            isLast = t.index >= total,
        )
    }

    private suspend fun showTrial(t: PlannedTrial, target: Deferred<Player.Loaded>) {
        current = t
        currentLoaded = null
        firstPlayAt = null
        replays = 0
        _trial.value = trialUiFor(t)
        val loaded = try {
            target.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _trial.value = _trial.value?.copy(error = e.message ?: e.javaClass.simpleName)
            return
        }
        currentLoaded = loaded
        _trial.value = _trial.value?.copy(phase = TrialPhase.Listening)
        // Auto-play once the clip is ready; this play is the reaction-time reference.
        firstPlayAt = player.play(loaded)
    }

    /** The big button: first play (if auto-play failed) or a replay of the same clip. */
    fun onPlayTapped() {
        val loaded = currentLoaded ?: return
        val ui = _trial.value ?: return
        if (ui.phase !is TrialPhase.Listening) return
        val at = player.play(loaded)
        if (firstPlayAt == null) firstPlayAt = at
        else {
            replays++
            _trial.value = _trial.value?.copy(replays = replays)
        }
    }

    fun onAnswer(word: String) {
        val t = current ?: return
        val sch = scheduler ?: return
        val ui = _trial.value ?: return
        if (ui.phase !is TrialPhase.Listening) return
        val onset = firstPlayAt ?: return
        val rt = (SystemClock.elapsedRealtime() - onset).toInt()
        val row = sch.record(t, word, rt, replays)
        _trial.value = ui.copy(phase = TrialPhase.Answered(row.correct, word))
        // Plan the next trial now and decode its clips during the feedback pause.
        if (t.index < sch.plan.trialsPerSession) {
            val n = sch.next(t.index + 1)
            next = n
            nextTarget = prefetchOf(n)
        }
        autoAdvance?.cancel()
        if (row.correct && ui.feedback != Feedback.FULL) {
            autoAdvance = viewModelScope.launch {
                delay(AUTO_ADVANCE_MS)
                onNext()
            }
        }
    }

    /** In full feedback, tap a word to hear it in the trial's voice. */
    fun onHearWord(word: String) {
        val t = current ?: return
        val c = cache ?: return
        val ui = _trial.value ?: return
        if (ui.phase !is TrialPhase.Answered || ui.feedback != Feedback.FULL) return
        viewModelScope.launch {
            try {
                currentLoaded?.let { player.stop(it) }
                val loaded = c.get(word, t.voice)
                player.play(loaded)
            } catch (e: Exception) {
                _trial.value = _trial.value?.copy(error = e.message)
            }
        }
    }

    fun onNext() {
        autoAdvance?.cancel()
        autoAdvance = null
        val ui = _trial.value ?: return
        if (ui.phase !is TrialPhase.Answered) return
        if (sessionJob?.isActive == true) return
        sessionJob = viewModelScope.launch {
            val n = next
            if (n == null) {
                finishSession()
            } else {
                val d = nextTarget ?: prefetchOf(n)
                next = null
                nextTarget = null
                showTrial(n, d)
            }
        }
    }

    fun abandonSession() {
        autoAdvance?.cancel()
        sessionJob?.cancel()
        sessionJob = null
        cache?.releaseAll()
        cache = null
        scheduler = null
        current = null
        currentLoaded = null
        next = null
        nextTarget = null
        _trial.value = null
        _screen.value = Screen.HOME
    }

    private suspend fun finishSession() {
        val sch = scheduler ?: return
        val cat = catalog ?: return
        val began = started ?: Instant.now()
        val ended = Instant.now()
        val record = RecordBuilder.build(
            started = began,
            ended = ended,
            appVersion = container.appVersion,
            catalogVersion = cat.version,
            planSource = sch.plan.planSource,
            planWritten = sch.plan.planWritten,
            voices = sch.plan.voices,
            trials = sch.answeredTrials,
            untrainedShortfall = sch.untrainedShortfall,
        )
        val newState = StateUpdater.apply(state, record, cat)
        state = newState
        var mirrored = true
        try {
            folder.writeMirrorState(newState)
            folder.writeMirrorSession(record)
        } catch (e: Exception) {
            mirrored = false
        }
        val stateOutcome = folder.writeState(newState)
        val sessionOutcome = folder.writeSession(record)
        folder.republishMissingSessions()
        folderStatus = folder.status()
        val rows = record.summary.contrasts.map { (id, s) ->
            SummaryContrastRow(
                id = id,
                label = cat.contrast(id)?.label ?: id,
                trials = s.trials,
                untrainedPct = if (s.untrainedTrials > 0) RecordBuilder.ratio(s.untrainedCorrect, s.untrainedTrials) else null,
                untrainedTrials = s.untrainedTrials,
            )
        }
        _summary.value = SummaryUi(
            record = record,
            rows = rows,
            fileName = FolderLayout.sessionFileName(record.id),
            folderOutcome = sessionOutcome,
            mirrored = mirrored,
            stateOutcome = stateOutcome,
        )
        cache?.releaseAll()
        cache = null
        scheduler = null
        current = null
        currentLoaded = null
        _trial.value = null
        _screen.value = Screen.SUMMARY
        recompute()
    }

    override fun onCleared() {
        cache?.releaseAll()
        cache = null
        super.onCleared()
    }

    companion object {
        const val AUTO_ADVANCE_MS = 900L
    }
}
