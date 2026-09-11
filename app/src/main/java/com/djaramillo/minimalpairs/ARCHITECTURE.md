# App architecture — `com.djaramillo.minimalpairs`

One Gradle module, four packages on top of a pure-Kotlin domain layer. The
fixed decisions are in `docs/DESIGN.md`; the file contract with the coach in
`docs/CONTRACT.md`; the scheduling rules in `docs/ADAPTATION.md`.

```
MainActivity.kt        single activity, Compose, screen switch on AppViewModel.screen, RECORD_AUDIO prompt
AppContainer.kt        process singletons: Prefs, DataFolder, ClipPack, ClipDownloader, PackRenderer, Player, Recorder, catalog
domain/                pure Kotlin, no android.* (see below)
storage/               SAF data folder + private mirror + SharedPreferences
clips/                 clip pack discovery (assets + downloaded), zip download / validation, Azure TTS + STT REST
audio/                 WebM/Opus → PCM decode, silence trim, static AudioTrack playback, LRU cache,
                       AudioRecord capture with an RMS end-of-utterance gate, WAV wrapping
ui/                    AppViewModel (all state as StateFlow) + Home / Trial / Say it / Summary / Settings screens
```

## Domain layer — `domain` (pure Kotlin, unit-tested in `app/src/test`)

The UI, audio and storage layers sit on top of this and never re-implement any of it.

### Models (`domain.model`, all `@Serializable`, keys match `docs/CONTRACT.md`)

| Class | File / meaning |
|---|---|
| `Catalog`, `Contrast`, `Pair`, `PairWord` | `assets/catalog.json`. `Catalog.contrast(id)`, `Catalog.trainableContrasts`, `Catalog.allTrainableWords()`, `Contrast.trainablePairs`, `Contrast.trainableWords()`, `Pair.other(word)`, `Pair.side(word)`. Note the class is named `Pair` — import `com.djaramillo.minimalpairs.domain.model.Pair` explicitly to shadow `kotlin.Pair`. |
| `Plan` | `plan.json`, every field nullable. Parse with `AppJson.json`; a parse failure means "no plan" → pass `null` to `effectivePlan`. Levers: `trials_per_session`, `untrained_ratio`, `voices`, `band`, `feedback`, `weights`, `production_pairs`, `production_threshold`, `max_level`, `levels`, `weekly_minutes_target`. |
| `Override(enabled, weights, trialsPerSession)` | the learner's manual override from Settings (stored in SharedPreferences by `storage.Prefs`). |
| `EffectivePlan` | resolved plan: `planSource` (`"coach"`/`"default"`/`"override"`, see `PlanSource`), `planWritten`, `note`, `trialsPerSession`, `untrainedRatio`, `voices`, `bands` (the **ceiling**, default all three), `feedback: Feedback`, `weights` (every catalog contrast), `activeContrastIds`, `productionPairs` / `productionOn`, `productionThreshold`, `maxLevel`, `levels` (pins), `weeklyMinutesTarget`. `PlanDefaults` holds the defaults and ranges. |
| `LearnerState`, `ContrastState`, `WordState`, `PairState`, `Practice`, `DayTally` | `state.json`. `{}` parses to a fresh state. `exposures(word)`, `isTrained(word)`. `ContrastState` carries the ladder rung (`level`, `levelChanged`) and the Say-it tallies (`productionPairs`, `productionPoints`, `lastProductionPct`, `recentProductionPct`); `pairs` is the per-pair Say-it history; `practice` the consistency tally by UTC day. |
| `SessionRecord`, `TrialRow`, `ProductionRow`, `WordResult`, `Summary`, `ContrastSummary`, `ProductionSummary`, `ProductionContrastSummary` | `sessions/<id>.json`. `levels` is the ladder snapshot at session start; `production` and `summary.production` are `null` when the Say-it block was off or skipped (the writer emits the `null`). |

`AppJson.json` (pretty, 2-space) for files in the folder; `AppJson.compact` for prefs/logs.
Both: `ignoreUnknownKeys`, `isLenient`, `explicitNulls = false`, `encodeDefaults = true`.

### Session flow

