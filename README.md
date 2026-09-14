# Minimal Pairs

A minimal-pair perception trainer for British English on Android. It follows
high-variability phonetic training (HVPT): you hear one word of a pair
(*ship* / *sheep*, *think* / *sink*) spoken by a random one of six voices, tap
the word you heard, get instant feedback, and repeat for about three minutes.

It is one tool inside a larger English-coaching system. The coach tunes the
drill from outside through a `plan.json` file and reads the results the app
writes to a shared folder; the app itself has no accounts, no backend and no
analytics, and works offline once the clip pack is on the phone.

## What is in the box

Catalog `2026-09-11.2`, generated from Britfone 3.0.1 and the en_50k frequency
list: 13 contrasts, 1123 trainable minimal pairs (1017 distinct sound pairs
once homophone spellings are counted once), 1941 trainable words. `s-cluster`
is production-only and has no pairs.

| Contrast | Sounds | Trainable pairs | Distinct sound pairs |
|---|---|---|---|
| th | θ/t, θ/s, ð/d | 101 | 86 |
| s/z | s/z | 64 | 53 |
| i/ii | ɪ/iː | 101 | 90 |
| b/v | b/v | 33 | 24 |
| cat/cut | æ/ʌ | 97 | 94 |
| long-back | ɒ/ɔː, æ/ɑː | 69 | 61 |
| j/y | dʒ/j | 7 | 6 |
| -ed | final t/d present vs absent | 478 | 469 |
| h | h vs zero | 50 | 38 |
| sh/ch | ʃ/tʃ | 42 | 37 |
| er/or | ɜː/ɔː | 69 | 47 |
| schwa | ə vs a full vowel | 12 | 12 |

`j/y` and `schwa` are limited by the Britfone lexicon, not by the rules.

Clip pack: 1941 words × 6 en-GB neural voices = 12,738 WebM/Opus clips,
64.2 MB (about 5 KB per clip). That is above the 40 MB bundling limit, so
the APK bundles the two highest-weight contrasts (`th` and `s/z`: 278 words,
1668 clips, 8.3 MB) and the whole pack ships as `clips.zip` on the GitHub
Release; the app offers the download on first run. The release APK is about
11 MB.

## Screens

- **Home** – today's drill button, streak, last untrained score per contrast,
  the coach's note, warnings about the folder or the clip pack.
- **Trial** – a large play/replay button, two large word buttons, progress
  (`12/40`), instant feedback with the differing sounds highlighted in IPA;
  with full feedback you can tap either word to hear it.
- **Summary** – overall score, per contrast, mean reaction time, and the name
  of the session file that was written.
- **Say it** – the sentence drill the coach sends in `sayit.zip`: play the model
  clip, read the same sentence aloud, hear the two back to back, and get the
  score on the spot. The phone scores the whole sentence with its own separate
  Azure Speech resource (below) and leaves the recording in the folder for the
  coach as well. With no key, no connection or Azure unavailable it says so
  plainly — the recording is saved and the coach scores it at the next sync —
  and never makes a number up.
- **Settings** – data folder picker and status, the coach plan (read-only), a
  manual override, clip pack status, download and import, whether the pack has
  been saved to the data folder, versions.

## Install on the phone (Android 15)

1. Open this repository's Releases page in Chrome on the phone.
2. Under the latest release, download `minimal-pairs-<version>.apk`.
3. When Chrome asks, allow it to install unknown apps (Settings opens; switch
   it on and go back).
4. Open the downloaded APK from the notification or from Files and tap
   Install.

If a debug-signed build was installed before and the new one is signed with
the release key (or the other way round), Android refuses to update. Uninstall
the old build first; your data is in `Documents/MinimalPairs`, not inside the
app, so nothing is lost. The release notes say which key signed each build.

### First launch

- The app asks for its data folder. Pick, or create, `Documents/MinimalPairs`
  and confirm. The choice is remembered.
- If the data folder already holds a `clips.zip` for this catalog — because
  this phone rendered the pack once before and the app saved it there (below) —
  the app installs it by itself, in the background, with no download and no
  Azure call. Nothing to pick, nothing to tap.
