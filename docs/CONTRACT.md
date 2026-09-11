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
  deletes them. The coach may archive old ones on Drive; the app does not care.
- Every JSON file carries `"version": 1`. Readers ignore unknown keys. A file
  that fails to parse is treated as absent (the app falls back to defaults and
  shows a warning in Settings; it never crashes and never overwrites a coach file).
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
| `band` | list of `"high"`, `"mid"`, `"low"` | `["high", "mid"]` | which frequency bands of words may be drilled (see catalog `band`) |
| `feedback` | `"full"`, `"brief"`, `"minimal"` | `"full"` | `full`: correct/incorrect + both words with the differing sounds highlighted in IPA + tap either word to hear it; `brief`: correct/incorrect + IPA; `minimal`: tick/cross only |
| `weights` | map contrast id → float 0–1 | catalog `default_weight` per contrast | `0` excludes a contrast. Weights are relative: trials are drawn in proportion. Unknown contrast ids are ignored. Contrasts missing from the map keep their catalog default |

Values out of range are clamped. A plan with no usable contrast (all weights 0)
falls back to catalog defaults and the session record says `plan_source: "default"`.

## `state.json` — rolling learner state (app → coach)

Rewritten by the app after every completed session (and on first launch).
Small, one object. The coach reads it; it must never write it.

```json
{
  "version": 1,
  "updated": "2026-09-11T07:05:30Z",
  "app_version": "0.1.0",
  "catalog_version": "2026-09-11.1",
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
      "words_trained": 34, "words_total": 96
    }
  },
  "words": {
    "ship": {"exposures": 4, "correct": 3, "last": "2026-09-11T07:03:10Z"},
    "sheep": {"exposures": 3, "correct": 3, "last": "2026-09-10T07:01:44Z"}
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
| `last_pct` / `last_untrained_pct` | from the most recent session that included the contrast (`null` if none) |
| `recent_untrained_pct` | last up-to-5 sessions' untrained percent, oldest first |
| `mean_rt_ms` | lifetime mean reaction time (tap minus audio onset) on this contrast |
| `words_trained` / `words_total` | trainable words of this contrast with ≥1 exposure / in the catalog |
| `words.<word>` | per-word exposure: `exposures` counts trials where the word was the **target**; `correct` those answered correctly; `last` the last such trial |

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
  "ended": "2026-09-11T07:05:28Z",
  "app_version": "0.1.0",
  "catalog_version": "2026-09-11.1",
  "plan_source": "coach",
  "plan_written": "2026-09-11T18:30:00Z",
  "voices": ["en-GB-SoniaNeural", "en-GB-RyanNeural"],
  "trials": [
    {"i": 1, "contrast": "th", "pair": "th:think-sink", "target": "think", "other": "sink",
     "chosen": "sink", "correct": false, "voice": "en-GB-RyanNeural", "rt_ms": 1210,
     "replays": 0, "trained": false, "band": "high", "position": "initial"}
  ],
  "summary": {
    "trials": 40, "correct": 31, "pct": 0.775,
    "untrained_trials": 20, "untrained_correct": 14, "untrained_pct": 0.7,
    "duration_s": 197, "mean_rt_ms": 950,
    "untrained_shortfall": 0,
    "contrasts": {
      "th": {"trials": 10, "correct": 7, "untrained_trials": 5, "untrained_correct": 3, "mean_rt_ms": 1010}
    }
  }
}
```

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

`summary.untrained_shortfall` counts trials that were meant to be untrained but
no untrained word was left in the chosen contrast and band, so the scheduler
used the least-exposed word instead. When it grows, the coach should widen
`band` or lower `untrained_ratio`.

## `catalog-version.txt`

One line, the catalog version string bundled in the installed app
(`2026-09-11.1`). Written on every launch. The coach uses it to know which pair
set the state and sessions refer to; pair ids are stable across catalog versions
as long as both words and the contrast stay the same.

## Coach side

- `scripts/plan-from-ledger.py --ledger logs/pronunciation-ledger.json --sessions <dir> --out plan.json`
  turns the coach's pronunciation ledger (`{"phonemes": {"<class>": {"count": n, "words": {...}}}}`)
  plus recent session files into `plan.json`: weights proportional to ledger
  counts and recent misses, with a floor so every trainable contrast keeps
  presence. `--selftest` exercises it on synthetic data.
- `scripts/sessions-summary.py <sessions dir>` prints one TSV row per ISO week
  and contrast: percent correct on untrained words, trials, untrained trials,
  mean reaction ms, sessions. `--selftest` included.

Both are stdlib-only Python 3.9+, like the rest of the coaching system.
