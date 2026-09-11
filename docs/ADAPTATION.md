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
| difficulty band | `plan.band` | which frequency bands of words are eligible |
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
(so the session ends near the target share, not front-loaded). If the chosen
contrast and band have no untrained word left, the least-exposed word is used
and `summary.untrained_shortfall` is incremented.

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
the *share of trials*, never the plan file, never the targets, never the bands.

Word exposure: `state.json.words` records every target exposure. Words are
"trained" once exposed; the untrained probe therefore naturally moves through
the catalog. When a contrast runs out of untrained words in the allowed bands,
the shortfall is reported and the coach widens the band (the coach's
`plan-from-ledger.py` does so automatically once the recent shortfall reaches
10 % of trials; with the default plan this happens after roughly 15 sessions).
The app does **not** widen it by itself.


## What the app never does

- change `plan.json`, or any value in it, or write a plan of its own;
- change the session length, the untrained ratio, the bands, the voices or the
  feedback level;
- drop a contrast the coach weighted above 0;
- score the learner. Percentages are formative signals for the coach's
  ledger; the app shows them plainly and does not rank, grade or gamify beyond
  the streak count on Home.
