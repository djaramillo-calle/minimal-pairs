package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.TrialRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

import org.junit.Test
import java.time.Instant

class RecordBuilderTest {
    private fun row(i: Int, contrast: String, correct: Boolean, trained: Boolean, rt: Int) = TrialRow(
        i = i, contrast = contrast, pair = "$contrast:a-b", target = "a", other = "b",
        chosen = if (correct) "a" else "b", correct = correct, voice = "en-GB-SoniaNeural",
        rtMs = rt, replays = 0, trained = trained, band = "high", position = "initial",
    )

    @Test
    fun summaryMaths() {
        val rows = listOf(
            row(1, "th", true, false, 1000),
            row(2, "th", false, false, 2000),
            row(3, "s/z", true, true, 1500),
            row(4, "s/z", true, true, 500),
        )
        val started = Instant.parse("2026-09-11T07:02:11Z")
        val ended = Instant.parse("2026-09-11T07:05:28.900Z")
        val rec = RecordBuilder.build(
            started, ended, "0.1.0", "2026-09-11.1", "coach", "2026-09-11T18:30:00Z",
            listOf("en-GB-SoniaNeural"), rows, untrainedShortfall = 3,
        )
        assertEquals("20260911T070211Z", rec.id)
        assertEquals("2026-09-11T07:02:11Z", rec.started)
        assertEquals("2026-09-11T07:05:28Z", rec.ended)
        assertEquals(1, rec.version)
        val s = rec.summary
        assertEquals(4, s.trials)
        assertEquals(3, s.correct)
        assertEquals(0.75, s.pct, 1e-9)
        assertEquals(2, s.untrainedTrials)
        assertEquals(1, s.untrainedCorrect)
        assertEquals(0.5, s.untrainedPct!!, 1e-9)
        assertEquals(197, s.durationS)
        assertEquals(1250, s.meanRtMs)
        assertEquals(3, s.untrainedShortfall)
        assertEquals(setOf("th", "s/z"), s.contrasts.keys)
        val th = s.contrasts.getValue("th")
        assertEquals(2, th.trials); assertEquals(1, th.correct)
        assertEquals(2, th.untrainedTrials); assertEquals(1, th.untrainedCorrect)
        assertEquals(1500, th.meanRtMs)
        val sz = s.contrasts.getValue("s/z")
        assertEquals(2, sz.trials); assertEquals(2, sz.correct)
        assertEquals(0, sz.untrainedTrials); assertEquals(0, sz.untrainedCorrect)
        assertEquals(1000, sz.meanRtMs)
    }

    @Test
    fun emptyAndRounding() {
        val now = Instant.parse("2026-09-11T07:02:11Z")
        val rec = RecordBuilder.build(now, now, "0.1.0", "v", "default", null, emptyList(), emptyList(), 0)
        assertEquals(0, rec.summary.trials)
        assertEquals(0.0, rec.summary.pct, 1e-9)
        // No untrained trials: the contract wants null, never "0 % on untrained words".
        assertNull(rec.summary.untrainedPct)
        assertEquals(0, rec.summary.meanRtMs)
        assertEquals(0, rec.summary.durationS)
        assertNull(rec.planWritten)
        assertEquals(0.3333, RecordBuilder.ratio(1, 3), 1e-9)
        assertEquals(0.6667, RecordBuilder.ratio(2, 3), 1e-9)
    }

    @Test
    fun untrainedPctIsNullWithoutUntrainedTrials() {
        val rows = listOf(row(1, "th", true, true, 900), row(2, "s/z", false, true, 1100))
        val now = Instant.parse("2026-09-11T07:02:11Z")
        val rec = RecordBuilder.build(now, now.plusSeconds(30), "0.1.0", "v", "default", null, listOf("en-GB-SoniaNeural"), rows, 2)
        assertEquals(0, rec.summary.untrainedTrials)
        assertNull(rec.summary.untrainedPct)
        assertEquals(0.5, rec.summary.pct, 1e-9)
        assertEquals(2, rec.summary.untrainedShortfall)
        val text = AppJson.writer.encodeToString(com.djaramillo.minimalpairs.domain.model.SessionRecord.serializer(), rec)
        assertTrue(text, text.contains("\"untrained_pct\": null"))
        assertTrue(text, text.contains("\"plan_written\": null"))
    }
}