```kotlin
val plan = effectivePlan(planOrNull, catalog, packVoices = merged.voices, override = prefs.override)
val scheduler = SessionScheduler(catalog, plan, state, availableWords = merged.words, random = Random)
// scheduler.skipped: Map<contrastId, reason> → contrasts left out (weight 0, no clips, no pair in band)
// scheduler.levels: ladder snapshot (contrast → 1–4) for the record; scheduler.poolBands: bands used per contrast
val started = Instant.now()
for (i in 1..plan.trialsPerSession) {
    val t: PlannedTrial = scheduler.next(i)      // play t.target in t.voice; buttons t.leftWord / t.rightWord
    val row: TrialRow = scheduler.record(t, chosen, rtMs, replays)
}
val perceptionEnded = Instant.now()
// Say it (plan.productionOn && Azure key && RECORD_AUDIO && network), see "Say it" below:
val misses = scheduler.answeredTrials.filter { !it.correct }.map { it.pair }.toSet()
val mods = scheduler.schedulable.associate { it.id to scheduler.sessionModifier(it.id) }
val pairs = ProductionPlanner.pick(plan.productionPairs, catalog, plan, state, misses, Random, availableWords = merged.words, sessionModifiers = mods)
val productionStarted = Instant.now()
// per pair, per word: record → Wav.wrapPadded → AzureSpeech.assessPair → ProductionScorer.score → ProductionRow
val record = RecordBuilder.build(started, Instant.now(), appVersion, catalog.version,
    plan.planSource, plan.planWritten, plan.voices, scheduler.answeredTrials, scheduler.untrainedShortfall,
    levels = scheduler.levels, production = rowsOrNull, perceptionEnded = perceptionEnded, productionStarted = productionStarted)
val newState = StateUpdater.apply(state, record, catalog, plan)   // the plan drives pins, max_level, productionOn
```

- `SessionScheduler` throws `IllegalStateException("Nothing to schedule…")` from the constructor when no
  contrast has weight > 0, trainable pairs in the bands and clips for all its words. `AppViewModel`
  constructs one at every recompute to surface that message on Home and disable the start button.
  Each contrast draws from `LevelPolicy.poolBands(level, plan.bands, contrast)` and
  `effectiveWeight = w_plan × clamp(m_history × m_session, 0.5, 2.0) × m_level`.
- `next(i)` draws lazily and `m_session` depends on the answers, so trials cannot be planned ahead
  of the answers (see the audio path below for how latency is handled anyway).
- `LevelPolicy` (docs/ADAPTATION.md "The level ladder"): `bandsFor(level, ceiling)`, `poolBands(level, ceiling, contrast)`
  (widened to the first higher rung that holds a pair), `levelModifier(level)` (0.5 at 4), `currentLevel(id, state, plan)`
  (pin, else stored level, capped at `max_level`), `nextLevel(current, recentUntrainedPct, recentProductionPct, productionOn, pinned, maxLevel)`
  (promotion / demotion exactly at the documented thresholds, one step per session; production evidence counts only while Say it is on).
- `ProductionScorer` (docs/CONTRACT.md "Say it"): `phonemeOfInterest(pair, word)` → `PhonemeTarget(symbol, position, phonemeCount)`
  in Azure's en-US IPA (`azureSymbol`: iː→i, ɜː→ɝ, ɔː→ɔ, ɒ→ɑ, ɑː→ɑ); `pickPhonemeScore(azurePhonemes, target)` (symbol first,
  nearest occurrence; position only when Azure's list is as long as Britfone's); `score(WordInput, intended, other, contrastId, catalog, threshold)`
  → `WordResult` (three-signal majority vote, homophones via catalog IPA, phoneme signal skipped for `long-back` / `schwa`);
  `points`, `pairPoints`, `normaliseRecognised`, `matches`.
- `ProductionPlanner` (docs/ADAPTATION.md "Say it — what adapts"): `pick(n, catalog, plan, state, perceptionMisses, random, availableWords, sessionModifiers)`
  — shares `w_eff × (1 + (1 − last_production_pct))`, the four within-contrast tiers, no repeats, a pair scored 2 not offered in the very
  next session; `queue(contrast, …)` and `contrastShare(…)` expose the parts.
- `RecordBuilder.build(…, levels, production, perceptionEnded, productionStarted)`: `production` null or empty → `"production": null`
  and `summary.production: null`; `perception_duration_s = perceptionEnded − started`; `summary.production.duration_s = ended − productionStarted`.
- `StateUpdater.apply(state, record, catalog, plan)`: per-contrast production stats (recent cap 5), `pairs`, `practice` (last 120 days,
  `longest_streak`, `total_seconds`), level transitions stamped with `level_changed` (only contrasts with new evidence are re-judged;
  pins and a lowered `max_level` apply to every stored contrast).
