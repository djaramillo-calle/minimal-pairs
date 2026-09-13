# What the app adapts on its own, and what only the coach changes

Principle: **the app optimises the drill; it never changes the plan.** The plan
(`plan.json`, see `docs/CONTRACT.md`) says which contrasts matter and how much,
how long a session is, what share of it is an honest probe, which voices and
which word bands. The app decides, inside that remit, which contrast the next
trial is on, which pair, which word, which voice, and how many trials a
contrast gets when the learner is clearly struggling or clearly done with it.

## The coach's levers (never touched by the app)

| Lever | Where | What the app does with it |
|---|---|---|
| contrast weights 0–1 | `plan.weights` | base probability of each contrast |
| trials per session | `plan.trials_per_session` | session length, fixed |
| untrained ratio | `plan.untrained_ratio` | share of trials whose target word was never heard before |
| voices | `plan.voices` | the pool for the random voice per trial |
| difficulty band ceiling | `plan.band` | the bands a contrast may ever use; the level ladder picks within it |
| level ceiling and pins | `plan.max_level`, `plan.levels` | how far the ladder may go; a pinned contrast never moves |
| consistency target | `plan.weekly_minutes_target` | shown on Home, judged by the progress report |
| feedback verbosity | `plan.feedback` | how much the Trial screen shows after an answer |
| note | `plan.note` | shown on Home |

The learner can switch on a **manual override** in Settings (own weights, trials
per session, contrasts on/off). While it is on, sessions are recorded with
`plan_source: "override"` so the coach can see it and talk about it. The app
never silently mixes override and plan.

## Within a session (the app decides)

Effective weight of contrast `c` for the next trial:

```
w_eff(c) = w_plan(c) × m_history(c) × m_session(c)
```

- `w_plan(c)`: the plan weight (or catalog default). `0` means excluded, full stop.
- `m_session(c)`: starts at 1. Once the contrast has ≥ 4 trials in this session:
  accuracy < 80 % → 1.5; accuracy > 95 % with ≥ 6 trials → 0.5; otherwise 1.
  Recomputed before every draw. This is the "struggling contrasts get more,
  mastered ones fewer" rule.
- Floor: every contrast with `w_plan > 0` keeps a draw probability of at least
  3 %, so nothing the coach asked for silently vanishes from a session.
- Draw: the contrast is sampled in proportion to `w_eff`. The scheduler avoids
  the same contrast more than 3 times in a row.

Untrained probe: the scheduler keeps a running count and makes the next trial an
untrained-word trial whenever `untrained_so_far < untrained_ratio × trials_so_far`
(so the session ends near the target share, not front-loaded). The probe widens
the way the pair pool does: when the contrast's own rung holds no untrained word
left, the probe is drawn from the first higher rung's bands within the ceiling
that does, so the honest probe never dries up while the ceiling still holds an
untrained word for that contrast. The rung itself, its pace and its promotion
rules are untouched — only that one trial reaches further down the frequency
list. Only when the whole ceiling is exhausted for the contrast is the
least-exposed word used instead and `summary.untrained_shortfall` incremented.

Pair and word: within the contrast, eligible pairs are those with both words in
an allowed band. Pairs already used in this session are avoided until all have
been used. The target side of the pair is chosen at random; on an untrained
trial it is chosen among the untrained sides. Foil position on screen (left or
right) is random.

Voice: uniform random over `plan.voices ∩ pack voices`. A replay plays the same
clip (same voice) — the stimulus never changes within a trial.

Reaction time: milliseconds from the audio onset of the first play to the tap.
Replays do not reset it.

## Across sessions (the app decides)

`m_history(c)` comes from `state.json` and is recomputed at session start:

| Evidence (untrained percent, most recent sessions that included `c`) | `m_history` |
|---|---|
| last session < 80 % | 1.25 |
| last two sessions both > 95 % | 0.75 |
| otherwise, or no history | 1.0 |

Clamped to [0.5, 2.0] after multiplying with `m_session`. The modifiers change
the *share of trials*, never the plan file, never the targets.

## The level ladder (the app decides, within the coach's ceiling)

Every contrast sits on a rung 1–4, stored in `state.json` and snapshotted in
each session. The rung decides the word pool and the pace, so a contrast
that is being mastered keeps getting harder instead of plateauing, and one
that slips gets narrower again:

| Level | Word bands used (∩ `plan.band`) | Share of trials | Meaning |
|---|---|---|---|
| 1 | high | normal | the commonest words only |
| 2 | high, mid | normal | |
| 3 | high, mid, low | normal | the whole lexicon |
| 4 | high, mid, low | × 0.5 (`m_level`) | maintenance: still probed, fewer trials |

A contrast whose rung has no trainable pair in the bands the ceiling leaves
(j/y and schwa have no high/high pair; a ceiling of `["mid"]` leaves level 1
nothing) draws from the first higher rung's bands that hold a pair, until its
own rung catches up. The rung itself, its pace and its promotion rules do not
change, and nothing the coach weighted above 0 is left out for want of words.

Moves happen after a session, one step at a time, never for a pinned
contrast, never above `plan.max_level`:

A level moves only on evidence **this session produced**. A session that added
no untrained result for a contrast does not re-judge the unchanged list (it
would otherwise move the contrast again on the same evidence):

- **Promotion** when this session probed the contrast with untrained words and
  the last three sessions that probed it all had untrained accuracy ≥ 90 %.
- **Demotion** when this session probed the contrast and the last two untrained
  results were both < 60 % (the regression guard).

New contrasts start at level 1, or at `plan.levels[id]` when pinned.

## Say it — what adapts

Nothing. **Say it** is a separate mode driven entirely by the coach's
`sayit.zip` (`docs/CONTRACT.md`, "Say it"): the coach picks the words and their
sentences, the app shows the first `per_session` of the `active` ones in the
contract's fixed order (`miss_rate` descending, then `added` descending,
`flagged_on` descending and `id` ascending), and records what the learner says. The app does not choose words, does
not score, and does not touch `plan.json`, `state.json` or `sessions/` for it.
The Say-it loop and the perception ladder are independent.

## Consistency (tracked, not judged, by the app)

`state.json.practice` tallies every completed perception session by UTC day:
sessions, seconds and perception trials; plus the longest streak and total
seconds. Home shows the current streak and this week's minutes against
`plan.weekly_minutes_target`. The coach's `scripts/progress-report.py` turns
the tally into the consistency part of its analysis and never lets a good
score hide a bad week: a regression flag on a week with one session reads
as a consistency problem, not a skill problem.

Word exposure: `state.json.words` records every target exposure. Words are
"trained" once exposed; the untrained probe therefore naturally moves through
the catalog, rung by rung and then, for the probe alone, beyond the rung within
the ceiling. `summary.untrained_shortfall` is reported only when a contrast has
no untrained word left anywhere in `plan.band` — the coach then widens the
band or lowers `untrained_ratio` (the coach's `plan-from-ledger.py` widens
automatically once the recent shortfall reaches 10 % of trials). The app does
**not** widen the ceiling by itself.


## What the app never does

- change `plan.json`, or any value in it, or write a plan of its own;
- change the session length, the untrained ratio, the band ceiling, the
  voices, the feedback level, or move a pinned level;
- drop a contrast the coach weighted above 0;
- write `sayit.zip`, or score a Say-it attempt (the cloud does that);
- score the learner. Percentages are formative signals for the coach's
  ledger; the app shows them plainly and does not rank, grade or gamify beyond
  the streak count on Home.
