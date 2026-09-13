package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.ContrastState
import com.djaramillo.minimalpairs.domain.model.DayTally
import com.djaramillo.minimalpairs.domain.model.LearnerState
import com.djaramillo.minimalpairs.domain.model.Plan
import com.djaramillo.minimalpairs.domain.model.Practice
import com.djaramillo.minimalpairs.domain.model.TrialRow
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

    // ---- levels and consistency ----------------------------------------

    private fun record(
        startedIso: String, rows: List<TrialRow>, seconds: Long = 180,
        levels: Map<String, Int> = emptyMap(),
    ) = RecordBuilder.build(
        Instant.parse(startedIso), Instant.parse(startedIso).plusSeconds(seconds),
        "0.2.0", "2026-09-11.1", "coach", null, listOf("en-GB-SoniaNeural"), rows, 0,
        levels = levels,
    )

    @Test
    fun practiceTallyAcrossDaysStreakAndTrim() {
        val stale = LearnerState(
            practice = Practice(longestStreak = 2, totalSeconds = 100, days = mapOf(
                "2026-05-14" to DayTally(1, 50, 10),   // 120 days before 2026-09-11 → dropped
                "2026-05-15" to DayTally(1, 50, 10),   // 119 days before → the oldest kept
            )),
        )
        val t = listOf(row(1, "th", "think", "sink", true, false, 900), row(2, "th", "mouth", "mouse", true, false, 900))
        var st = StateUpdater.apply(stale, record("2026-09-11T07:02:11Z", t, seconds = 290), catalog)
        assertEquals(setOf("2026-05-15", "2026-09-11"), st.practice.days.keys)
        assertEquals(DayTally(sessions = 1, seconds = 290, perceptionTrials = 2), st.practice.days.getValue("2026-09-11"))
        assertEquals(390, st.practice.totalSeconds)
        assertEquals(2, st.practice.longestStreak) // streak restarted at 1 (no last_session): the old record stands
        assertEquals(1, st.streakDays)

        // a second session the same UTC day (23:59 is still the 11th)
        st = StateUpdater.apply(st, record("2026-09-11T23:59:00Z", t.take(1), seconds = 60), catalog)
        assertEquals(DayTally(sessions = 2, seconds = 350, perceptionTrials = 3), st.practice.days.getValue("2026-09-11"))
        assertEquals(450, st.practice.totalSeconds)
        assertEquals(1, st.streakDays)
        // the next two days extend the streak past the old record
        st = StateUpdater.apply(st, record("2026-09-12T00:00:01Z", t, seconds = 100), catalog)
        st = StateUpdater.apply(st, record("2026-09-13T06:00:00Z", t, seconds = 100), catalog)
        assertEquals(3, st.streakDays)
        assertEquals(3, st.practice.longestStreak)
        assertEquals(650, st.practice.totalSeconds)
        assertEquals(listOf("2026-09-11", "2026-09-12", "2026-09-13"), st.practice.days.keys.toList()) // 2026-05-15 is now 121 days back
        assertEquals(DayTally(1, 100, 2), st.practice.days.getValue("2026-09-13"))
        // a gap resets the streak but not the record
        st = StateUpdater.apply(st, record("2026-09-20T06:00:00Z", t, seconds = 100), catalog)
        assertEquals(1, st.streakDays)
        assertEquals(3, st.practice.longestStreak)
        assertEquals(4, st.practice.days.size)
    }

    @Test
    fun levelStampingPromotionDemotionPinsAndSnapshot() {
        val plan = effectivePlan(Plan(), catalog, Fixture.voices)
        val good = listOf(row(1, "th", "think", "sink", true, false, 900), row(2, "th", "mouth", "mouse", true, false, 900))
        var st = LearnerState(contrasts = mapOf("th" to ContrastState(recentUntrainedPct = listOf(0.9, 0.95))))
        st = StateUpdater.apply(st, record("2026-09-11T07:02:11Z", good, levels = mapOf("th" to 1)), catalog, plan)
        val th = st.contrasts.getValue("th")
        assertEquals(listOf(0.9, 0.95, 1.0), th.recentUntrainedPct)
        assertEquals(2, th.level)
        assertEquals("2026-09-11T07:05:11Z", th.levelChanged)
        // one step per session: the next perfect session goes to 3, not further
        st = StateUpdater.apply(st, record("2026-09-12T07:02:11Z", good, levels = mapOf("th" to 2)), catalog, plan)
        assertEquals(3, st.contrasts.getValue("th").level)
        assertEquals("2026-09-12T07:05:11Z", st.contrasts.getValue("th").levelChanged)
        // a session without untrained trials on the contrast adds no evidence: no move, stamp untouched
        val trained = good.map { it.copy(trained = true) }
        st = StateUpdater.apply(st, record("2026-09-13T07:02:11Z", trained, levels = mapOf("th" to 3)), catalog, plan)
        assertEquals(3, st.contrasts.getValue("th").level)
        assertEquals("2026-09-12T07:05:11Z", st.contrasts.getValue("th").levelChanged)
        st = StateUpdater.apply(st, record("2026-09-14T07:02:11Z", good, levels = mapOf("th" to 3)), catalog, plan)
        assertEquals(4, st.contrasts.getValue("th").level)
        assertEquals("2026-09-14T07:05:11Z", st.contrasts.getValue("th").levelChanged)

        // regression guard: two sessions < 60 % bring it down one rung
        val bad = listOf(row(1, "th", "think", "sink", false, false, 900), row(2, "th", "mouth", "mouse", false, false, 900))
        st = StateUpdater.apply(st, record("2026-09-17T07:02:11Z", bad, levels = mapOf("th" to 4)), catalog, plan)
        assertEquals(4, st.contrasts.getValue("th").level)
        st = StateUpdater.apply(st, record("2026-09-18T07:02:11Z", bad, levels = mapOf("th" to 4)), catalog, plan)
        assertEquals(3, st.contrasts.getValue("th").level)
        assertEquals("2026-09-18T07:05:11Z", st.contrasts.getValue("th").levelChanged)

        // a pin overrides whatever the evidence says, and is stamped when it differs
        val pinned = effectivePlan(Plan(levels = mapOf("th" to 1)), catalog, Fixture.voices)
        st = StateUpdater.apply(st, record("2026-09-19T07:02:11Z", good, levels = mapOf("th" to 1)), catalog, pinned)
        assertEquals(1, st.contrasts.getValue("th").level)
        assertEquals("2026-09-19T07:05:11Z", st.contrasts.getValue("th").levelChanged)
        // an untouched contrast with a pin is moved to the pin too; without one it keeps its rung
        st = st.copy(contrasts = st.contrasts + ("s/z" to ContrastState(level = 3)))
        val pinSz = effectivePlan(Plan(levels = mapOf("s/z" to 2)), catalog, Fixture.voices)
        st = StateUpdater.apply(st, record("2026-09-20T07:02:11Z", good), catalog, pinSz)
        assertEquals(2, st.contrasts.getValue("s/z").level)
        assertEquals(1, st.contrasts.getValue("th").level)
        // a lowered max_level caps a stored rung
        val capped = effectivePlan(Plan(maxLevel = 1), catalog, Fixture.voices)
        st = StateUpdater.apply(st, record("2026-09-21T07:02:11Z", good), catalog, capped)
        assertEquals(1, st.contrasts.getValue("s/z").level)

        // without a plan (older callers): no pins, ceiling 4
        var plain = StateUpdater.apply(LearnerState(contrasts = mapOf("th" to ContrastState(recentUntrainedPct = listOf(0.9, 0.95)))), record("2026-09-11T07:02:11Z", good), catalog)
        assertEquals(2, plain.contrasts.getValue("th").level)
        assertTrue(plain.words.containsKey("think"))
    }

    @Test
    fun aSessionWithoutAnUntrainedProbeDoesNotRejudgeTheLadder() {
        val plan = effectivePlan(Plan(), catalog, Fixture.voices)
        // trained trials only on th: the session adds nothing to recent_untrained_pct
        val trained = listOf(row(1, "th", "think", "sink", true, true, 900), row(2, "th", "mouth", "mouse", true, true, 900))
        // a bad perception list must not demote the contrast again and again
        var st = LearnerState(contrasts = mapOf("th" to ContrastState(level = 3, recentUntrainedPct = listOf(0.9, 0.5, 0.5))))
        for (d in 11..13) {
            st = StateUpdater.apply(st, record("2026-09-${d}T07:02:11Z", trained, levels = mapOf("th" to 3)), catalog, plan)
            assertEquals(3, st.contrasts.getValue("th").level)
        }
        assertEquals(listOf(0.9, 0.5, 0.5), st.contrasts.getValue("th").recentUntrainedPct)
        assertNull(st.contrasts.getValue("th").levelChanged)
        // and a good one must not promote it again and again either
        var up = LearnerState(contrasts = mapOf("th" to ContrastState(level = 1, recentUntrainedPct = listOf(1.0, 1.0, 1.0))))
        for (d in 11..13) {
            up = StateUpdater.apply(up, record("2026-09-${d}T07:02:11Z", trained, levels = mapOf("th" to 1)), catalog, plan)
            assertEquals(1, up.contrasts.getValue("th").level)
        }
    }

    @Test
    fun untrainedProbesClimbTheLadderOneRungPerSession() {
        val plan = effectivePlan(Plan(), catalog, Fixture.voices)
        val good = listOf(row(1, "th", "think", "sink", true, false, 900), row(2, "th", "mouth", "mouse", true, false, 900))
        var st = LearnerState()
        for ((i, before) in listOf(1, 1, 2, 3, 4).withIndex()) {
            st = StateUpdater.apply(st, record("2026-09-1${i + 1}T07:02:11Z", good, levels = mapOf("th" to before)), catalog, plan)
        }
        assertEquals(4, st.contrasts.getValue("th").level)
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0, 1.0), st.contrasts.getValue("th").recentUntrainedPct)
    }
}