- Otherwise, if the build carries only part of the clip pack (the release notes
  say which contrasts are bundled, or "placeholder"), Settings offers to
  download `clips.zip` from the newest release that has one. This needs a
  network connection once; after that the app is offline. The app only keeps a
  downloaded pack that is complete and covers its own catalog.
- No release has a pack yet (the repository has no Azure secrets)? Two ways:
  Settings → "Azure Speech" lets you paste your own Azure Speech region and
  key and render the whole pack on the phone (see below); or copy a
  `clips.zip` to the phone by any route (Chrome download, Drive) and use
  Settings → "Import clips.zip…". Both go through the same checks as a
  download and land in the same place.

### This phone's own Azure key (no GitHub secrets needed)

The app uses one Azure Speech resource of its own for two things: scoring **Say
it** sentences the moment they are recorded, and rendering the clip pack.

**Create a second, separate resource for the phone.** Its own key, its own
region, and never the key the coach's pipeline runs on. A phone is lost, lent
and backed up; keeping the two apart means the worst case costs this free
resource and nothing else, and the coach's pipeline keeps running whatever
happens to the phone. The free F0 tier covers both uses comfortably.

1. In the Azure portal create a *Speech* resource (the free F0 tier covers
   the whole pack many times over: about 70,000 characters for 1941 words ×
   6 voices) and open its "Keys and Endpoint" page.
2. In the app, Settings → Azure Speech: enter the region (`uksouth`,
   `westeurope`, …) and one of the two keys, Save, then Test. Test asks Azure
   for its voice list and confirms the six British voices exist.
3. Tap "Render clip pack on this phone". About 11,600 short requests, 20 to
   40 minutes on Wi-Fi with the app open (the screen stays on). You can
   cancel and continue later: finished words are kept, the heaviest contrasts
   are rendered first and become drillable as soon as their words are in.

**You only ever have to do this once.** As soon as the app holds a complete
pack it saves it to the data folder as `clips.zip` (~60 MB), by itself, in the
background, once per catalog version — and a fresh install picks it up from
there instead of rendering again. The rendered pack itself lives in the app's
private storage, which Android deletes when the app is uninstalled; the folder
copy is what survives that. Settings shows the save happening and says plainly
if it failed. Rendering is never automatic: it only ever happens when you tap
that button.

The one cost is that your Autosync mirrors those 60 MB to Drive, once. That is
the trade for not spending some 70,000 characters of the free tier's monthly
allowance — and half an hour of your evening — on every reinstall.

The key is stored only in the app's private preferences on the phone (the
app opts out of Android backup), is sent only to
`<region>.tts.speech.microsoft.com` (rendering) and
`<region>.stt.speech.microsoft.com` (scoring a Say-it sentence), and never
appears in the data folder, in session files, or in logs. "Forget key" removes
it; **Say it** then falls back to saving the recording for the coach.

### Sync with the coach (Autosync for Google Drive)

Add one folder pair in Autosync for Google Drive, the same way as the KOReader
settings folder:

| Setting | Value |
|---|---|
| Local folder | `Documents/MinimalPairs` (internal storage) |
| Remote folder | `EnglishPractice/pairs` |
| Sync method | Two-way |
| Autosync | on |

The app writes `state.json`, `catalog-version.txt`, one file per session under
`sessions/`, the clip pack as `clips.zip` once it has a complete one, and for
**Say it** its recordings under `sayit/attempts/` plus one immutable score file
per scored attempt under `sayit/scores/`; the coach writes `plan.json` and
`sayit.zip` and nothing else. Nothing else goes through the folder. `clips.zip`
is by far the largest of them (~60 MB) and is written once per catalog version;
everything else is a few kilobytes. Details:
[`docs/CONTRACT.md`](docs/CONTRACT.md).

## How the coach tunes it

The coach never touches the app. It writes `plan.json` into the Drive folder
(contrast weights, session length, untrained-word ratio, voices, word bands,
feedback level, a note) and the phone picks it up at the next session.

- `scripts/plan-from-ledger.py` builds `plan.json` from the coach's
  pronunciation ledger plus recent session files.
- `scripts/sessions-summary.py` prints a weekly TSV of untrained-word accuracy
  per contrast.
- `scripts/validate-contract.py` checks a folder against the contract.

