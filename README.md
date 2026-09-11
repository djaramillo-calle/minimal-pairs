# Minimal Pairs

A minimal-pair perception trainer for British English on Android. It follows
high-variability phonetic training (HVPT): you hear one word of a pair
(*ship* / *sheep*, *think* / *sink*) spoken by a random one of six voices, tap
the word you heard, get instant feedback, and repeat for about three minutes.

It is one tool inside a larger English-coaching system. The coach tunes the
drill from outside through a `plan.json` file and reads the results the app
writes to a shared folder; the app itself has no accounts, no backend and no
analytics, and works offline once the clip pack is on the phone.

<!-- STATS -->

## Screens

- **Home** – today's drill button, streak, last untrained score per contrast,
  the coach's note, warnings about the folder or the clip pack.
- **Trial** – a large play/replay button, two large word buttons, progress
  (`12/40`), instant feedback with the differing sounds highlighted in IPA;
  with full feedback you can tap either word to hear it.
- **Summary** – overall score, per contrast, mean reaction time, and the name
  of the session file that was written.
- **Settings** – data folder picker and status, the coach plan (read-only), a
  manual override, clip pack status and download, versions.

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
- If the build carries only part of the clip pack (the release notes say
  "placeholder"), Settings offers to download `clips.zip` from the latest
  release. This needs a network connection once; after that the app is offline.

### Sync with the coach (Autosync for Google Drive)

Add one folder pair in Autosync for Google Drive, the same way as the KOReader
settings folder:

| Setting | Value |
|---|---|
| Local folder | `Documents/MinimalPairs` (internal storage) |
| Remote folder | `EnglishPractice/pairs` |
| Sync method | Two-way |
| Autosync | on |

The app writes `state.json`, `catalog-version.txt` and one file per session
under `sessions/`; the coach writes `plan.json`. Nothing else goes through the
folder. Details: [`docs/CONTRACT.md`](docs/CONTRACT.md).

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
`clips-<catalog-version>.zip`. The release notes state the signing mode and
whether the pack is full or the placeholder.

Repository secrets (Settings → Secrets and variables → Actions):

| Secret | Purpose |
|---|---|
| `ANDROID_KEYSTORE_B64` | release keystore, base64 on one line |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias (`minimalpairs` by default) |
| `ANDROID_KEY_PASSWORD` | key password |
| `AZURE_SPEECH_KEY` | Azure AI Speech key, used to render the clip pack |
| `AZURE_SPEECH_REGION` | Azure region of that key, e.g. `uksouth` |

All six are optional. Without the keystore secrets the APK is signed with a
debug key (the release notes say so). Without the Azure secrets, and with no
cached pack, the build ships the tiny committed placeholder pack. The rendered
pack is cached by catalog version and voice list, so Azure is only called when
the catalog changes.

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
