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
import com.djaramillo.minimalpairs.domain.LevelPolicy
import com.djaramillo.minimalpairs.domain.PlannedTrial
import com.djaramillo.minimalpairs.domain.RecordBuilder
import com.djaramillo.minimalpairs.domain.SessionScheduler
import com.djaramillo.minimalpairs.domain.StateUpdater
import com.djaramillo.minimalpairs.domain.TimeUtil
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/** The screens; a tiny state machine held in [AppViewModel.screen]. */
enum class Screen { HOME, TRIAL, SUMMARY, SETTINGS }

data class ContrastRow(
    val id: String,
    val label: String,
    val lastUntrainedPct: Double?,
    val trials: Int,
    /** The contrast's rung on the level ladder (pins and `max_level` applied). */
    val level: Int = 1,
    /** Last untrained result against the previous one; `null` with fewer than two. */
    val trend: Trend? = null,
)

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
    /** Consistency (docs/ADAPTATION.md): minutes practised this Monday–Sunday week against `plan.weekly_minutes_target`. */
    val weekMinutes: Int = 0,
    val weeklyTarget: Int = 0,
    val longestStreak: Int = 0,
)

sealed class TrialPhase {
    data object Loading : TrialPhase()
    /** Clip ready; playing or waiting for the answer. */
    data object Listening : TrialPhase()
    data class Answered(val correct: Boolean, val chosen: String) : TrialPhase()
    /**
     * The clip could not be opened, decoded or played. Nothing is recorded for
     * this trial; the learner can [AppViewModel.retryTrial] (decode again) or
     * [AppViewModel.skipTrial] (draw a replacement with the same index), so one
     * bad file never forces the whole session to be abandoned.
     */
    data class Failed(val message: String) : TrialPhase()
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
    /** Non-fatal playback problem (tap-to-hear failed); the trial goes on. */
    val error: String? = null,
)

data class SummaryContrastRow(val id: String, val label: String, val trials: Int, val untrainedPct: Double?, val untrainedTrials: Int)

data class LevelChangeRow(val id: String, val label: String, val from: Int, val to: Int)

data class SummaryUi(
    val record: SessionRecord,
    val rows: List<SummaryContrastRow>,
    val fileName: String,
    val folderOutcome: DataFolder.WriteOutcome,
    val mirrored: Boolean,
    val stateOutcome: DataFolder.WriteOutcome,
    /** Rungs that moved when this session was applied. */
    val levelChanges: List<LevelChangeRow> = emptyList(),
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
    /** Azure Speech (Settings → Azure Speech): region as stored, whether a key is stored, last test result. */
    val azureRegion: String = "",
    val azureKeySet: Boolean = false,
    val azureMessage: String? = null,
    val azureOk: Boolean? = null,
    /** A download or import is writing the pack directory (the renderer must wait). */
    val packBusy: Boolean = false,
)