- `Ipa` (tokeniser + `highlight(ipaA, ipaB, diff)` for the coloured phoneme), `TimeUtil` (ISO timestamps, `20260911T070211Z` ids, UTC days).

## Storage — `storage`

- `Prefs`: SharedPreferences — the picked tree URI, the resolved root document URI, the created
  subfolder name, and the `Override`.
- `FolderLayout` (pure, tested): file names, `subfolderFor(treeDisplayName)` (`Documents` →
  create `MinimalPairs`; anything else is used as is), `displayPath(treeDocumentId, subfolder)`.
- `DataFolder`: everything SAF. `PickFolder` is an `ActivityResultContract` for
  `ACTION_OPEN_DOCUMENT_TREE` with `EXTRA_INITIAL_URI = primary:Documents` and read/write/persistable
  flags; `onFolderPicked` takes the persistable permission and resolves/creates the subfolder.
  `status()` → `NotChosen | Ok(path) | PermissionLost | Unwritable` (permission is checked against
  `persistedUriPermissions`, then the root is queried). Files are created with MIME
  `application/octet-stream` so the provider keeps names like `state.json.tmp` verbatim.
  - `readPlan()` → `PlanResult(plan?, message?)`; parse failure = no plan + a message.
  - `writeState()` = `state.json.tmp` → delete old → `DocumentsContract.renameDocument`; if the
    provider refuses the rename, the final file is opened with `"wt"` and overwritten.
  - `writeSession()` creates `sessions/<id>.json` once; an existing name is skipped.
  - `writeCatalogVersion()` rewrites `catalog-version.txt` only when the content differs.
  - Mirror: `filesDir/mirror/state.json` and `filesDir/mirror/sessions/<id>.json` are written first
    (tmp + rename). `republishMissingSessions()` lists the folder's `sessions/` once and creates
    every mirror file that is missing; it runs on every launch, after every session and from the
    Settings button. Idempotent.
  - All I/O on `Dispatchers.IO`; nothing here throws for folder trouble — outcomes are returned.
- State ownership: the mirror is the source of truth. On first run with an existing folder
  `state.json` and no mirror, the folder copy is adopted into the mirror.

## Clips — `clips`

- `ClipIndex` (`index.json`) and `MergedIndex` (pure, tested): bundled ∪ downloaded. A word is
  *available* when every merged voice has it from some source; that set is the scheduler's
  `availableWords`. `sourceFor(word, voice)` prefers the bundled asset.
- `ClipPack`: reads `assets/clips/index.json` and `filesDir/clips/index.json`; `open(word, voice)`
  returns an `AssetFileDescriptor` (assets are stored uncompressed: `noCompress += "webm"`) or a
  `File`. If an asset unexpectedly cannot be opened as a descriptor it is copied to the cache once.
- `ZipRules` (pure, tested): entry validation — only `index.json`, `sha256.txt` and
  `<voice>/<word>.webm` (with `./` and `clips/` prefixes tolerated), no `..`, no absolute paths,
  8 MB per entry, 200 MB total, 30 000 entries. `parseSha256` reads the sha256sum-style manifest.
- `ClipDownloader`: `HttpURLConnection` GET of
  `https://github.com/djaramillo-calle/minimal-pairs/releases/latest/download/clips.zip`, manual
  redirect loop (GitHub answers 302 to a different host), streams into `clips.zip.part` with byte
  progress, unzips into `clips.staging/` under `ZipRules`, verifies `sha256.txt` when present, then
  swaps the staging directory in as `clips/`. Runs in `viewModelScope` on `Dispatchers.IO`,
  cancellable at every read; state is a `StateFlow` (`Idle`, `Downloading(bytes, total?)`,
  `Unpacking(files)`, `Verifying`, `Done`, `Failed(message)`). Only ever started from a tap.
- `AzureTts` + `RenderPlan` (pure, tested) + `PackRenderer`: rendering the pack on the phone with the
  learner's own key (Settings → Azure Speech); the key only ever goes into the
  `Ocp-Apim-Subscription-Key` header, never into a log or an exception.
