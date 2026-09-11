package com.djaramillo.minimalpairs.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.djaramillo.minimalpairs.AppContainer
import com.djaramillo.minimalpairs.audio.ClipCache
import com.djaramillo.minimalpairs.audio.Player
import com.djaramillo.minimalpairs.audio.Recorder
import com.djaramillo.minimalpairs.clips.AzureSpeech
import com.djaramillo.minimalpairs.clips.ClipDownloader
import com.djaramillo.minimalpairs.clips.MergedIndex
import com.djaramillo.minimalpairs.domain.Ipa
import com.djaramillo.minimalpairs.domain.LevelPolicy
import com.djaramillo.minimalpairs.domain.PlannedTrial
import com.djaramillo.minimalpairs.domain.ProductionPlanner
import com.djaramillo.minimalpairs.domain.ProductionScorer
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
import com.djaramillo.minimalpairs.domain.model.ProductionRow
import com.djaramillo.minimalpairs.domain.model.SessionRecord
import com.djaramillo.minimalpairs.domain.model.WordResult
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
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/** The five screens; a tiny state machine held in [AppViewModel.screen]. */
enum class Screen { HOME, TRIAL, SAY_IT, SUMMARY, SETTINGS }

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

/** One word of the Say-it pair on screen. */
sealed class SayWordPhase {
    /** Not recorded yet (or a failed attempt may be redone). */
    data object Idle : SayWordPhase()
    data class Recording(val level: Float, val speaking: Boolean) : SayWordPhase()
    /** Recorded; the three Azure calls are in flight. */
    data object Scoring : SayWordPhase()
    /** The recording held no speech; one more try is allowed. */
    data object NoSpeech : SayWordPhase()
    /** The microphone failed; the learner may try again. */
    data class Error(val message: String) : SayWordPhase()
    data class Scored(val result: WordResult, val point: Int, val hint: SayHint?) : SayWordPhase()
}

data class SayWordUi(
    val word: String,
    val ipa: Ipa.Highlight?,
    val phase: SayWordPhase = SayWordPhase.Idle,
    /** Recordings made so far (the row's `attempts`). */
    val attempts: Int = 0,
    /** The model voice has a clip for this word. */
    val hearEnabled: Boolean = true,
    /** The learner's recording can be played back. */
    val hasRecording: Boolean = false,
)

data class SayItUi(
    /** 1-based pair index and the block size; 0 / 0 while preparing. */
    val index: Int = 0,
    val total: Int = 0,
    val contrastId: String = "",
    val contrastLabel: String = "",
    val pairId: String = "",
    val a: SayWordUi? = null,
    val b: SayWordUi? = null,
    /** Pair points once both words are scored. */
    val points: Int? = null,
    /** Permission prompt, network probe or planning in progress. */
    val preparing: Boolean = true,
    /** Non-fatal problem (model clip could not be played); the pair goes on. */
    val error: String? = null,
    /** Points scored so far in the block over the pairs done. */
    val pointsSoFar: Int = 0,
    val pairsDone: Int = 0,
) {
    fun word(w: String): SayWordUi? = when (w) { a?.word -> a; b?.word -> b; else -> null }
    val bothScored: Boolean get() = a?.phase is SayWordPhase.Scored && b?.phase is SayWordPhase.Scored
    val busy: Boolean get() = listOf(a?.phase, b?.phase).any { it is SayWordPhase.Recording || it is SayWordPhase.Scoring }
}

data class SummaryContrastRow(val id: String, val label: String, val trials: Int, val untrainedPct: Double?, val untrainedTrials: Int)

data class SummaryProductionRow(val id: String, val label: String, val pairs: Int, val points: Int)

data class LevelChangeRow(val id: String, val label: String, val from: Int, val to: Int)

