package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.Feedback
import com.djaramillo.minimalpairs.domain.model.Override
import com.djaramillo.minimalpairs.domain.model.Plan
import com.djaramillo.minimalpairs.domain.model.PlanSource
import com.djaramillo.minimalpairs.domain.model.effectivePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanTest {
    private val catalog = Fixture.catalog
    private val pack = Fixture.voices

    @Test
    fun nullPlanGivesDefaults() {
        val p = effectivePlan(null, catalog, pack)
        assertEquals(PlanSource.DEFAULT, p.planSource)
        assertEquals(40, p.trialsPerSession)
        assertEquals(0.5, p.untrainedRatio, 1e-9)
        assertEquals(pack, p.voices)
        assertEquals(listOf("high", "mid", "low"), p.bands) // the ceiling; the level ladder picks within it
        assertEquals(Feedback.FULL, p.feedback)
        assertEquals(4, p.maxLevel)
        assertEquals(emptyMap<String, Int>(), p.levels)
        assertEquals(20, p.weeklyMinutesTarget)
        assertEquals("", p.note)
        assertNull(p.planWritten)
        assertEquals(1.0, p.weights.getValue("th"), 1e-9)
        assertEquals(0.8, p.weights.getValue("s/z"), 1e-9)
        assertEquals(0.6, p.weights.getValue("i/ii"), 1e-9)
        assertEquals(0.5, p.weights.getValue("b/v"), 1e-9)
        // not trainable → 0 even though the catalog carries a default weight
        assertEquals(0.0, p.weights.getValue("schwa"), 1e-9)
        assertEquals(0.0, p.weights.getValue("s-cluster"), 1e-9)
        assertEquals(listOf("th", "s/z", "i/ii", "b/v"), p.activeContrastIds)
    }

    @Test
    fun coachPlanIsUsedAndClamped() {
        val plan = Plan(
            version = 1, written = "2026-09-11T18:30:00Z", note = "Week 3",
            trialsPerSession = 500, untrainedRatio = 1.7,
            weights = mapOf("th" to 2.0, "s/z" to -1.0, "i/ii" to 0.25),
        )
        val p = effectivePlan(plan, catalog, pack)
        assertEquals(PlanSource.COACH, p.planSource)
        assertEquals("2026-09-11T18:30:00Z", p.planWritten)
        assertEquals("Week 3", p.note)
        assertEquals(120, p.trialsPerSession)
        assertEquals(1.0, p.untrainedRatio, 1e-9)
        assertEquals(1.0, p.weights.getValue("th"), 1e-9)
        assertEquals(0.0, p.weights.getValue("s/z"), 1e-9)
        assertEquals(0.25, p.weights.getValue("i/ii"), 1e-9)
        // missing keeps catalog default
        assertEquals(0.5, p.weights.getValue("b/v"), 1e-9)

        val low = effectivePlan(Plan(trialsPerSession = 3, untrainedRatio = -0.2), catalog, pack)
        assertEquals(10, low.trialsPerSession)
        assertEquals(0.0, low.untrainedRatio, 1e-9)
    }

    @Test
    fun allZeroWeightsFallBackToDefaults() {
        val plan = Plan(weights = mapOf("th" to 0.0, "s/z" to 0.0, "i/ii" to 0.0, "b/v" to 0.0), note = "zero")
        val p = effectivePlan(plan, catalog, pack)
        assertEquals(PlanSource.DEFAULT, p.planSource)
        assertEquals(1.0, p.weights.getValue("th"), 1e-9)
        assertEquals(0.5, p.weights.getValue("b/v"), 1e-9)
        // the rest of the plan still applies
        assertEquals("zero", p.note)
    }

    @Test
    fun unknownContrastIdsAreDropped() {
        val p = effectivePlan(Plan(weights = mapOf("nope" to 1.0, "th" to 0.3)), catalog, pack)
        assertEquals(false, p.weights.containsKey("nope"))
        assertEquals(0.3, p.weights.getValue("th"), 1e-9)
        // only non-trainable contrast weighted → nothing usable → defaults
        val q = effectivePlan(Plan(weights = mapOf("th" to 0.0, "s/z" to 0.0, "i/ii" to 0.0, "b/v" to 0.0, "schwa" to 1.0)), catalog, pack)
        assertEquals(PlanSource.DEFAULT, q.planSource)
    }

    @Test
    fun voicesIntersectPack() {
        val p = effectivePlan(Plan(voices = listOf("en-GB-RyanNeural", "en-US-Nope", "en-GB-RyanNeural")), catalog, pack)
        assertEquals(listOf("en-GB-RyanNeural"), p.voices)
        val q = effectivePlan(Plan(voices = listOf("en-US-Nope")), catalog, pack)
        assertEquals(pack, q.voices)
        val r = effectivePlan(Plan(voices = emptyList()), catalog, pack)
        assertEquals(pack, r.voices)
    }

    @Test
    fun bandsAndFeedbackValidated() {
        val p = effectivePlan(Plan(band = listOf("low", "silly"), feedback = "brief"), catalog, pack)
        assertEquals(listOf("low"), p.bands)
        assertEquals(Feedback.BRIEF, p.feedback)
        val q = effectivePlan(Plan(band = listOf("silly"), feedback = "loud"), catalog, pack)
        assertEquals(listOf("high", "mid", "low"), q.bands)
        assertEquals(Feedback.FULL, q.feedback)
    }

    @Test
    fun overrideWins() {
        val plan = Plan(trialsPerSession = 60, weights = mapOf("th" to 1.0), note = "coach note")
        val ov = Override(enabled = true, weights = mapOf("th" to 0.0, "b/v" to 1.0), trialsPerSession = 20)
        val p = effectivePlan(plan, catalog, pack, ov)
        assertEquals(PlanSource.OVERRIDE, p.planSource)
        assertEquals(20, p.trialsPerSession)
        assertEquals(0.0, p.weights.getValue("th"), 1e-9)
        assertEquals(1.0, p.weights.getValue("b/v"), 1e-9)
        assertEquals(0.8, p.weights.getValue("s/z"), 1e-9) // catalog default kept
        assertEquals("coach note", p.note)

        val off = effectivePlan(plan, catalog, pack, ov.copy(enabled = false))
        assertEquals(PlanSource.COACH, off.planSource)
        assertEquals(60, off.trialsPerSession)

        val noTrials = effectivePlan(plan, catalog, pack, ov.copy(trialsPerSession = null))
        assertEquals(60, noTrials.trialsPerSession)

        val allOff = effectivePlan(plan, catalog, pack, Override(true, mapOf("th" to 0.0, "s/z" to 0.0, "i/ii" to 0.0, "b/v" to 0.0)))
        assertEquals(PlanSource.DEFAULT, allOff.planSource)
    }

    @Test
    fun levelLeversAndTargetAreClampedAndValidated() {
        val plan = Plan(
            maxLevel = 9,
            levels = mapOf("th" to 7, "s/z" to 0, "i/ii" to 3, "nope" to 2),
            weeklyMinutesTarget = 1000,
        )
        val p = effectivePlan(plan, catalog, pack)
        assertEquals(4, p.maxLevel)
        assertEquals(mapOf("th" to 4, "s/z" to 1, "i/ii" to 3), p.levels) // unknown id dropped, values clamped
        assertEquals(300, p.weeklyMinutesTarget)

        val off = effectivePlan(Plan(maxLevel = 0, weeklyMinutesTarget = -3), catalog, pack)
        assertEquals(1, off.maxLevel)
        assertEquals(0, off.weeklyMinutesTarget)

        val mid = effectivePlan(Plan(maxLevel = 2, weeklyMinutesTarget = 45), catalog, pack)
        assertEquals(2, mid.maxLevel)
        assertEquals(45, mid.weeklyMinutesTarget)
    }
}
