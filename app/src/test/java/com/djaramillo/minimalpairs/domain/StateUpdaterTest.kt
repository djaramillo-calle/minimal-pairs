package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.ContrastState
import com.djaramillo.minimalpairs.domain.model.LearnerState
import com.djaramillo.minimalpairs.domain.model.TrialRow
import com.djaramillo.minimalpairs.domain.model.WordState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