/**
 * Holds the whole app state: bootstrap on launch (catalog, pack, state,
 * plan, catalog-version.txt, republish), the perception session loop and the
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
    private val renderer = container.renderer
    val renderState: StateFlow<com.djaramillo.minimalpairs.clips.PackRenderer.State> = renderer.state

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
    /** Set once [bootstrap] has run (successfully or not); [onResume] does nothing before that. */
    private var bootstrapped = false

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
    private var finishing = false

    init {
        viewModelScope.launch { bootstrap() }
        viewModelScope.launch {
            var wasRunning = false
            downloader.state.collect { s ->
                if (s is ClipDownloader.State.Done) pack.reload()
                if (s is ClipDownloader.State.Done || wasRunning != s.isRunning) recompute()
                wasRunning = s.isRunning
            }
        }
        viewModelScope.launch {
            // Reload the pack when a render finishes or is cancelled (it wrote index.json either way).
            var wasRunning = false
            renderer.state.collect { s ->
                val running = s.isRunning
                if (wasRunning && !running) { pack.reload(); recompute() }
                wasRunning = running
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
            bootstrapped = true
            recompute()
        }
    }

    /**
     * The activity came to the foreground: re-read `plan.json` (DriveSync may
     * have delivered a new one, or a half-synced read at launch may have
     * failed), republish never-published sessions and refresh the status.
     * Skipped during a session: the running scheduler keeps its plan.
     */
    fun onResume() {
        val cat = catalog ?: return
        if (!bootstrapped || inSession()) return
        viewModelScope.launch {
            syncFolder(cat)
            if (!inSession()) recompute()
        }
    }

    private fun inSession(): Boolean = _screen.value == Screen.TRIAL

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
            ContrastRow(
                id = c.id,
                label = c.label,
                lastUntrainedPct = cs?.lastUntrainedPct,
                trials = cs?.trials ?: 0,
                level = LevelPolicy.currentLevel(c.id, state, plan),
                trend = trendOf(cs?.recentUntrainedPct ?: emptyList()),
            )
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
            // Offer the download until a pack that says `complete: true` is installed
            // (a placeholder pack downloaded by an older build must not hide the button).
            canDownload = merged.downloaded?.complete != true && !downloader.state.value.isRunning && !renderer.state.value.isRunning,
            schedulerError = schedulerError,
            planMessage = planResult.message,
            lastError = _home.value.lastError,
            weekMinutes = weekMinutes(state.practice.days, TimeUtil.utcDay(Instant.now())),
            weeklyTarget = plan.weeklyMinutesTarget,
            longestStreak = state.practice.longestStreak,
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
            azureRegion = prefs.azureRegion ?: "",
            azureKeySet = !prefs.azureKey.isNullOrEmpty(),
            packBusy = downloader.state.value.isRunning,
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
        if (renderer.state.value.isRunning) return
        downloader.reset()
        val cat = catalog
        downloader.start(viewModelScope, cat?.version ?: "", cat?.allTrainableWords() ?: emptySet())
        recompute()
    }

    /** Settings → "Import clips.zip": install a pack the user picked with the file picker. */
    fun importPack(uri: android.net.Uri) {
        if (renderer.state.value.isRunning) return
        downloader.reset()
        val cat = catalog
        downloader.startFromUri(viewModelScope, uri, cat?.version ?: "", cat?.allTrainableWords() ?: emptySet())
        recompute()
    }

    fun cancelDownload() = downloader.cancel()

    // ---- Azure Speech: the learner's own key, rendering on the phone ------

    /** Store region + key (validated for shape only). An empty key keeps the stored one. */
    fun saveAzure(region: String, key: String) {
        val r = com.djaramillo.minimalpairs.clips.RenderPlan.normalizeRegion(region)
        if (r == null) { azureMessage("azure:bad-region", false); return }
        if (key.isBlank() && prefs.azureKey.isNullOrEmpty()) { azureMessage("azure:no-key", false); return }
        val k = if (key.isBlank()) prefs.azureKey else com.djaramillo.minimalpairs.clips.RenderPlan.normalizeKey(key)
        if (k == null) { azureMessage("azure:bad-key", false); return }
        prefs.azureRegion = r
        prefs.azureKey = k
        azureMessage("azure:saved", true)
        recompute()
    }

    fun forgetAzure() {
        prefs.clearAzure()
        azureMessage(null, null)
        recompute()
    }

    /** Ask Azure for its voice list with the stored key; reports the en-GB voice count or the error. */
    fun testAzure() {
        val r = prefs.azureRegion; val k = prefs.azureKey
        if (r.isNullOrEmpty() || k.isNullOrEmpty()) { azureMessage("azure:no-key", false); return }
        azureMessage("azure:testing", null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val voices = com.djaramillo.minimalpairs.clips.AzureTts(r, k).listBritishVoices()
                val wanted = com.djaramillo.minimalpairs.clips.RenderPlan.voicesFor(catalog ?: container.catalog())
                val missing = wanted.filter { it !in voices }
                azureMessage(if (missing.isEmpty()) "azure:ok:${voices.size}:${wanted.size}" else "azure:missing:${missing.joinToString(", ")}", missing.isEmpty())
            } catch (e: Exception) {
                azureMessage(e.message ?: e.javaClass.simpleName, false)
            }
        }
    }

    fun startRender() {
        val r = prefs.azureRegion; val k = prefs.azureKey
        val cat = catalog
        if (r.isNullOrEmpty() || k.isNullOrEmpty() || cat == null) { azureMessage("azure:no-key", false); return }
        if (downloader.state.value.isRunning) return
        renderer.reset()
        renderer.start(container.scope, cat, r, k)
    }

    fun cancelRender() = renderer.cancel()

    private fun azureMessage(msg: String?, ok: Boolean?) {
        _settings.value = _settings.value.copy(azureMessage = msg, azureOk = ok)
    }

    fun deleteDownloaded() {
        if (renderer.state.value.isRunning) return
        viewModelScope.launch {
            downloader.deleteDownloaded()
            recompute()
        }
    }

    fun republish() {
        viewModelScope.launch {
            val n = folder.republishMissingSessions(force = true)
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
        if (effective == null) return
        sessionJob = viewModelScope.launch {
            _summary.value = null
            _trial.value = null
            _screen.value = Screen.TRIAL
            // A plan.json that DriveSync delivered while the process was alive (or that was
            // half-synced at launch) must drive this session: re-read it now, off the main thread.
            planResult = folder.readPlan()
            val merged = pack.index.value
            val plan = effectivePlan(planResult.plan, cat, merged.voices, prefs.override)
            effective = plan
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
            finishing = false
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
            failTrial(e)
            return
        }
        currentLoaded = loaded
        _trial.value = _trial.value?.copy(phase = TrialPhase.Listening)
        // Auto-play once the clip is ready; this play is the reaction-time reference.
        try {
            firstPlayAt = player.play(loaded)
        } catch (e: IllegalStateException) {
            failTrial(e)
        }
    }

    /** The current trial cannot be played: offer Retry / Skip instead of a dead end. */
    private fun failTrial(e: Exception) {
        currentLoaded = null
        firstPlayAt = null
        _trial.value = _trial.value?.copy(phase = TrialPhase.Failed(e.message ?: e.javaClass.simpleName))
    }

    /** Decode the failed trial's clip again (a failed decode is not cached, see [ClipCache]). */
    fun retryTrial() {
        val t = current ?: return
        val ui = _trial.value ?: return
        if (ui.phase !is TrialPhase.Failed) return
        if (sessionJob?.isActive == true) return
        sessionJob = viewModelScope.launch { showTrial(t, prefetchOf(t)) }
    }

    /**
     * Replace the failed trial with a fresh draw at the same index. Nothing is
     * recorded for the failed one, so the session record stays 1..N. The
     * scheduler keeps the failed draw in its running tallies (untrained ratio,
     * pair cycle), a bias of one trial that is preferable to losing the session.
     */
    fun skipTrial() {
        val t = current ?: return
        val sch = scheduler ?: return
        val ui = _trial.value ?: return
        if (ui.phase !is TrialPhase.Failed) return
        if (sessionJob?.isActive == true) return
        sessionJob = viewModelScope.launch {
            val n = sch.next(t.index)
            showTrial(n, prefetchOf(n))
        }
    }

    /** The big button: first play (if auto-play failed) or a replay of the same clip. */
    fun onPlayTapped() {
        val loaded = currentLoaded ?: return
        val ui = _trial.value ?: return
        if (ui.phase !is TrialPhase.Listening) return
        val at = try {
            player.play(loaded)
        } catch (e: IllegalStateException) {
            failTrial(e)
            return
        }
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
                finishSession(scheduler ?: return@launch)
            } else {
                val d = nextTarget ?: prefetchOf(n)
                next = null
                nextTarget = null
                showTrial(n, d)
            }
        }
    }

    /**
     * The activity went to the background: stop ducking other apps (the next
     * play re-takes focus).
     */
    fun onBackground() {
        player.abandonFocus()
    }

    fun abandonSession() {
        autoAdvance?.cancel()
        sessionJob?.cancel()
        sessionJob = null
        cache?.releaseAll()
        cache = null
        player.abandonFocus()
        scheduler = null
        current = null
        currentLoaded = null
        next = null
        nextTarget = null
        _trial.value = null
        _screen.value = Screen.HOME
    }

    // ---- session end ----------------------------------------------------

    /**
     * Build the record, apply it to the state, write everything, show the
     * Summary. Runs to completion once started (`NonCancellable`): a session
     * whose files are half-written would be worse than a late cancellation.
     *
     * [sch] is the session the caller belongs to. A caller whose session is no
     * longer the current one is refused outright: the session it meant to end
     * is over, and writing "it" now would write whatever session is running
     * instead — a file in the coach's folder for a session that never happened
     * (docs/CONTRACT.md: one file per completed session, written once).
     */
    private suspend fun finishSession(sch: SessionScheduler) = withContext(NonCancellable) {
        if (scheduler !== sch || finishing) return@withContext
        val cat = catalog ?: return@withContext
        finishing = true
        // Bump `started` past any mirror session with the same second (wall clock stepped back),
        // so this session gets its own file instead of being dropped as "already there".
        val began = folder.freeSessionStart(started ?: Instant.now())
        val ended = maxOf(Instant.now(), began)

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
            levels = sch.levels,
        )
        val before = state
        val newState = StateUpdater.apply(before, record, cat, sch.plan)
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
        val summaryRows = record.summary.contrasts.map { (id, s) ->
            SummaryContrastRow(
                id = id,
                label = cat.contrast(id)?.label ?: id,
                trials = s.trials,
                untrainedPct = if (s.untrainedTrials > 0) RecordBuilder.ratio(s.untrainedCorrect, s.untrainedTrials) else null,
                untrainedTrials = s.untrainedTrials,
            )
        }
        val changes = levelChanges(before, newState).map { LevelChangeRow(it.id, cat.contrast(it.id)?.label ?: it.id, it.from, it.to) }
        _summary.value = SummaryUi(
            record = record,
            rows = summaryRows,
            fileName = FolderLayout.sessionFileName(record.id),
            folderOutcome = sessionOutcome,
            mirrored = mirrored,
            stateOutcome = stateOutcome,
            levelChanges = changes,
        )
        cache?.releaseAll()
        cache = null
        player.abandonFocus()
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
        player.abandonFocus()
        super.onCleared()
    }

    companion object {
        const val AUTO_ADVANCE_MS = 900L
    }
}
