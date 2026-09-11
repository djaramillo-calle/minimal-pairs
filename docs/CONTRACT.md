# Coach integration contract — the files in `Documents/MinimalPairs/`

This is the only interface between the app and the coach. The phone folder
`Documents/MinimalPairs/` is mirrored two-way to Google Drive
`EnglishPractice/pairs/` by Autosync for Google Drive (DriveSync), exactly like
KOReader's `koreader/settings` folder in the coaching system. The cloud coach
pulls `sessions/` and `state.json` from Drive and writes `plan.json` there.

```
Documents/MinimalPairs/
├── plan.json               written by the COACH, read by the app (never written by the app)
├── state.json              written by the app after every session, read by the coach
├── catalog-version.txt     written by the app on every launch: the catalog the results refer to
└── sessions/
    ├── 20260911T070211Z.json   one file per completed session, append-only, never rewritten
    └── ...
```

Rules that never change:

- The app never writes `plan.json`. The coach never writes anything else.
- Session files are immutable once written. The app never edits, renames or
  deletes them. The coach may archive old ones on Drive; the app does not care:
  a session that reached the folder once is not put back when it later
  disappears (the app keeps a private copy and remembers that it was
  published). Only sessions that never reached the folder are republished at
  launch; the "republish" button in Settings is the explicit way to restore
  every missing session from the phone's private copies.
- Every JSON file carries `"version": 1`. Readers ignore unknown keys. A file
  that fails to parse is treated as absent (the app falls back to defaults and
  shows a warning in Settings; it never crashes and never overwrites a coach file).
  Files the app writes carry every key of their schema; a value that has no
  meaning yet is written as `null` (`"plan_written": null`,
  `"untrained_pct": null`), never omitted and never a stand-in `0`.
- `plan.json` is re-read at every launch, whenever the app returns to the
  foreground and at the start of every session, so a plan DriveSync delivers
  while the app is open drives the next session.
- Timestamps are UTC ISO 8601 (`2026-09-11T07:02:11Z`). File names use the
  basic form without colons (`20260911T070211Z`) because Android external
  storage and Drive reject `:` in names.
- Contrast ids are the coach's confusion-class names where one exists
  (`th`, `b/v`, `i/ii`, `j/y`, `s/z`, `-ed`, `schwa`, `s-cluster`, `h`,
  `cat/cut`) plus the app's additions (`long-back`, `sh/ch`, `er/or`). Ids are
  the keys in `plan.json` weights, `state.json` contrasts and session rows.

## `plan.json` — the coach's levers (coach → app)

```json
{
  "version": 1,
  "written": "2026-09-11T18:30:00Z",
  "written_by": "coach",
  "note": "Week 3: th and s/z from the Tuesday recording. Keep the pace.",
  "trials_per_session": 40,
  "untrained_ratio": 0.5,
  "voices": ["en-GB-SoniaNeural", "en-GB-RyanNeural", "en-GB-LibbyNeural",
             "en-GB-ThomasNeural", "en-GB-HollieNeural", "en-GB-AlfieNeural"],
  "band": ["high", "mid"],
  "feedback": "full",
  "weights": {
    "th": 1.0, "s/z": 0.8, "i/ii": 0.6, "b/v": 0.5, "cat/cut": 0.4,
    "long-back": 0.3, "j/y": 0.3, "-ed": 0.3, "h": 0.2, "sh/ch": 0.2,
    "er/or": 0.2, "schwa": 0.15
  }
}
```