data class SummaryUi(
    val record: SessionRecord,
    val rows: List<SummaryContrastRow>,
    val fileName: String,
    val folderOutcome: DataFolder.WriteOutcome,
    val mirrored: Boolean,
    val stateOutcome: DataFolder.WriteOutcome,
    /** Say-it points per contrast (empty when the block was off or skipped). */
    val production: List<SummaryProductionRow> = emptyList(),
    /** Rungs that moved when this session was applied. */
    val levelChanges: List<LevelChangeRow> = emptyList(),
    /**
     * Why the Say-it block did not run or ended early, as a code the screen
     * maps to text: `sayit:denied`, `sayit:no-network`, `sayit:nothing`,
     * `sayit:skipped`, `sayit:azure:<message>`; `null` when nothing to say.
     */
    val sayItNotice: String? = null,
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
 * plan, catalog-version.txt, republish), the session loop (perception trials,
 * then the Say-it block), and the Settings actions. Everything observable is
 * a StateFlow.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = AppContainer.get(application)
    private val prefs = container.prefs
    private val folder = container.folder
    private val pack = container.pack
    private val player: Player = container.player
    private val recorder: Recorder = container.recorder
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
    private val _sayIt = MutableStateFlow<SayItUi?>(null)
    val sayIt: StateFlow<SayItUi?> = _sayIt
    private val _summary = MutableStateFlow<SummaryUi?>(null)
    val summary: StateFlow<SummaryUi?> = _summary
    private val _settings = MutableStateFlow(SettingsUi())
    val settings: StateFlow<SettingsUi> = _settings
    /**
     * The pending `RECORD_AUDIO` request of the Say-it block: `0` when nothing
     * is pending, otherwise the id of the request the activity must show the
     * system prompt for; it answers with [onMicPermission], which clears it
     * again. The activity remembers the id it prompted for across rotation, and
     * the ViewModel clears the request whenever the block ends, so the two
     * never disagree after the process was killed and the activity restored
     * (a saved counter against a ViewModel that starts from scratch would
     * otherwise leave the next block waiting for a prompt that never comes).
     */
    private val _micPrompt = MutableStateFlow(0)
    val micPrompt: StateFlow<Int> = _micPrompt
    private var micRequests = 0

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

    // Say-it runtime (docs/CONTRACT.md "Say it", docs/ADAPTATION.md "Say it — what adapts").
    private var perceptionEnded: Instant? = null
    private var productionStarted: Instant? = null
    private var azure: AzureSpeech? = null
    private var sayPairs: List<Pair> = emptyList()
    private var sayIndex = 0
    private var sayVoice: String? = null
    private val sayRows = ArrayList<ProductionRow>()
    /** Current pair: word → its result (both present once the pair is scored). */
    private val sayResults = LinkedHashMap<String, WordResult>()
    private val sayAttempts = HashMap<String, Int>()
    /** Current pair: word → the learner's recording bound to a track, for playback. */
    private val sayLoaded = HashMap<String, Player.Loaded>()
    private var sayJob: Job? = null
    private var sayNotice: String? = null
    private var awaitingMic = false

    /**
     * Identity of the running Say-it block: the [AzureSpeech] instance made
     * when the block starts, dropped again by [resetSayIt]. Everything that
     * comes back from a recording or an Azure call carries the token it began
     * with and must do nothing at all once it no longer matches: the block —
     * and possibly the whole session — is gone, and a late result must never
     * touch the state of the next one (a stale failure that ended "the
     * session" would otherwise write the session the learner has only just
     * started, docs/CONTRACT.md).
     */
    private fun sayBlockGone(token: AzureSpeech): Boolean = azure !== token

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

    private fun inSession(): Boolean = _screen.value == Screen.TRIAL || _screen.value == Screen.SAY_IT

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
            _sayIt.value = null
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
            resetSayIt()
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
                perceptionEnded = Instant.now()
                beginSayIt()
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
     * play re-takes focus) and end a running recording (Android silences the
     * microphone of a background app anyway; the frames so far are kept).
     */
    fun onBackground() {
        player.abandonFocus()
        recorder.stop()
    }

    fun abandonSession() {
        autoAdvance?.cancel()
        sessionJob?.cancel()
        sessionJob = null
        sayJob?.cancel()
        sayJob = null
        recorder.stop()
        cache?.releaseAll()
        cache = null
        player.abandonFocus()
        scheduler = null
        current = null
        currentLoaded = null
        next = null
        nextTarget = null
        resetSayIt()
        _trial.value = null
        _sayIt.value = null
        _screen.value = Screen.HOME
    }

    // ---- Say it ---------------------------------------------------------

    private fun resetSayIt() {
        perceptionEnded = null
        productionStarted = null
        azure = null
        sayPairs = emptyList()
        sayIndex = 0
        sayVoice = null
        sayRows.clear()
        sayResults.clear()
        sayAttempts.clear()
        releaseSayRecordings()
        sayNotice = null
        awaitingMic = false
        _micPrompt.value = 0
    }

    private fun releaseSayRecordings() {
        for (l in sayLoaded.values) player.release(l)
        sayLoaded.clear()
    }

    /**
     * After the last perception trial: run the Say-it block when the plan asks
     * for it (`production_pairs > 0`) and a key is stored; otherwise write the
     * session straight away. Without the microphone permission the system
     * prompt is shown once ([micRequest] → [onMicPermission]); a denial skips
     * the block with a notice and the session still counts.
     */
    private suspend fun beginSayIt() {
        val sch = scheduler ?: return
        val region = prefs.azureRegion
        val key = prefs.azureKey
        if (!sch.plan.productionOn || region.isNullOrEmpty() || key.isNullOrEmpty()) {
            finishSession(sch)
            return
        }
        azure = AzureSpeech(region, key)
        _sayIt.value = SayItUi(preparing = true)
        _screen.value = Screen.SAY_IT
        _trial.value = null
        if (!recorder.hasPermission()) {
            awaitingMic = true
            micRequests++
            _micPrompt.value = micRequests
            return
        }
        // Preparation runs as the block's own job so "Skip the rest" can cancel it (a probe on a
        // dead connection can take seconds) without touching the session job.
        sayJob = viewModelScope.launch { prepareSayIt(sch) }
    }

    /** The activity's answer to the `RECORD_AUDIO` prompt. */
    fun onMicPermission(granted: Boolean) {
        _micPrompt.value = 0
        if (!awaitingMic) return
        awaitingMic = false
        val sch = scheduler ?: return
        if (_screen.value != Screen.SAY_IT || finishing) return
        if (sayJob?.isActive == true) return
        sayJob = viewModelScope.launch {
            if (granted && recorder.hasPermission()) prepareSayIt(sch)
            else { sayNotice = "sayit:denied"; finishSession(sch) }
        }
    }

    /** Network probe, pair selection, first pair (runs in [sayJob], for the session [sch]). */
    private suspend fun prepareSayIt(sch: SessionScheduler) {
        val cat = catalog ?: return
        val az = azure ?: return
        val reachable = withContext(Dispatchers.IO) { az.reachable() }
        if (sayBlockGone(az) || scheduler !== sch) return
        if (!reachable) { sayNotice = "sayit:no-network"; finishSession(sch); return }
        val misses = sch.answeredTrials.filter { !it.correct }.map { it.pair }.toSet()
        val mods = sch.schedulable.associate { it.id to sch.sessionModifier(it.id) }
        val words = pack.index.value.words
        val pairs = withContext(Dispatchers.Default) {
            // Prefer pairs whose model clips exist (hear buttons live); a partial pack must not silence the block.
            ProductionPlanner.pick(sch.plan.productionPairs, cat, sch.plan, state, misses, Random, availableWords = words, sessionModifiers = mods)
                .ifEmpty { ProductionPlanner.pick(sch.plan.productionPairs, cat, sch.plan, state, misses, Random, availableWords = null, sessionModifiers = mods) }
        }
        if (sayBlockGone(az) || scheduler !== sch) return
        if (pairs.isEmpty()) { sayNotice = "sayit:nothing"; finishSession(sch); return }
        sayPairs = pairs
        sayIndex = 0
        productionStarted = Instant.now()
        showSayPair()
    }

    private fun currentSayPair(): Pair? = sayPairs.getOrNull(sayIndex)

    private fun showSayPair() {
        val sch = scheduler ?: return
        val cat = catalog ?: return
        val pair = currentSayPair() ?: return
        sayResults.clear()
        sayAttempts.clear()
        releaseSayRecordings()
        val voice = sch.plan.voices.random()
        sayVoice = voice
        val merged = pack.index.value
        val h = Ipa.highlight(pair.a.ipa, pair.b.ipa, pair.diff)
        val c = cache
        fun wordUi(word: String, ipa: Ipa.Highlight): SayWordUi {
            val has = merged.has(word, voice)
            if (has) c?.prefetch(word, voice)
            return SayWordUi(word = word, ipa = ipa, hearEnabled = has)
        }
        val contrastId = pair.id.substringBefore(':')
        _sayIt.value = SayItUi(
            index = sayIndex + 1,
            total = sayPairs.size,
            contrastId = contrastId,
            contrastLabel = cat.contrast(contrastId)?.label ?: contrastId,
            pairId = pair.id,
            a = wordUi(pair.a.word, h.a),
            b = wordUi(pair.b.word, h.b),
            preparing = false,
            pointsSoFar = sayRows.sumOf { it.points },
            pairsDone = sayRows.size,
        )
    }

    private fun updateSayWord(word: String, f: (SayWordUi) -> SayWordUi) {
        _sayIt.update { ui ->
            when (word) {
                ui?.a?.word -> ui.copy(a = f(ui.a))
                ui?.b?.word -> ui.copy(b = f(ui.b))
                else -> ui
            }
        }
    }

    /** The big record button: start recording [word]; while recording, stop it. */
    fun onSayRecord(word: String) {
        val ui = _sayIt.value ?: return
        val w = ui.word(word) ?: return
        if (w.phase is SayWordPhase.Recording) { recorder.stop(); return }
        if (ui.busy || ui.preparing) return
        if (w.phase is SayWordPhase.Scored || w.phase is SayWordPhase.Scoring) return
        if (sayJob?.isActive == true) return
        sayJob = viewModelScope.launch { recordAndScore(word) }
    }

    private suspend fun recordAndScore(word: String) {
        val sch = scheduler ?: return
        val cat = catalog ?: return
        val az = azure ?: return
        val pair = currentSayPair() ?: return
        val other = pair.other(word) ?: return
        val contrastId = pair.id.substringBefore(':')
        val threshold = sch.plan.productionThreshold

        updateSayWord(word) { it.copy(phase = SayWordPhase.Recording(0f, false)) }
        currentLoaded?.let { player.stop(it) }
        val rec = try {
            recorder.record { level, speaking ->
                updateSayWord(word) { w ->
                    if (w.phase is SayWordPhase.Recording) w.copy(phase = SayWordPhase.Recording(level, speaking)) else w
                }
            }
        } catch (e: CancellationException) {
            if (!sayBlockGone(az)) updateSayWord(word) { it.copy(phase = SayWordPhase.Idle) }
            throw e
        } catch (e: IOException) {
            if (sayBlockGone(az)) return
            updateSayWord(word) { it.copy(phase = SayWordPhase.Error(e.message ?: e.javaClass.simpleName)) }
            return
        }
        if (sayBlockGone(az)) return
        val attempts = (sayAttempts[word] ?: 0) + 1
        sayAttempts[word] = attempts

        // Keep the learner's voice for playback (a failure here only disables the playback button).
        sayLoaded.remove(word)?.let { player.release(it) }
        if (rec.pcm.isNotEmpty()) {
            try {
                val loaded = withContext(Dispatchers.Default) { player.prepare(rec.toClip()) }
                // The block may have ended while the clip was being prepared: release it there
                // instead of leaving a track behind in a map nobody clears again.
                if (sayBlockGone(az)) { player.release(loaded); return }
                sayLoaded[word] = loaded
            } catch (e: IllegalStateException) {
                // no playback for this word
            }
        }
        if (sayBlockGone(az)) return
        val hasRecording = sayLoaded.containsKey(word)

        if (!rec.hasSpeech) {
            if (attempts < MAX_ATTEMPTS) {
                updateSayWord(word) { it.copy(phase = SayWordPhase.NoSpeech, attempts = attempts, hasRecording = hasRecording) }
                return
            }
            // Nothing said twice: scored as nothing heard, no Azure call (docs/CONTRACT.md "attempts").
            val result = ProductionScorer.score(
                ProductionScorer.WordInput(0.0, 0.0, null, null, null, rec.durationMs, attempts),
                word, other, contrastId, cat, threshold, pair,
            )
            completeSayWord(word, result, noSpeech = true, hasRecording = hasRecording)
            return
        }

        updateSayWord(word) { it.copy(phase = SayWordPhase.Scoring, attempts = attempts, hasRecording = hasRecording) }
        val wav = withContext(Dispatchers.Default) { rec.toWav() }
        val res = try {
            withContext(Dispatchers.IO) { az.assessPair(wav, word, other) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // A call cancelled with the block can still end in its own IOException rather than a
            // CancellationException (the three requests block on IO): a failure that belongs to a
            // block that is gone must not end — or write — the session that is running now.
            if (sayBlockGone(az) || scheduler !== sch) return
            // No network or Azure failing for good: end the block with what was scored (docs/CONTRACT.md).
            sayNotice = "sayit:azure:" + (e.message ?: e.javaClass.simpleName)
            updateSayWord(word) { it.copy(phase = SayWordPhase.Idle) }
            endSayIt(sch)
            return
        }
        if (sayBlockGone(az) || scheduler !== sch) return
        // Azure heard nothing at all (a breath, a chair, a page turn passes the local gate but
        // comes back with no text and accuracy 0): that is a recording without speech, and the
        // contract gives it the same single redo; only the second one is scored (docs/CONTRACT.md
        // "Say it", `attempts`).
        if (res.heardNothing && attempts < MAX_ATTEMPTS) {
            updateSayWord(word) { it.copy(phase = SayWordPhase.NoSpeech, attempts = attempts, hasRecording = hasRecording) }
            return
        }
        val target = ProductionScorer.phonemeOfInterest(pair, word)
        val targetOther = ProductionScorer.phonemeOfInterest(pair, other)
        val ph = target?.let { ProductionScorer.pickPhonemeScore(res.intended.phonemes, it) }
        val phOther = targetOther?.let { ProductionScorer.pickPhonemeScore(res.other.phonemes, it) }
        val input = ProductionScorer.WordInput(
            acc = res.intended.acc ?: 0.0,
            accOther = res.other.acc ?: 0.0,
            ph = ph,
            phOther = phOther,
            recognised = res.recognition.recognised,
            ms = rec.durationMs,
            attempts = attempts,
        )
        val result = ProductionScorer.score(input, word, other, contrastId, cat, threshold, pair)
        completeSayWord(word, result, noSpeech = res.heardNothing, hasRecording = hasRecording)
    }

    private fun completeSayWord(word: String, result: WordResult, noSpeech: Boolean, hasRecording: Boolean) {
        val sch = scheduler ?: return
        val pair = currentSayPair() ?: return
        val threshold = sch.plan.productionThreshold
        sayResults[word] = result
        val point = ProductionScorer.points(result, word, threshold)
        val hint = sayHint(pair, word, result, threshold, noSpeech)
        updateSayWord(word) { it.copy(phase = SayWordPhase.Scored(result, point, hint), attempts = result.attempts, hasRecording = hasRecording) }
        val ra = sayResults[pair.a.word]
        val rb = sayResults[pair.b.word]
        if (ra != null && rb != null) {
            val words = linkedMapOf(pair.a.word to ra, pair.b.word to rb)
            val contrastId = pair.id.substringBefore(':')
            val row = ProductionRow(
                i = sayIndex + 1,
                contrast = contrastId,
                pair = pair.id,
                a = pair.a.word,
                b = pair.b.word,
                points = ProductionScorer.pairPoints(words, threshold),
                level = sch.levels[contrastId] ?: LevelPolicy.MIN_LEVEL,
                words = words,
            )
            sayRows.add(row)
            _sayIt.update { it?.copy(points = row.points, pointsSoFar = sayRows.sumOf { r -> r.points }, pairsDone = sayRows.size) }
        }
    }

    /** "Hear": the model voice of [word] (the pair's random plan voice) from the clip cache. */
    fun onSayHear(word: String) {
        val c = cache ?: return
        val voice = sayVoice ?: return
        val ui = _sayIt.value ?: return
        if (ui.word(word)?.hearEnabled != true) return
        if (ui.word(word)?.phase is SayWordPhase.Recording) return
        viewModelScope.launch {
            try {
                for (l in sayLoaded.values) player.stop(l)
                player.play(c.get(word, voice))
            } catch (e: Exception) {
                _sayIt.update { it?.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Play the learner's own recording of [word] back. */
    fun onSayPlayRecording(word: String) {
        val loaded = sayLoaded[word] ?: return
        val ui = _sayIt.value ?: return
        if (ui.busy) return
        try {
            player.play(loaded)
        } catch (e: IllegalStateException) {
            _sayIt.update { it?.copy(error = e.message ?: e.javaClass.simpleName) }
        }
    }

    /** Next pair, or the end of the block after the last one. */
    fun onSayNext() {
        val ui = _sayIt.value ?: return
        if (!ui.bothScored || ui.busy || finishing) return
        if (sayJob?.isActive == true || sessionJob?.isActive == true) return
        val sch = scheduler ?: return
        if (sayIndex + 1 < sayPairs.size) {
            sayIndex++
            showSayPair()
        } else {
            sessionJob = viewModelScope.launch { endSayIt(sch) }
        }
    }

    /**
     * "Skip the rest" (and Back): end the block now, keeping the pairs already
     * scored; a recording or preparation in flight is cancelled and the
     * session is written.
     */
    fun onSaySkipRest() {
        if (_screen.value != Screen.SAY_IT || finishing) return
        if (sessionJob?.isActive == true) return
        val sch = scheduler ?: return
        awaitingMic = false
        sayJob?.cancel()
        sayJob = null
        recorder.stop()
        val ui = _sayIt.value
        val unfinished = ui != null && (ui.preparing || !ui.bothScored || sayIndex + 1 < sayPairs.size)
        if (unfinished && sayNotice == null) sayNotice = "sayit:skipped"
        sessionJob = viewModelScope.launch { endSayIt(sch) }
    }

    /**
     * Callers cancel [sayJob] first when they are not running inside it. [sch]
     * is the session the caller belongs to: a caller of a session that is over
     * ends nothing.
     */
    private suspend fun endSayIt(sch: SessionScheduler) {
        if (scheduler !== sch) return
        releaseSayRecordings()
        finishSession(sch)
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
        val rows = sayRows.toList().takeIf { it.isNotEmpty() }

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
            production = rows,
            perceptionEnded = perceptionEnded?.let { maxOf(it, began) },
            productionStarted = productionStarted?.let { maxOf(it, began) },
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
        val productionRows = record.summary.production?.contrasts?.map { (id, p) ->
            SummaryProductionRow(id, cat.contrast(id)?.label ?: id, p.pairs, p.points)
        }.orEmpty()
        val changes = levelChanges(before, newState).map { LevelChangeRow(it.id, cat.contrast(it.id)?.label ?: it.id, it.from, it.to) }
        _summary.value = SummaryUi(
            record = record,
            rows = summaryRows,
            fileName = FolderLayout.sessionFileName(record.id),
            folderOutcome = sessionOutcome,
            mirrored = mirrored,
            stateOutcome = stateOutcome,
            production = productionRows,
            levelChanges = changes,
            sayItNotice = sayNotice,
        )
        cache?.releaseAll()
        cache = null
        player.abandonFocus()
        scheduler = null
        current = null
        currentLoaded = null
        resetSayIt()
        _trial.value = null
        _sayIt.value = null
        _screen.value = Screen.SUMMARY
        recompute()
    }

    override fun onCleared() {
        sayJob?.cancel()
        recorder.stop()
        releaseSayRecordings()
        cache?.releaseAll()
        cache = null
        player.abandonFocus()
        super.onCleared()
    }

    companion object {
        const val AUTO_ADVANCE_MS = 900L
        /** Recordings per word: a recording without speech may be redone once (docs/CONTRACT.md). */
        const val MAX_ATTEMPTS = 2
    }
}