What the app adapts on its own within the plan (which contrast comes next,
which word, how a struggling contrast gets more trials) is described in
[`docs/ADAPTATION.md`](docs/ADAPTATION.md). The contract between app and coach
is [`docs/CONTRACT.md`](docs/CONTRACT.md); the design brief is
[`docs/DESIGN.md`](docs/DESIGN.md).

## Release pipeline

`.github/workflows/release.yml` runs on every push to `main`, on `v*` tags and
by hand. It runs `scripts/check.sh`, restores or renders the clip pack, builds
the release APK, and publishes a GitHub Release with three assets:
`minimal-pairs-<version>.apk`, `clips.zip` (always this exact name: the app
downloads `releases/latest/download/clips.zip`) and
`clips-<catalog-version>.zip`. The release notes state the signing mode,
whether the pack is full or the placeholder, and what the APK bundles (the
whole pack when it is at most 40 MB, else the two highest-weight contrasts).

Repository secrets (Settings → Secrets and variables → Actions):

| Secret | Purpose |
|---|---|
| `ANDROID_KEYSTORE_B64` | release keystore, base64 on one line |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias (`minimalpairs` by default) |
| `ANDROID_KEY_PASSWORD` | key password: the same value as `ANDROID_KEYSTORE_PASSWORD` (a PKCS12 keystore has one password) |
| `AZURE_SPEECH_KEY` | Azure AI Speech key, used to render the clip pack |
| `AZURE_SPEECH_REGION` | Azure region of that key, as shown on the Speech resource's "Keys and Endpoint" page, e.g. `westeurope` |

All six are optional. Without the keystore secrets the APK is signed with a
debug key (the release notes say so). Without the Azure secrets, and with no
cached pack, the build reuses the previous release's `clips-<catalog-version>.zip`
when it is a complete pack for the same catalog; failing that it ships the tiny
committed placeholder pack and the release is *not* marked latest, so
`releases/latest/download/clips.zip` keeps serving the last real pack. The
rendered pack is cached by catalog version and voice list (a partial render is
cached too and completed on the next run), so Azure is only called when the
catalog changes.

Create the keystore once with `scripts/make-keystore.sh`; it prints the exact
secrets to add and how to base64 the file. Keep the `.jks` file and its
passwords out of git and back them up: without them a later build cannot
update the installed app.

## Building locally

Requirements: JDK 17 or newer, Android SDK 35 (set `sdk.dir` in
`local.properties` or `ANDROID_HOME`), Python 3.9+ (standard library only).

```sh
./gradlew assembleDebug            # app/build/outputs/apk/debug/minimal-pairs-<version>-debug.apk
scripts/check.sh                   # script selftests, catalog check, contract examples, unit tests
SKIP_GRADLE=1 scripts/check.sh     # the same without the Android unit tests

python3 scripts/build-catalog.py --version 2026-09-11.1   # regenerate data/catalog from data/sources
AZURE_SPEECH_KEY=... AZURE_SPEECH_REGION=... \
python3 scripts/render-clips.py --out clips --install-assets   # render the pack and copy it into the app
```

When `app/src/main/assets/clips/` is absent the build uses
`data/placeholder-clips/` so it never fails; that build offers the pack
download on the phone.

## Repository layout

```
app/                      Android module (Kotlin, Jetpack Compose, Gradle Kotlin DSL)
data/sources/             vendored Britfone and frequency list, licences alongside
data/catalog/             generated catalog.json + catalog-version.txt (committed)
data/placeholder-clips/   tiny committed clip pack so builds never fail
data/examples/            example plan.json, state.json and a session file
clips/                    rendered clip pack (gitignored, never committed)
scripts/                  build-catalog.py, render-clips.py, plan-from-ledger.py,
                          sessions-summary.py, validate-contract.py, check.sh, make-keystore.sh
docs/                     DESIGN.md, CONTRACT.md, ADAPTATION.md
.github/workflows/        release.yml
```

## Licences

The source is MIT (see `LICENSE`). Pronunciations come from Britfone 3.0.1
(MIT) and word frequencies from hermitdave's FrequencyWords (MIT), both
vendored in `data/sources/`. The clips are synthesised with Azure neural voices
and shipped as a release asset. Details in [`THIRD_PARTY.md`](THIRD_PARTY.md).