| Key | Type | Default when absent | Meaning |
|---|---|---|---|
| `version` | int | — | always `1` |
| `written` | timestamp | `null` | copied into every session record as `plan_written`, so the coach can tell which plan produced a session |
| `written_by` | string | `"coach"` | free text, informational |
| `note` | string | `""` | shown on the Home screen, verbatim, max ~200 chars displayed |
| `trials_per_session` | int 10–120 | `40` | trials in one session (≈3 minutes at 40) |
| `untrained_ratio` | float 0–1 | `0.5` | share of trials whose target word has never been heard before in the app |
| `voices` | list of voice ids | all voices in the clip pack | subset to use; unknown ids are ignored; if the intersection with the pack is empty the app uses the whole pack |
| `band` | list of `"high"`, `"mid"`, `"low"` | `["high", "mid", "low"]` | which frequency bands of words may ever be drilled (the ceiling; the level ladder picks within it) |
| `feedback` | `"full"`, `"brief"`, `"minimal"` | `"full"` | `full`: correct/incorrect + both words with the differing sounds highlighted in IPA + tap either word to hear it; `brief`: correct/incorrect + IPA; `minimal`: tick/cross only |
| `weights` | map contrast id → float 0–1 | catalog `default_weight` per contrast | `0` excludes a contrast. Weights are relative: trials are drawn in proportion. Unknown contrast ids are ignored. Contrasts missing from the map keep their catalog default |
| `production_pairs` | int 0–30 | `8` | pairs in the **Say it** block that follows the perception trials; `0` switches production off |
| `production_threshold` | int 0–100 | `60` | Azure accuracy a word needs to earn its point (see "Say it") |
| `max_level` | int 1–4 | `4` | ceiling of the per-contrast level ladder the app runs (`docs/ADAPTATION.md`) |
| `levels` | map contrast id → int 1–4 | `{}` | pinned levels: the app never moves a pinned contrast |
| `weekly_minutes_target` | int 0–300 | `20` | consistency target shown on Home and used by the progress report |

`band` is the **ceiling** of word bands; the app's level ladder decides which
of them a contrast actually uses (level 1 = `high` only, level 2 adds `mid`,
level 3 adds `low`). Its default is therefore all three bands.

Values out of range are clamped. A plan with no usable contrast (all weights 0)
falls back to catalog defaults and the session record says `plan_source: "default"`.

## `state.json` — rolling learner state (app → coach)

Rewritten by the app after every completed session; until the first one there
is no `state.json` (a fresh install adopts an existing one from the folder
instead). Small, one object. The coach reads it; it must never write it.

```json
{
  "version": 1,
  "updated": "2026-09-11T07:05:30Z",
  "app_version": "0.1.0",
  "catalog_version": "2026-09-11.2",
  "sessions_completed": 12,
  "streak_days": 3,
  "last_session": "2026-09-11T07:02:11Z",
  "plan_source": "coach",
  "contrasts": {
    "th": {
      "trials": 120, "correct": 98,
      "untrained_trials": 60, "untrained_correct": 45,
      "last_pct": 0.8, "last_untrained_pct": 0.75,
      "recent_untrained_pct": [0.6, 0.7, 0.75],
      "mean_rt_ms": 910,
      "words_trained": 34, "words_total": 96,
      "level": 2, "level_changed": "2026-09-09T07:05:00Z",
      "production_pairs": 24, "production_points": 31,
      "last_production_pct": 0.75, "recent_production_pct": [0.5, 0.625, 0.75]
    }
  },
  "words": {
    "ship": {"exposures": 4, "correct": 3, "last": "2026-09-11T07:03:10Z"},
    "sheep": {"exposures": 3, "correct": 3, "last": "2026-09-10T07:01:44Z"}
  },
  "pairs": {
    "th:think-sink": {"attempts": 3, "last_points": 2, "best": 2, "fails": 1, "last": "2026-09-11T07:04:50Z"}
  },
  "practice": {
    "longest_streak": 9,
    "total_seconds": 5400,
    "days": {
      "2026-09-11": {"sessions": 1, "seconds": 290, "perception_trials": 40, "production_pairs": 8}
    }
  }
}
```

