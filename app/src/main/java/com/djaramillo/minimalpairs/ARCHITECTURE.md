# App architecture — `com.djaramillo.minimalpairs`

One Gradle module, four packages on top of a pure-Kotlin domain layer. The
fixed decisions are in `docs/DESIGN.md`; the file contract with the coach in
`docs/CONTRACT.md`; the scheduling rules in `docs/ADAPTATION.md`.

```
MainActivity.kt        single activity, Compose, screen switch on AppViewModel.screen
AppContainer.kt        process singletons: Prefs, DataFolder, ClipPack, ClipDownloader, Player, catalog
domain/                pure Kotlin, no android.* (see below)
storage/               SAF data folder + private mirror + SharedPreferences
clips/                 clip pack discovery (assets + downloaded), zip download / validation
audio/                 WebM/Opus → PCM decode, silence trim, static AudioTrack playback, LRU cache
ui/                    AppViewModel (all state as StateFlow) + Home / Trial / Summary / Settings screens
```

## Domain layer — `domain` (pure Kotlin, unit-tested in `app/src/test`)

The UI, audio and storage layers sit on top of this and never re-implement any of it.

### Models (`domain.model`, all `@Serializable`, keys match `docs/CONTRACT.md`)

| Class | File / meaning |
|---|---|
| `Catalog`, `Contrast`, `Pair`, `PairWord` | `assets/catalog.json`. `Catalog.contrast(id)`, `Catalog.trainableContrasts`, `Catalog.allTrainableWords()`, `Contrast.trainablePairs`, `Contrast.trainableWords()`, `Pair.other(word)`, `Pair.side(word)`. Note the class is named `Pair` — import `com.djaramillo.minimalpairs.domain.model.Pair` explicitly to shadow `kotlin.Pair`. |
| `Plan` | `plan.json`, every field nullable. Parse with `AppJson.json`; a parse failure means "no plan" → pass `null` to `effectivePlan`. |
| `Override(enabled, weights, trialsPerSession)` | the learner's manual override from Settings (stored in SharedPreferences by `storage.Prefs`). |
| `EffectivePlan` | resolved plan: `planSource` (`"coach"`/`"default"`/`"override"`, see `PlanSource`), `planWritten`, `note`, `trialsPerSession`, `untrainedRatio`, `voices`, `bands`, `feedback: Feedback`, `weights` (every catalog contrast), `activeContrastIds`. |
| `LearnerState`, `ContrastState`, `WordState` | `state.json`. `{}` parses to a fresh state. `exposures(word)`, `isTrained(word)`. |
| `SessionRecord`, `TrialRow`, `Summary`, `ContrastSummary` | `sessions/<id>.json`. |

`AppJson.json` (pretty, 2-space) for files in the folder; `AppJson.compact` for prefs/logs.
Both: `ignoreUnknownKeys`, `isLenient`, `explicitNulls = false`, `encodeDefaults = true`.

### Session flow

```kotlin
val plan = effectivePlan(planOrNull, catalog, packVoices = merged.voices, override = prefs.override)
val scheduler = SessionScheduler(catalog, plan, state, availableWords = merged.words, random = Random)
// scheduler.skipped: Map<contrastId, reason> → contrasts left out (weight 0, no clips, no pair in band)
val started = Instant.now()
for (i in 1..plan.trialsPerSession) {
    val t: PlannedTrial = scheduler.next(i)      // play t.target in t.voice; buttons t.leftWord / t.rightWord
    val row: TrialRow = scheduler.record(t, chosen, rtMs, replays)
}
val record = RecordBuilder.build(started, Instant.now(), appVersion, catalog.version,
    plan.planSource, plan.planWritten, plan.voices, scheduler.answeredTrials, scheduler.untrainedShortfall)
val newState = StateUpdater.apply(state, record, catalog)
```

- `SessionScheduler` throws `IllegalStateException("Nothing to schedule…")` from the constructor when no
  contrast has weight > 0, trainable pairs in the bands and clips for all its words. `AppViewModel`
  constructs one at every recompute to surface that message on Home and disable the start button.
- `next(i)` draws lazily and `m_session` depends on the answers, so trials cannot be planned ahead
  of the answers (see the audio path below for how latency is handled anyway).
- `RecordBuilder`, `StateUpdater`, `Ipa` (tokeniser + `highlight(ipaA, ipaB, diff)` for the
  coloured phoneme), `TimeUtil` (ISO timestamps, `20260911T070211Z` ids, UTC days).

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
  `retryTrial`, `skipTrial`, `abandonSession`, `finishSession`). Session end: `RecordBuilder` → `StateUpdater` → mirror
  state + session → folder state + session → republish → `SummaryUi`.
- `Screen` is a four-value enum; `MainActivity` switches on it. Back on Trial asks to confirm and
  discards the session (nothing is written); back on Summary/Settings goes Home.
- Screens: `HomeScreen`, `TrialScreen`, `SummaryScreen`, `SettingsScreen`; `Theme.kt` is a fixed
  palette (no dynamic colour) with light/dark schemes following the system; `Common.kt` holds the
  IPA `AnnotatedString` builder (differing phoneme in tertiary colour + bold) and small helpers.
- All user-visible text is in `res/values/strings.xml`.

## Tests (`app/src/test`, JUnit 4, JVM only)

Domain: `IpaTest`, `JsonTest`, `PlanTest`, `RecordBuilderTest`, `SchedulerTest`, `StateUpdaterTest`
(on the in-code `Fixture` catalog). App layers: `PcmTest` (silence trimming, windowed onset, downmix),
`AsyncLruTest` (dedupe, eviction, failed-load retry, release), `ZipRulesTest` (entry validation, sha256 manifest), `FolderLayoutTest` (Documents vs
Documents/MinimalPairs, display paths, session file names), `ClipIndexTest` (merged index).