- `AzureSpeech(region, key)`: the Say-it calls, same conventions and retry / `Fatal` pattern as `AzureTts`
  (3 attempts on 429 / 5xx / network with `RenderPlan.retryDelayMs`; 401 / 403 / 404 / unknown host are
  `Fatal`; 20 s connect / 60 s read). `assess(wav, referenceText)` is the en-US pronunciation assessment
  (`Pronunciation-Assessment` header = base64 of `assessmentJson`: HundredMark, Phoneme, Comprehensive,
  `EnableMiscue: false`, `PhonemeAlphabet: IPA`), `recognise(wav)` the en-GB recognition without a header,
  `assessPair(wav, intended, other)` runs the three concurrently (`async`) and `reachable()` is a cheap
  DNS + TCP probe of the STT host used before the block starts. `parse(body)` (pure, tested on the live
  response shape) → `Result(status, acc = NBest[0].AccuracyScore, phonemes = Words[0].Phonemes as
  (Phoneme, AccuracyScore), recognised = NBest[0].Lexical normalised)`; `null` for an unreadable body
  (retried), a non-`Success` status gives a `Result` with no scores.

## Audio path — `audio`

```
ClipPack.open(word, voice)            asset fd or file
  → ClipDecoder.decode(source)        MediaExtractor (setDataSource(fd, offset, length) | path)
                                      → first audio/* track → MediaCodec sync loop → ShortArray
                                      → Pcm.toMono → Pcm.trim
  → Player.prepare(clip)              AudioTrack(MODE_STATIC, USAGE_MEDIA/CONTENT_TYPE_SPEECH,
                                      PERFORMANCE_MODE_LOW_LATENCY), PCM written once
  → Player.play(loaded): Long         stop() + reloadStaticData() + play(); returns elapsedRealtime()
```

- `Pcm` (pure, tested): the onset is the first sample above −50 dBFS inside the first 5 ms window
  whose RMS exceeds −50 dBFS (a single stray click cannot trip it); leading audio before it is cut
  keeping 10 ms; trailing samples below −60 dBFS are cut keeping 30 ms; stereo is averaged to
  mono. The sample rate is whatever the decoder reports (24 kHz for the pack); the AudioTrack
  resamples.
- `ClipDecoder` handles 16-bit, float and 8-bit codec output defensively and fails with an
  `IOException` (never a crash) when a clip has no audio track or the decoder stalls.
- `ClipCache`: LRU of at most 8 decoded + track-bound clips keyed by (word, voice), in-flight
  decodes deduplicated, tracks released on eviction and on `releaseAll()` (session end / abandon /
  ViewModel cleared). The bookkeeping lives in the pure `AsyncLru<K, V>` (tested); a failed decode
  is forgotten at once so the next prefetch decodes again (that is what makes Retry meaningful).
- Audio focus: `Player.play()` takes `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` (once, cheap when held)
  so other apps' music ducks; `Player.abandonFocus()` runs at session end / abandon, when the
  ViewModel is cleared and on the activity's `ON_STOP` (`LifecycleEventEffect` in `MainActivity`).

### Recording (Say it)

```
Recorder.record(onLevel)              AudioRecord(VOICE_RECOGNITION, 16 kHz, mono, PCM16), one per recording,
                                      20 ms frames read with READ_BLOCKING on Dispatchers.IO
  → SpeechGate.feed(frame)            RMS gate: noise floor = the 20th percentile of the first 150 ms of
                                      frames (not their mean: a word that starts with the recording must not
                                      raise the floor over itself), capped at 500; speech = rms > 3 × floor
                                      (≥ 250 ≈ −42 dBFS), held at 1.5 × floor; stop after 600 ms of quiet
                                      following ≥ 200 ms of speech, or at 3 s
  → Recorder.Recording               pcm + speechMs; hasSpeech = speechMs ≥ 150 ms ("no speech" otherwise)
  → Recording.toWav()                 Wav.pad (400 ms of zeros each side) + Wav.wrap (44-byte RIFF header)
  → Recording.toClip()                PreparedClip(pcm, 16000) → Player.prepare → play(): the learner hears himself
```

- `Recorder.stop()` ends the running recording at the next frame (tap-to-stop, activity `ON_STOP`).
  Cancelling the coroutine lands within one frame; `stop()` + `release()` run in `finally` on every exit.
  `hasPermission()` is checked before the `AudioRecord` is built; a missing permission, a busy
  microphone or an unsupported format surface as `IOException`, never a crash.
- `SpeechGate` and `Wav` are pure (tested on synthetic signals / header bytes); `Recorder` is the
  only file that touches `android.media`.