| Key | Meaning |
|---|---|
| `sessions_completed` | lifetime count of completed sessions |
| `streak_days` | consecutive UTC days with at least one completed session, ending today or yesterday |
| `plan_source` | what the last session ran on: `"coach"` (a valid `plan.json`), `"default"` (no or invalid plan), `"override"` (the learner's manual override in Settings) |
| `contrasts.<id>.trials` / `correct` | lifetime totals |
| `untrained_trials` / `untrained_correct` | lifetime totals on trials whose target word was untrained at the time (the honest probe) |
| `last_pct` / `last_untrained_pct` | from the most recent session that included the contrast (`null` if none, and `last_untrained_pct` stays `null` until a session probed the contrast with an untrained word) |
| `recent_untrained_pct` | last up-to-5 sessions' untrained percent, oldest first |
| `mean_rt_ms` | lifetime mean reaction time (tap minus audio onset) on this contrast |
| `words_trained` / `words_total` | trainable words of this contrast with ≥1 exposure / in the catalog |
| `words.<word>` | per-word exposure: `exposures` counts trials where the word was the **target**; `correct` those answered correctly; `last` the last such trial |
| `contrasts.<id>.level` / `level_changed` | the contrast's rung on the level ladder (1–4, `docs/ADAPTATION.md`) and when it last moved |
| `production_pairs` / `production_points` | lifetime Say-it pairs and points (2 per pair max) on this contrast |
| `last_production_pct` / `recent_production_pct` | points ÷ max points of the most recent session with Say-it pairs on this contrast; last up-to-5, oldest first |
| `pairs.<pair id>` | Say-it history per pair: `attempts`, `last_points` (0–2), `best`, `fails` (sessions with < 2 points), `last` |
| `practice` | consistency: `longest_streak` (days), `total_seconds` of practice, and `days` keyed by UTC date for the last 120 days with sessions, seconds, perception trials and production pairs |

A word is **trained** when `exposures ≥ 1` at the start of a session. A trial's
`trained` flag is decided at session start, so a word heard for the first time
in trial 3 still counts as untrained if it comes up again in trial 30 of the same
session (the scheduler avoids that anyway).

## `sessions/<id>.json` — one completed session (app → coach)

Written once when the Summary screen is reached. Abandoned sessions (app
killed, back button before the last trial) are not written. `id` equals the
file name without extension and is the session's `started` timestamp in basic
ISO form.

```json
{
  "version": 1,
  "id": "20260911T070211Z",
  "started": "2026-09-11T07:02:11Z",
  "ended": "2026-09-11T07:03:15Z",
  "app_version": "0.1.0",
  "catalog_version": "2026-09-11.2",
  "plan_source": "coach",
  "plan_written": "2026-09-11T18:30:00Z",
  "voices": ["en-GB-SoniaNeural", "en-GB-RyanNeural"],
  "trials": [
    {"i": 1, "contrast": "th", "pair": "th:think-sink", "target": "think", "other": "sink",
     "chosen": "sink", "correct": false, "voice": "en-GB-RyanNeural", "rt_ms": 1210,
     "replays": 0, "trained": false, "band": "high", "position": "initial"}
  ],
  "levels": {"th": 2, "s/z": 1},
  "production": [
    {"i": 1, "contrast": "th", "pair": "th:think-sink", "a": "think", "b": "sink", "points": 1, "level": 2,
     "words": {
       "think": {"heard": "sink", "acc": 81, "acc_other": 96, "ph": 24, "ph_other": 99, "votes": "phoneme:other word:other recognition:other", "recognised": "sink", "ms": 1380, "attempts": 1},
       "sink":  {"heard": "sink", "acc": 98, "acc_other": 82, "ph": 93, "ph_other": 44, "votes": "phoneme:intended word:intended recognition:intended", "recognised": "sink", "ms": 1100, "attempts": 1}
     }}
  ],
  "summary": {
    "trials": 1, "correct": 0, "pct": 0.0,
    "untrained_trials": 1, "untrained_correct": 0, "untrained_pct": 0.0,
    "duration_s": 64, "perception_duration_s": 14, "mean_rt_ms": 1210,
    "untrained_shortfall": 0,
    "contrasts": {
      "th": {"trials": 1, "correct": 0, "untrained_trials": 1, "untrained_correct": 0, "mean_rt_ms": 1210}
    },
    "production": {
      "pairs": 1, "points": 1, "max_points": 2, "pct": 0.5, "duration_s": 46,
      "contrasts": {"th": {"pairs": 1, "points": 1}}
    }
  }
}
```

The example is deliberately one trial and one Say-it pair long, and nothing in
it is abbreviated: every worked example in this document is checked with
`scripts/validate-contract.py`, and the summary of a real session counts all
`trials_per_session` trial rows and all `production_pairs` production rows the
same way (`summary.duration_s` is `ended` − `started`, and
`perception_duration_s` plus `summary.production.duration_s` never exceed it).

`levels` is the ladder snapshot at session start. `production` and
`summary.production` are `null` when the block was off (`production_pairs: 0`)
or skipped (no microphone permission, no Azure key, no network); a block the
learner abandoned half-way keeps the pairs that were scored.

Trial row fields:

| Field | Meaning |
|---|---|
| `i` | 1-based trial index |
| `contrast` | contrast id |
| `pair` | catalog pair id (`<contrast>:<wordA>-<wordB>`, words in catalog order) |
| `target` | the word that was played |
| `other` | the other word of the pair (the foil shown on screen) |
| `chosen` | the word the learner tapped |
| `correct` | `chosen == target` |
| `voice` | Azure voice id of the clip |
| `rt_ms` | milliseconds from audio onset of the first play to the tap |
| `replays` | how many times the learner replayed before answering |
| `trained` | whether `target` had ≥1 exposure before this session |
| `band` | frequency band of the target (`high`, `mid`, `low`) |
| `position` | where the differing sound sits: `initial`, `medial`, `final` |

## Say it — production rows

After the perception trials the app shows up to `production_pairs` pairs. For
each pair the learner records each word separately (up to 3 s; the app plays
the model voice on request and plays the recording back). The recording is
padded with 400 ms of silence on both sides (Azure drops abrupt clips) and
assessed three times with the learner's Azure key. This is the coach's rule,
measured on synthesized clips of every contrast before it was fixed: a single
signal is not enough (reference-text assessment alone scored "ship" against
"sheep" at 100; plain recognition of an isolated word misses homophones and
guesses on vowels), but three signals with a majority vote are.

| Signal | Call | Vote for the intended word when… |
|---|---|---|
| phoneme | en-US pronunciation assessment, IPA phonemes, reference = intended word, and again with reference = the other word | the differing phoneme scores ≥ 15 points higher under the intended reference than the other word's differing phoneme under the other reference (`ph` vs `ph_other`); the reverse votes for the other word. **Insertion pairs** (`-ed`, `h`: walk/walked, eat/heat) have the phoneme on one side only: it is scored under the reference that contains it and votes for that word at ≥ 60, for the other word below 40, and not at all in between |
| word | the same two calls, word-level accuracy | `acc ≥ acc_other + 10`; reverse votes for the other word |
| recognition | en-GB recognition without reference text | the recognised word is the intended word, or a catalog word with the same IPA (homophones such as court/caught); the other word votes for the other |

`heard` is the majority of the votes cast (a signal with no clear winner does
not vote); a tie or no vote gives `"?"`. For `long-back` and `schwa` the
phoneme signal is skipped (en-US models have no ɒ or ɑː/æ split of the
British kind) and the other two decide. A word earns **1 point** when `heard`
is the intended word **and** `acc ≥ production_threshold`. A pair scores 0,
1 or 2.

Row fields under `words.<word>`:

| Field | Meaning |
|---|---|
| `heard` | the intended word, the other word, or `"?"` |
| `acc` / `acc_other` | word accuracy (0–100) under the intended / the other reference |
| `ph` / `ph_other` | accuracy of the differing phoneme under each reference; `null` when Azure returned no phonemes or the contrast skips the signal. On an insertion pair (`-ed`, `h`) only one of the two references contains the phoneme: its score goes in `ph` when the intended word carries it (`walked` in walk/walked) and in `ph_other` when the other word does, and the other field is `null` |
| `votes` | the three votes, always in the order `phoneme`, `word`, `recognition`, each `intended`, `other` or `none`, e.g. `"phoneme:intended word:intended recognition:other"`. `none` is a signal that did not vote: no clear winner by the rule above, no phonemes returned, a one-sided phoneme (insertion pair) scoring between 40 and 60, a recognition that matched neither word, or the phoneme signal being skipped for `long-back` and `schwa` (there it is always `phoneme:none`). On a substitution pair the phoneme signal needs both `ph` and `ph_other`; on an insertion pair it votes on the single score that exists |
| `recognised` | the en-GB recognition text, lower-cased, punctuation stripped; `null` when Azure recognised nothing (then the recognition vote is `none`) |
| `ms` | recording length before padding |
| `attempts` | recordings made for this word (a recording with no speech may be redone once; a scored one is never redone) |

Recordings are not kept in the folder (they stay in the app's cache until the
next session). The mapping from the catalog's Britfone phonemes to Azure's
en-US IPA is fixed in the app (`iː→i`, `ɜː→ɝ`, `ɔː→ɔ`, `ɒ→ɑ`, `ɑː→ɑ`, the
rest identical); when the symbol is not found the phoneme is located by its
position in the word.

`summary.untrained_pct` is `null` when the session had no untrained trial
(`untrained_ratio: 0`, or every eligible word already trained); it is never
`0.0` in that case.

`summary.untrained_shortfall` counts trials that were meant to be untrained but
the chosen contrast had no untrained word left **anywhere in `plan.band`**, so
the scheduler used the least-exposed word instead. The probe is not confined to
the contrast's level rung: when the rung's bands hold no untrained word it is
drawn from the first higher rung's bands within the ceiling that does
(`docs/ADAPTATION.md`), so a shortfall means the contrast's whole allowed
lexicon has been heard, not that its rung is narrow. When it grows, the coach
should widen `band` or lower `untrained_ratio`; `plan-from-ledger.py` does the
widening by itself (see below) and `sessions-summary.py` shows the number per
week. With the default `band` (all three) it takes a long run of sessions and
happens first on the small contrasts (j/y has 13 trainable words, b/v 56), so
it is the normal end of a contrast's lexicon, not an error.

## `catalog-version.txt`

One line, the catalog version string bundled in the installed app
(`2026-09-11.2`). Written on every launch. The coach uses it to know which pair
set the state and sessions refer to; pair ids are stable across catalog versions
as long as both words and the contrast stay the same.

## Coach side

- `scripts/plan-from-ledger.py --ledger logs/pronunciation-ledger.json --sessions <dir> --out plan.json`
  turns the coach's pronunciation ledger (`{"phonemes": {"<class>": {"count": n, "words": {...}}}}`)
  plus recent session files into `plan.json`. The weight of a contrast is

  ```
  score  = ledger count + 2 × incorrect untrained trials + 3 × Say-it pairs
           scored below 2          (the trials and pairs of the last --days days, default 14)
  weight = 0.15 + 0.85 × score / the largest score          rounded to 2 decimals
  ```

  so weights are proportional to ledger counts and recent misses, with a floor
  (0.15) so every trainable contrast keeps presence; the mouth counts for more
  than the ear because it is the ledger's own class of evidence. A contrast
  whose last untrained probe dropped ≥ 15 points against the mean of the
  previous two (over **all** sessions in the folder, not only the window) then
  gets its weight × 1.5, capped at 1.0. Production-only contrasts get 0, and
  with no evidence at all the catalog defaults are used, still subject to the
  regression rule. When the recent sessions' `untrained_shortfall` reaches
  10 % of their trials it widens `band` to `high,mid,low` (unless `--band` was
  given, in which case it warns loudly). `--selftest` exercises it on
  synthetic data.
- `scripts/sessions-summary.py <sessions dir>` prints one TSV row per ISO week
  and contrast: percent correct on untrained words, trials, untrained trials,
  mean reaction ms, sessions, and the week's total `untrained_shortfall`.
  `--selftest` included.
- `scripts/validate-contract.py <folder> [--catalog data/catalog/catalog.json]`
  checks a folder against this document (`data/examples/` must pass; the
  nullable keys must be present, as the app writes them).
- `scripts/progress-report.py <folder> [--weeks 6] [--out report.md] [--plan-out plan.json]`
  is the coach's analysis: consistency (days and minutes per week, streak,
  longest streak, against `weekly_minutes_target`), per contrast the level,
  the untrained-perception and production trends over the last weeks, and
  flags — `regression` (a drop of ≥ 15 points against the previous two
  sessions), `plateau` (three *consecutive* weeks with probes, within ±5
  points of each other below 90 % at the same level; at most one week without
  a probe may sit between them, and three flat probes spread wider than that
  are reported as a consistency problem instead of a plateau), `mastered`
  (≥ 95 % untrained and ≥ 85 % production for three sessions), `untested` (no
  production yet) — with the plan changes it recommends; `--plan-out` writes
  them as a `plan.json`. That plan starts from the current `plan.json`'s
  levers **and weights**: a weight moves only where this report's evidence
  justifies it (× 1.5 on regression, × 1.25 on a plateau, to the floor with
  the level pinned at 4 when mastered), and a contrast the current plan does
  not name is filled in with `plan-from-ledger.py`'s score rule. With
  `--ledger` the coach's counts are the fresh evidence and every weight comes
  from that rule instead. It also switches `feedback` to `brief` on a plateau
  at level ≥ 3, adds 4 `production_pairs` (max 30) when production lags
  perception by ≥ 20 points, and widens `band` to `high,mid,low` when the
  recent sessions report `untrained_shortfall` on ≥ 10 % of their trials —
  naming the widening among the recommended changes. It reads the same folder
  the app writes, so the cloud coach can run it straight from Drive.


All of them are stdlib-only Python 3.9+, like the rest of the coaching system.
