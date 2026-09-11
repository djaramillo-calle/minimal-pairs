package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.ContrastState
import com.djaramillo.minimalpairs.domain.model.DayTally
import com.djaramillo.minimalpairs.domain.model.LearnerState
import com.djaramillo.minimalpairs.domain.model.PairState
import com.djaramillo.minimalpairs.domain.model.Plan
import com.djaramillo.minimalpairs.domain.model.Practice
import com.djaramillo.minimalpairs.domain.model.ProductionRow
import com.djaramillo.minimalpairs.domain.model.TrialRow
import com.djaramillo.minimalpairs.domain.model.WordResult
import com.djaramillo.minimalpairs.domain.model.WordState
import com.djaramillo.minimalpairs.domain.model.effectivePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StateUpdaterTest {
    private val catalog = Fixture.catalog

    private fun row(i: Int, contrast: String, target: String, other: String, correct: Boolean, trained: Boolean, rt: Int) = TrialRow(
        i = i, contrast = contrast, pair = "$contrast:$target-$other", target = target, other = other,
        chosen = if (correct) target else other, correct = correct, voice = "en-GB-SoniaNeural",
        rtMs = rt, replays = 0, trained = trained, band = "high", position = "initial",
    )

    private fun record(startedIso: String, rows: List<TrialRow>, shortfall: Int = 0) = RecordBuilder.build(
        Instant.parse(startedIso), Instant.parse(startedIso).plusSeconds(180),
        "0.1.0", "2026-09-11.1", "coach", null, listOf("en-GB-SoniaNeural"), rows, shortfall,
    )

    @Test
    fun streakLogic() {
        assertEquals(1, StateUpdater.streakAfter(0, null, "2026-09-11T07:00:00Z"))
        assertEquals(3, StateUpdater.streakAfter(3, "2026-09-11T06:00:00Z", "2026-09-11T23:59:59Z")) // same UTC day
        assertEquals(4, StateUpdater.streakAfter(3, "2026-09-10T23:59:59Z", "2026-09-11T00:00:01Z")) // next day
        assertEquals(1, StateUpdater.streakAfter(3, "2026-09-09T07:00:00Z", "2026-09-11T07:00:00Z")) // gap
        assertEquals(1, StateUpdater.streakAfter(0, "2026-09-11T06:00:00Z", "2026-09-11T07:00:00Z")) // same day, never counted
        assertEquals(1, StateUpdater.streakAfter(5, "garbage", "2026-09-11T07:00:00Z"))
    }

    @Test
    fun appliesSessionToFreshState() {
        val rows = listOf(
            row(1, "th", "think", "sink", true, false, 1000),
            row(2, "th", "mouth", "mouse", false, false, 1400),
            row(3, "s/z", "sip", "zip", true, true, 900),
            row(4, "th", "think", "sink", true, false, 600),
        )
        val rec = record("2026-09-11T07:02:11Z", rows)
        val st = StateUpdater.apply(LearnerState(), rec, catalog)
        assertEquals(1, st.version)
        assertEquals(1, st.sessionsCompleted)
        assertEquals(1, st.streakDays)
        assertEquals("2026-09-11T07:02:11Z", st.lastSession)
        assertEquals("2026-09-11T07:05:11Z", st.updated)
        assertEquals("coach", st.planSource)
        assertEquals("0.1.0", st.appVersion)
        assertEquals("2026-09-11.1", st.catalogVersion)

        val think = st.words.getValue("think")
        assertEquals(2, think.exposures); assertEquals(2, think.correct); assertEquals("2026-09-11T07:05:11Z", think.last)
        assertEquals(WordState(1, 0, "2026-09-11T07:05:11Z"), st.words.getValue("mouth"))
        assertNull(st.words["sink"]) // foils are not exposures

        val th = st.contrasts.getValue("th")
        assertEquals(3, th.trials); assertEquals(2, th.correct)
        assertEquals(3, th.untrainedTrials); assertEquals(2, th.untrainedCorrect)
        assertEquals(0.6667, th.lastPct!!, 1e-9)
        assertEquals(0.6667, th.lastUntrainedPct!!, 1e-9)
        assertEquals(listOf(0.6667), th.recentUntrainedPct)
        assertEquals(1000, th.meanRtMs)
        assertEquals(16, th.wordsTotal)   // 8 trainable pairs × 2
        assertEquals(2, th.wordsTrained)  // think, mouth

        val sz = st.contrasts.getValue("s/z")
        assertEquals(1, sz.trials); assertEquals(0, sz.untrainedTrials)
        assertEquals(1.0, sz.lastPct!!, 1e-9)
        assertNull(sz.lastUntrainedPct)          // no untrained trial this session
        assertEquals(emptyList<Double>(), sz.recentUntrainedPct)
        assertEquals(14, sz.wordsTotal); assertEquals(1, sz.wordsTrained)
    }

    @Test
    fun accumulatesAndCapsRecent() {
        var st = LearnerState(
            streakDays = 2, sessionsCompleted = 7, lastSession = "2026-09-10T07:00:00Z",
            contrasts = mapOf("th" to ContrastState(trials = 10, correct = 5, untrainedTrials = 4, untrainedCorrect = 1, recentUntrainedPct = listOf(0.1, 0.2, 0.3, 0.4, 0.5), meanRtMs = 1000)),
            words = mapOf("think" to WordState(3, 1, "2026-09-10T07:00:00Z")),
        )
        val rows = listOf(
            row(1, "th", "think", "sink", true, true, 500),
            row(2, "th", "sink", "think", true, false, 500),
        )
        st = StateUpdater.apply(st, record("2026-09-11T07:02:11Z", rows), catalog)
        assertEquals(8, st.sessionsCompleted)
        assertEquals(3, st.streakDays)
        val th = st.contrasts.getValue("th")
        assertEquals(12, th.trials); assertEquals(7, th.correct)
        assertEquals(5, th.untrainedTrials); assertEquals(2, th.untrainedCorrect)
        assertEquals(1.0, th.lastPct!!, 1e-9)
        assertEquals(1.0, th.lastUntrainedPct!!, 1e-9)
        assertEquals(listOf(0.2, 0.3, 0.4, 0.5, 1.0), th.recentUntrainedPct) // capped at 5, oldest dropped
        assertEquals(917, th.meanRtMs) // (1000×10 + 500×2) / 12 = 916.67
        assertEquals(4, st.words.getValue("think").exposures)
        assertEquals(1, st.words.getValue("sink").exposures)
        assertEquals(2, th.wordsTrained)

        // a gap resets the streak
        st = StateUpdater.apply(st, record("2026-09-14T07:02:11Z", rows.take(1)), catalog)
        assertEquals(1, st.streakDays)
        assertEquals(9, st.sessionsCompleted)
        assertEquals(listOf(0.2, 0.3, 0.4, 0.5, 1.0), st.contrasts.getValue("th").recentUntrainedPct) // unchanged: no untrained trial
    }

    // ---- Say it, levels and consistency ---------------------------------

    private fun prow(i: Int, contrast: String, a: String, b: String, points: Int, level: Int = 1) = ProductionRow(
        i = i, contrast = contrast, pair = "$contrast:$a-$b", a = a, b = b, points = points, level = level,
        words = mapOf(
            a to WordResult(heard = a, acc = 90, accOther = 70, ph = 95, phOther = 40, votes = "phoneme:intended word:intended recognition:intended", recognised = a, ms = 800),
            b to WordResult(heard = b, acc = 88, accOther = 60, ph = 90, phOther = 30, votes = "phoneme:intended word:intended recognition:intended", recognised = b, ms = 700),
        ),
    )

    private fun record(
        startedIso: String, rows: List<TrialRow>, production: List<ProductionRow>?, seconds: Long = 180,
        levels: Map<String, Int> = emptyMap(),
    ) = RecordBuilder.build(
        Instant.parse(startedIso), Instant.parse(startedIso).plusSeconds(seconds),
        "0.1.0", "2026-09-11.1", "coach", null, listOf("en-GB-SoniaNeural"), rows, 0,
        levels = levels, production = production,
        perceptionEnded = Instant.parse(startedIso).plusSeconds(seconds - 60),
    )

    @Test
    fun productionStatsPairsAndFails() {
        val rows = listOf(prow(1, "th", "think", "sink", 1), prow(2, "th", "mouth", "mouse", 2), prow(3, "s/z", "ice", "eyes", 0))
        var st = StateUpdater.apply(LearnerState(), record("2026-09-11T07:02:11Z", emptyList(), rows), catalog)
        val th = st.contrasts.getValue("th")
        assertEquals(2, th.productionPairs); assertEquals(3, th.productionPoints)
        assertEquals(0.75, th.lastProductionPct!!, 1e-9)
        assertEquals(listOf(0.75), th.recentProductionPct)
        assertEquals(0, th.trials) // no perception trial on it this session
        val sz = st.contrasts.getValue("s/z")
        assertEquals(1, sz.productionPairs); assertEquals(0, sz.productionPoints)
        assertEquals(0.0, sz.lastProductionPct!!, 1e-9)
        assertEquals(PairState(attempts = 1, lastPoints = 1, best = 1, fails = 1, last = "2026-09-11T07:05:11Z"), st.pairs.getValue("th:think-sink"))
        assertEquals(PairState(attempts = 1, lastPoints = 2, best = 2, fails = 0, last = "2026-09-11T07:05:11Z"), st.pairs.getValue("th:mouth-mouse"))
        assertEquals(PairState(attempts = 1, lastPoints = 0, best = 0, fails = 1, last = "2026-09-11T07:05:11Z"), st.pairs.getValue("s/z:ice-eyes"))

        // second session: the pair improves, fails stays, best follows
        st = StateUpdater.apply(st, record("2026-09-12T07:02:11Z", emptyList(), listOf(prow(1, "th", "think", "sink", 2))), catalog)
        assertEquals(PairState(attempts = 2, lastPoints = 2, best = 2, fails = 1, last = "2026-09-12T07:05:11Z"), st.pairs.getValue("th:think-sink"))
        assertEquals(PairState(attempts = 1, lastPoints = 2, best = 2, fails = 0, last = "2026-09-11T07:05:11Z"), st.pairs.getValue("th:mouth-mouse"))
        assertEquals(listOf(0.75, 1.0), st.contrasts.getValue("th").recentProductionPct)
        assertEquals(3, st.contrasts.getValue("th").productionPairs); assertEquals(5, st.contrasts.getValue("th").productionPoints)
        // a session without production leaves the production stats alone
        st = StateUpdater.apply(st, record("2026-09-13T07:02:11Z", listOf(row(1, "th", "think", "sink", true, true, 900)), null), catalog)
        assertEquals(listOf(0.75, 1.0), st.contrasts.getValue("th").recentProductionPct)
        assertEquals(1.0, st.contrasts.getValue("th").lastProductionPct!!, 1e-9)
        // recent cap at 5
        for (d in 14..19) st = StateUpdater.apply(st, record("2026-09-${d}T07:02:11Z", emptyList(), listOf(prow(1, "th", "think", "sink", d % 3))), catalog)
        assertEquals(5, st.contrasts.getValue("th").recentProductionPct.size)
        assertEquals(listOf(0.0, 0.5, 1.0, 0.0, 0.5), st.contrasts.getValue("th").recentProductionPct) // 15..19 → 0,1,2,0,1 points
    }

    @Test
    fun practiceTallyAcrossDaysStreakAndTrim() {
        val stale = LearnerState(
            practice = Practice(longestStreak = 2, totalSeconds = 100, days = mapOf(
                "2026-05-14" to DayTally(1, 50, 10, 0),   // 120 days before 2026-09-11 → dropped
                "2026-05-15" to DayTally(1, 50, 10, 0),   // 119 days before → the oldest kept
            )),
        )
        val t = listOf(row(1, "th", "think", "sink", true, false, 900), row(2, "th", "mouth", "mouse", true, false, 900))
        var st = StateUpdater.apply(stale, record("2026-09-11T07:02:11Z", t, listOf(prow(1, "th", "think", "sink", 2)), seconds = 290), catalog)
        assertEquals(setOf("2026-05-15", "2026-09-11"), st.practice.days.keys)
        assertEquals(DayTally(sessions = 1, seconds = 290, perceptionTrials = 2, productionPairs = 1), st.practice.days.getValue("2026-09-11"))
        assertEquals(390, st.practice.totalSeconds)
        assertEquals(2, st.practice.longestStreak) // streak restarted at 1 (no last_session): the old record stands
        assertEquals(1, st.streakDays)

        // a second session the same UTC day (23:59 is still the 11th), no production
        st = StateUpdater.apply(st, record("2026-09-11T23:59:00Z", t.take(1), null, seconds = 60), catalog)
        assertEquals(DayTally(sessions = 2, seconds = 350, perceptionTrials = 3, productionPairs = 1), st.practice.days.getValue("2026-09-11"))
        assertEquals(450, st.practice.totalSeconds)
        assertEquals(1, st.streakDays)
        // the next two days extend the streak past the old record
        st = StateUpdater.apply(st, record("2026-09-12T00:00:01Z", t, null, seconds = 100), catalog)
        st = StateUpdater.apply(st, record("2026-09-13T06:00:00Z", t, null, seconds = 100), catalog)
        assertEquals(3, st.streakDays)
        assertEquals(3, st.practice.longestStreak)
        assertEquals(650, st.practice.totalSeconds)
        assertEquals(listOf("2026-09-11", "2026-09-12", "2026-09-13"), st.practice.days.keys.toList()) // 2026-05-15 is now 121 days back
        assertEquals(DayTally(1, 100, 2, 0), st.practice.days.getValue("2026-09-13"))
        // a gap resets the streak but not the record
        st = StateUpdater.apply(st, record("2026-09-20T06:00:00Z", t, null, seconds = 100), catalog)
        assertEquals(1, st.streakDays)
        assertEquals(3, st.practice.longestStreak)
        assertEquals(4, st.practice.days.size)
    }

    @Test
    fun levelStampingPromotionDemotionPinsAndSnapshot() {
        val plan = effectivePlan(Plan(productionPairs = 0), catalog, Fixture.voices) // Say it off: perception alone decides
        val good = listOf(row(1, "th", "think", "sink", true, false, 900), row(2, "th", "mouth", "mouse", true, false, 900))
        var st = LearnerState(contrasts = mapOf("th" to ContrastState(recentUntrainedPct = listOf(0.9, 0.95))))
        st = StateUpdater.apply(st, record("2026-09-11T07:02:11Z", good, null, levels = mapOf("th" to 1)), catalog, plan)
        val th = st.contrasts.getValue("th")
        assertEquals(listOf(0.9, 0.95, 1.0), th.recentUntrainedPct)
        assertEquals(2, th.level)
        assertEquals("2026-09-11T07:05:11Z", th.levelChanged)
        // one step per session: the next perfect session goes to 3, not further
        st = StateUpdater.apply(st, record("2026-09-12T07:02:11Z", good, null, levels = mapOf("th" to 2)), catalog, plan)
        assertEquals(3, st.contrasts.getValue("th").level)
        assertEquals("2026-09-12T07:05:11Z", st.contrasts.getValue("th").levelChanged)
        // a session without untrained trials on the contrast adds no evidence: no move, stamp untouched
        val trained = good.map { it.copy(trained = true) }
        st = StateUpdater.apply(st, record("2026-09-13T07:02:11Z", trained, null, levels = mapOf("th" to 3)), catalog, plan)
        assertEquals(3, st.contrasts.getValue("th").level)
        assertEquals("2026-09-12T07:05:11Z", st.contrasts.getValue("th").levelChanged)
        // Say it on but never run for this contrast: perception alone promotes it
        val withSayIt = effectivePlan(Plan(), catalog, Fixture.voices)
        st = StateUpdater.apply(st, record("2026-09-14T07:02:11Z", good, null, levels = mapOf("th" to 3)), catalog, withSayIt)
        assertEquals(4, st.contrasts.getValue("th").level)
        assertEquals("2026-09-14T07:05:11Z", st.contrasts.getValue("th").levelChanged)

        // regression guard: two sessions < 60 % bring it down one rung
        val bad = listOf(row(1, "th", "think", "sink", false, false, 900), row(2, "th", "mouth", "mouse", false, false, 900))
        st = StateUpdater.apply(st, record("2026-09-17T07:02:11Z", bad, null, levels = mapOf("th" to 4)), catalog, plan)
        assertEquals(4, st.contrasts.getValue("th").level)
        st = StateUpdater.apply(st, record("2026-09-18T07:02:11Z", bad, null, levels = mapOf("th" to 4)), catalog, plan)
        assertEquals(3, st.contrasts.getValue("th").level)
        assertEquals("2026-09-18T07:05:11Z", st.contrasts.getValue("th").levelChanged)

        // a pin overrides whatever the evidence says, and is stamped when it differs
        val pinned = effectivePlan(Plan(levels = mapOf("th" to 1)), catalog, Fixture.voices)
        st = StateUpdater.apply(st, record("2026-09-19T07:02:11Z", good, null, levels = mapOf("th" to 1)), catalog, pinned)
        assertEquals(1, st.contrasts.getValue("th").level)
        assertEquals("2026-09-19T07:05:11Z", st.contrasts.getValue("th").levelChanged)
        // an untouched contrast with a pin is moved to the pin too; without one it keeps its rung
        st = st.copy(contrasts = st.contrasts + ("s/z" to ContrastState(level = 3)))
        val pinSz = effectivePlan(Plan(levels = mapOf("s/z" to 2)), catalog, Fixture.voices)
        st = StateUpdater.apply(st, record("2026-09-20T07:02:11Z", good, null), catalog, pinSz)
        assertEquals(2, st.contrasts.getValue("s/z").level)
        assertEquals(1, st.contrasts.getValue("th").level)
        // a lowered max_level caps a stored rung
        val capped = effectivePlan(Plan(maxLevel = 1), catalog, Fixture.voices)
        st = StateUpdater.apply(st, record("2026-09-21T07:02:11Z", good, null), catalog, capped)
        assertEquals(1, st.contrasts.getValue("s/z").level)

        // without a plan (older callers): no pins, ceiling 4, Say it on when the record has rows
        var plain = LearnerState(contrasts = mapOf("th" to ContrastState(recentUntrainedPct = listOf(0.9, 0.95))))
        val produced = listOf(prow(1, "th", "think", "sink", 2), prow(2, "th", "mouth", "mouse", 1))
        plain = StateUpdater.apply(plain, record("2026-09-11T07:02:11Z", good, produced), catalog)
        assertEquals(2, plain.contrasts.getValue("th").level) // one production result does not gate
        assertEquals("2026-09-11T07:05:11Z", plain.contrasts.getValue("th").levelChanged)
        plain = StateUpdater.apply(LearnerState(contrasts = mapOf("th" to ContrastState(recentUntrainedPct = listOf(0.9, 0.95)))), record("2026-09-11T07:02:11Z", good, null), catalog)
        assertEquals(2, plain.contrasts.getValue("th").level)
        assertTrue(plain.words.containsKey("think"))
    }

    @Test
    fun aSkippedSayItBlockNeverFreezesTheLadder() {
        // plan.production_pairs > 0 but the block never runs (no Azure key, no microphone, no network):
        // the contrast has no production history, so perception alone moves it (docs/ADAPTATION.md).
        val withSayIt = effectivePlan(Plan(), catalog, Fixture.voices)
        val good = listOf(row(1, "th", "think", "sink", true, false, 900), row(2, "th", "mouth", "mouse", true, false, 900))
        var st = LearnerState()
        val levels = listOf(1, 1, 2, 3, 4)
        for ((i, expectedBefore) in levels.withIndex()) {
            val rec = record("2026-09-1${i + 1}T07:02:11Z", good, null, levels = mapOf("th" to expectedBefore))
            st = StateUpdater.apply(st, rec, catalog, withSayIt)
        }
        assertEquals(4, st.contrasts.getValue("th").level)
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0, 1.0), st.contrasts.getValue("th").recentUntrainedPct)
        // two production results, both weak, do gate the next promotion
        var gated = LearnerState(contrasts = mapOf("th" to ContrastState(level = 1, recentUntrainedPct = listOf(1.0, 1.0))))
        val weak = listOf(prow(1, "th", "think", "sink", 1), prow(2, "th", "mouth", "mouse", 1)) // 50 %: under the gate, over the regression guard
        gated = StateUpdater.apply(gated, record("2026-09-11T07:02:11Z", good, weak, levels = mapOf("th" to 1)), catalog, withSayIt)
        assertEquals(2, gated.contrasts.getValue("th").level) // one result: no gate yet
        gated = StateUpdater.apply(gated, record("2026-09-12T07:02:11Z", good, weak, levels = mapOf("th" to 2)), catalog, withSayIt)
        assertEquals(2, gated.contrasts.getValue("th").level) // two weak results: the mouth must catch up
    }

    @Test
    fun aProductionOnlySessionDoesNotRejudgeThePerceptionEvidence() {
        val withSayIt = effectivePlan(Plan(), catalog, Fixture.voices)
        // trained trials only on th: the session adds nothing to recent_untrained_pct
        val trained = listOf(row(1, "th", "think", "sink", true, true, 900), row(2, "th", "mouth", "mouse", true, true, 900))
        val produced = listOf(prow(1, "th", "think", "sink", 2), prow(2, "th", "mouth", "mouse", 2))
        // a bad perception list must not demote the contrast again and again
        var st = LearnerState(contrasts = mapOf("th" to ContrastState(level = 3, recentUntrainedPct = listOf(0.9, 0.5, 0.5))))
        for (d in 11..13) {
            st = StateUpdater.apply(st, record("2026-09-${d}T07:02:11Z", trained, produced, levels = mapOf("th" to 3)), catalog, withSayIt)
            assertEquals(3, st.contrasts.getValue("th").level)
        }
        assertEquals(listOf(0.9, 0.5, 0.5), st.contrasts.getValue("th").recentUntrainedPct)
        assertNull(st.contrasts.getValue("th").levelChanged)
        // and a good one must not promote it again and again either
        var up = LearnerState(contrasts = mapOf("th" to ContrastState(
            level = 1, recentUntrainedPct = listOf(1.0, 1.0, 1.0), recentProductionPct = listOf(1.0, 1.0),
        )))
        for (d in 11..13) {
            up = StateUpdater.apply(up, record("2026-09-${d}T07:02:11Z", trained, produced, levels = mapOf("th" to 1)), catalog, withSayIt)
            assertEquals(1, up.contrasts.getValue("th").level)
        }
        // the production guard still works on the evidence this session did add
        var down = LearnerState(contrasts = mapOf("th" to ContrastState(level = 3, recentProductionPct = listOf(0.0))))
        val flunked = listOf(prow(1, "th", "think", "sink", 0), prow(2, "th", "mouth", "mouse", 0))
        down = StateUpdater.apply(down, record("2026-09-11T07:02:11Z", trained, flunked, levels = mapOf("th" to 3)), catalog, withSayIt)
        assertEquals(2, down.contrasts.getValue("th").level)
    }
}