### Latency design

The scheduler is lazy and its next draw depends on the answer, so the "decode the whole session
at start" idea from the design brief is realised as a rolling one-trial look-ahead:

1. `startSession()`: build the scheduler, draw trial 1, decode its target **and** its foil (same
   voice) on `Dispatchers.Default` behind a spinner in the play button; when the target is ready
   the trial auto-plays and `firstPlayAt` is taken from `Player.play`.
2. `onAnswer()`: reaction time = `elapsedRealtime() − firstPlayAt` (the first play; replays never
   reset it), `scheduler.record(...)`, then immediately `scheduler.next(i+1)` and prefetch of both
   clips of trial `i+1` — they decode during the feedback pause (≥ 900 ms auto-advance or the
   user's Next tap), so trial `i+1` is ready before it is shown.
3. Replay and the full-feedback "tap a word to hear it" are `play()` on tracks already in the
   cache: sound starts well within the 150 ms budget (an AudioTrack `play()` on a pre-filled
   static buffer starts in a few tens of milliseconds).
4. If the next trial's decode is still running when Next is tapped, `showTrial` awaits it and the
   auto-play happens the moment it lands; the button shows the spinner meanwhile.
5. If a clip cannot be opened, decoded or played (missing / truncated file, `AudioTrack` failure)
   the trial enters `TrialPhase.Failed(message)`: play and word buttons are disabled and the screen
   offers **Retry** (`retryTrial()`: decode again) and **Skip** (`skipTrial()`: `scheduler.next(i)`
   with the same index — nothing is recorded for the failed draw, so the record still has rows
   1..N; the scheduler's running tallies keep the failed draw, a one-trial bias). The session is
   never lost to one bad file.

## UI — `ui`

- `AppViewModel` (`AndroidViewModel`): bootstrap (catalog from assets → pack indexes → state:
  mirror, else adopt the folder's, else fresh → `plan.json` → `catalog-version.txt` →
  republish → status), `recompute()` builds `HomeUi` / `SettingsUi` from the effective plan, and
  the session loop (`startSession`, `onPlayTapped`, `onAnswer`, `onHearWord`, `onNext`,
  `retryTrial`, `skipTrial`, `abandonSession`, then the Say-it block, then `finishSession`). Session end:
  `RecordBuilder` → `StateUpdater` → mirror state + session → folder state + session → republish →
  `SummaryUi`. `finishSession` runs under `NonCancellable` and a `finishing` flag: once started the
  files are written exactly once.
- `Screen` is a five-value enum (`HOME, TRIAL, SAY_IT, SUMMARY, SETTINGS`); `MainActivity` switches on
  it. Back on Trial asks to confirm and discards the session (nothing is written); Back on Say it asks
  and then ends the block keeping the scored pairs (the session **is** written); back on
  Summary/Settings goes Home.
- Screens: `HomeScreen` (streak, sessions, this week's minutes against `weekly_minutes_target` with a
  bar, longest streak, coach note, per contrast: level `L2`, trend arrow of the last two untrained
  results, last untrained %), `TrialScreen`, `SayItScreen`, `SummaryScreen` (overall, untrained, RT,
  per contrast, Say-it points / max overall and per contrast, level changes "th moved to level 2",
  why the block was skipped, file outcome), `SettingsScreen` (the plan section shows every lever
  read-only, Say-it, ladder and weekly target included); `Theme.kt` is a fixed palette (no dynamic
  colour) with light/dark schemes following the system; `Common.kt` holds the IPA `AnnotatedString`
  builder (differing phoneme in tertiary colour + bold) and the pure helpers behind those screens
  (`trendOf`, `weekMinutes` / `weekStart`, `levelChanges`, `sayHint`), unit tested in `ui/CommonTest`.
- All user-visible text is in `res/values/strings.xml`; the ViewModel passes codes
  (`sayit:denied`, `republish:3`, `azure:ok:…`) that the screens map to strings.

### Say it (`SAY_IT`)

After the last trial's Next, `onNext()` stores `perceptionEnded` and calls `beginSayIt()`:

1. Off when `plan.production_pairs == 0` or no Azure region + key is stored → `finishSession()`
   straight away (`production: null`, no notice).
2. `RECORD_AUDIO`: `Recorder.hasPermission()`; otherwise `micPrompt` (the pending request id, 0 =
   none) is set, the activity's `RequestPermission` launcher shows the system prompt once per
   request (`shouldPromptMic(request, launched)` against a `rememberSaveable` id, so a rotation does
   not prompt twice and a restored activity above a fresh ViewModel still prompts) and answers with
   `onMicPermission(granted)`, which clears the request; denial → `sayit:denied`, the session is
   written without the block.
3. `prepareSayIt()` (in `sayJob`, so "Skip the rest" can cancel it): `AzureSpeech.reachable()`
   (no → `sayit:no-network`), then `ProductionPlanner.pick(...)` with this session's missed pair ids
   and `m_session` per contrast, words with clips preferred (falls back to all words on a partial
   pack, hear buttons disabled where a clip is missing); nothing to say → `sayit:nothing`.
   `productionStarted` is taken here.
4. Per pair (`showSayPair`): one random plan voice for both model clips (prefetched through the
   `ClipCache`, so "Hear" is a `play()`), IPA highlights from `Ipa.highlight`. Per word (`onSayRecord`
   → `recordAndScore` in `sayJob`): `Recorder.record` with the level meter → the recording is bound to
   an `AudioTrack` for playback → no speech: one redo allowed, a second empty recording is scored
   without an Azure call (`heard "?"`, `attempts 2`) → otherwise `AzureSpeech.assessPair`; an answer
   where Azure heard nothing (`PairResult.heardNothing`: no `Success`, or accuracy 0 with no
   recognised text — a breath or a chair passes the local gate) counts as a no-speech recording and
   gets the same single redo →
   `ProductionScorer.phonemeOfInterest` / `pickPhonemeScore` for `ph` and `ph_other` →
   `ProductionScorer.score` → `SayWordPhase.Scored(result, point, hint)`. Both words scored →
   `ProductionRow(i, contrast, pair, a, b, pairPoints, scheduler.levels[contrast], words)`.
5. Next → next pair or `endSayIt()`; "Skip the rest" / Back → `endSayIt()` with `sayit:skipped`
   when anything was left; an `IOException` from Azure after the retries (no network, `Fatal`) →
   `sayit:azure:<message>` and `endSayIt()`. Every step of `recordAndScore` and `prepareSayIt`
   carries the block's `AzureSpeech` instance as a token and does nothing at all once it is no
   longer the current one, and `endSayIt` / `finishSession` take the session's `SessionScheduler`
   and refuse a caller that is not the current session: a call cancelled with the block can still
   come back as its own `IOException` (the three requests block on IO), and that failure must never
   write the session the learner has started since. Only complete pairs go into the record; an empty list
   becomes `production: null`.
6. `finishSession()` passes `levels`, the rows, `perceptionEnded` and `productionStarted` to
   `RecordBuilder.build` and the plan to `StateUpdater.apply`; the Summary gets the production
   summary, the level changes (`levelChanges(before, after)`) and the notice.

Recordings live only in memory (the tracks are released when the pair changes and at block end);
nothing is written to the data folder or the cache. `onBackground()` stops a running recording
(Android mutes the microphone of a background app anyway; the activity skips it on a configuration
change, where `ON_STOP` only means a rotation); `abandonSession()` and `onCleared()`
cancel `sayJob`, stop the recorder and release the tracks.

## Tests (`app/src/test`, JUnit 4, JVM only)

Domain: `IpaTest`, `JsonTest`, `PlanTest`, `RecordBuilderTest`, `SchedulerTest`, `StateUpdaterTest`,
`LevelPolicyTest`, `ProductionScorerTest`, `ProductionPlannerTest` (on the in-code `Fixture` catalog).
App layers: `PcmTest` (silence trimming, windowed onset, downmix), `WavTest` (header bytes, sizes, padding),
`SpeechGateTest` (the RMS gate on synthetic signals: stop 600 ms after a word, cap at 3 s, clicks are not
speech, floor-relative thresholds, a word that starts with or inside the calibration window), `AsyncLruTest` (dedupe, eviction, failed-load retry, release),
`ZipRulesTest` (entry validation, sha256 manifest), `RenderPlanTest`, `AzureSpeechTest` (parsing of the
live response shape, "Azure heard nothing", the assessment header, endpoints), `FolderLayoutTest` (Documents vs
Documents/MinimalPairs, display paths, session file names), `ClipIndexTest` (merged index),
`ui/CommonTest` (trend, week minutes, level changes, Say-it hints).
