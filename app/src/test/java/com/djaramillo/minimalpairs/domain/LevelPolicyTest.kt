package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.ContrastState
import com.djaramillo.minimalpairs.domain.model.LearnerState
import com.djaramillo.minimalpairs.domain.model.Plan
import com.djaramillo.minimalpairs.domain.model.effectivePlan
import org.junit.Assert.assertEquals
import org.junit.Test

class LevelPolicyTest {
    private val all = listOf("high", "mid", "low")

    @Test
    fun bandsPerLevelWithinCeiling() {
        assertEquals(listOf("high"), LevelPolicy.bandsFor(1, all))
        assertEquals(listOf("high", "mid"), LevelPolicy.bandsFor(2, all))
        assertEquals(listOf("high", "mid", "low"), LevelPolicy.bandsFor(3, all))
        assertEquals(listOf("high", "mid", "low"), LevelPolicy.bandsFor(4, all))
        // ∩ ceiling
        assertEquals(listOf("high", "mid"), LevelPolicy.bandsFor(3, listOf("mid", "high")))
        assertEquals(listOf("high"), LevelPolicy.bandsFor(4, listOf("high")))
        assertEquals(listOf("mid"), LevelPolicy.bandsFor(2, listOf("mid", "low")))
        assertEquals(emptyList<String>(), LevelPolicy.bandsFor(1, listOf("mid", "low")))
        // out of range clamps
        assertEquals(listOf("high"), LevelPolicy.bandsFor(0, all))
        assertEquals(listOf("high", "mid", "low"), LevelPolicy.bandsFor(9, all))
    }

    @Test
    fun poolWidensWhenTheRungHasNoPair() {
        val th = Fixture.th   // high/high pairs exist: the rung stands
        assertEquals(listOf("high"), LevelPolicy.poolBands(1, all, th))
        assertEquals(listOf("high", "mid"), LevelPolicy.poolBands(2, all, th))
        // ceiling without high: level 1 has nothing → the next rung ∩ ceiling that holds a pair (sip/zip is mid/mid)
        val sz = Fixture.sz
        assertEquals(listOf("mid"), LevelPolicy.poolBands(1, listOf("mid"), sz))
        assertEquals(listOf("mid"), LevelPolicy.poolBands(1, listOf("mid", "low"), sz))       // level 2's rung already has one
        assertEquals(listOf("mid", "low"), LevelPolicy.poolBands(3, listOf("mid", "low"), sz))
        // every th pair has a high word: no rung holds a pair under a [mid] ceiling → the rung's own bands, empty
        assertEquals(emptyList<String>(), LevelPolicy.poolBands(1, listOf("mid"), th))
        // a contrast with no high/high pair (like j/y in the real catalog): level 1 draws from high+mid
        val jy = Fixture.bv.copy(id = "j/y", pairs = Fixture.bv.pairs.filter { it.a.band != "high" || it.b.band != "high" })
        assertEquals(listOf("high", "mid"), LevelPolicy.poolBands(1, all, jy))
        assertEquals(listOf("high", "mid"), LevelPolicy.poolBands(2, all, jy))
        // no rung holds a pair: the rung's own (empty) bands, the contrast is skipped downstream
        val lowOnly = Fixture.bv.copy(pairs = Fixture.bv.pairs.filter { it.a.band == "low" && it.b.band == "low" })
        assertEquals(listOf("high", "mid", "low"), LevelPolicy.poolBands(1, all, lowOnly))
        assertEquals(emptyList<String>(), LevelPolicy.poolBands(1, listOf("mid"), lowOnly))
        assertEquals(listOf("high", "mid"), LevelPolicy.poolBands(3, listOf("high", "mid"), lowOnly))
    }

    @Test
    fun levelModifierHalvesOnlyMaintenance() {
        assertEquals(1.0, LevelPolicy.levelModifier(1), 1e-9)
        assertEquals(1.0, LevelPolicy.levelModifier(2), 1e-9)
        assertEquals(1.0, LevelPolicy.levelModifier(3), 1e-9)
        assertEquals(0.5, LevelPolicy.levelModifier(4), 1e-9)
    }

    private fun next(
        current: Int, u: List<Double>, p: List<Double> = emptyList(), productionOn: Boolean = false,
        pinned: Int? = null, maxLevel: Int = 4, untrainedEvidence: Boolean = true, productionEvidence: Boolean = true,
    ) = LevelPolicy.nextLevel(current, u, p, productionOn, pinned, maxLevel, untrainedEvidence, productionEvidence)

    @Test
    fun promotionAtExactThresholds() {
        assertEquals(2, next(1, listOf(0.9, 0.9, 0.9)))
        assertEquals(2, next(1, listOf(0.2, 0.9, 0.95, 1.0)))            // only the last three count
        assertEquals(1, next(1, listOf(0.89, 0.9, 0.9)))
        assertEquals(1, next(1, listOf(0.9, 0.9, 0.8999)))
        assertEquals(1, next(1, listOf(0.9, 0.9)))                      // two sessions are not enough
        assertEquals(1, next(1, emptyList()))
        // Say it on: the last two production results must both reach 75 %
        assertEquals(2, next(1, listOf(0.9, 0.9, 0.9), listOf(0.75, 0.75), productionOn = true))
        assertEquals(2, next(1, listOf(0.9, 0.9, 0.9), listOf(0.1, 0.75, 1.0), productionOn = true))
        assertEquals(1, next(1, listOf(0.9, 0.9, 0.9), listOf(0.74, 0.75), productionOn = true))
        // fewer than two production results are no evidence: perception alone promotes (a Say-it
        // block that never runs — no key, no microphone, no network — cannot freeze the ladder)
        assertEquals(2, next(1, listOf(0.9, 0.9, 0.9), listOf(0.75), productionOn = true))
        assertEquals(2, next(1, listOf(0.9, 0.9, 0.9), listOf(0.1), productionOn = true))
        assertEquals(2, next(1, listOf(0.9, 0.9, 0.9), emptyList(), productionOn = true))
        // Say it off: production history is ignored
        assertEquals(2, next(1, listOf(0.9, 0.9, 0.9), listOf(0.0, 0.0), productionOn = false))
    }

    @Test
    fun onlyTheEvidenceThisSessionAddedMovesTheLevel() {
        // no untrained trial on the contrast this session: the perception lists are not re-judged
        assertEquals(1, next(1, listOf(1.0, 1.0, 1.0), untrainedEvidence = false))
        assertEquals(3, next(3, listOf(0.1, 0.1), untrainedEvidence = false))
        assertEquals(3, next(3, listOf(0.1, 0.1), listOf(1.0, 1.0), productionOn = true, untrainedEvidence = false))
        // a production-only session neither promotes nor demotes on the frozen perception evidence
        assertEquals(3, next(3, listOf(0.9, 0.5, 0.5), listOf(1.0, 1.0), productionOn = true, untrainedEvidence = false))
        assertEquals(1, next(1, listOf(1.0, 1.0, 1.0), listOf(1.0, 1.0), productionOn = true, untrainedEvidence = false))
        // … but its own production results still guard, and stop guarding once the block stops running
        assertEquals(2, next(3, listOf(0.9, 0.9), listOf(0.1, 0.1), productionOn = true, untrainedEvidence = false))
        assertEquals(3, next(3, listOf(0.9, 0.9), listOf(0.1, 0.1), productionOn = true, untrainedEvidence = false, productionEvidence = false))
        // perception evidence with stale bad production results: the promotion gate still applies
        assertEquals(3, next(3, listOf(1.0, 1.0, 1.0), listOf(0.5, 0.5), productionOn = true, productionEvidence = false))
    }

    @Test
    fun oneStepPerSessionAndCeiling() {
        val perfect = listOf(1.0, 1.0, 1.0, 1.0, 1.0)
        assertEquals(2, next(1, perfect))
        assertEquals(3, next(2, perfect))
        assertEquals(4, next(3, perfect))
        assertEquals(4, next(4, perfect))
        assertEquals(2, next(2, perfect, maxLevel = 2))
        assertEquals(1, next(1, perfect, maxLevel = 1))
        // above a lowered ceiling: comes down to it
        assertEquals(2, next(4, perfect, maxLevel = 2))
        assertEquals(2, next(3, listOf(0.1, 0.1), maxLevel = 2))
    }

    @Test
    fun demotionAtExactThresholds() {
        assertEquals(2, next(3, listOf(0.59, 0.59)))
        assertEquals(2, next(3, listOf(0.9, 0.9, 0.0, 0.5999)))
        assertEquals(3, next(3, listOf(0.6, 0.59)))
        assertEquals(3, next(3, listOf(0.59, 0.6)))
        assertEquals(3, next(3, listOf(0.59)))                          // one bad session is not a regression
        assertEquals(1, next(1, listOf(0.0, 0.0)))                      // never below 1
        // production guard, even while perception is fine
        assertEquals(2, next(3, listOf(0.95, 0.95, 0.95), listOf(0.39, 0.39), productionOn = true))
        // Say it off: stale production results neither block nor demote
        assertEquals(4, next(3, listOf(0.95, 0.95, 0.95), listOf(0.39, 0.39), productionOn = false))
        assertEquals(3, next(3, listOf(0.7, 0.7), listOf(0.39, 0.39), productionOn = false))
        assertEquals(4, next(3, listOf(0.95, 0.95, 0.95), listOf(0.4, 0.39), productionOn = false))
        assertEquals(3, next(3, listOf(0.95, 0.95, 0.95), listOf(0.4, 0.39), productionOn = true))
        // one production result is not enough to guard either way: perception decides
        assertEquals(4, next(3, listOf(0.95, 0.95, 0.95), listOf(0.2), productionOn = true))
        assertEquals(3, next(3, listOf(0.7, 0.7), listOf(0.2), productionOn = true))
    }

    @Test
    fun pinnedNeverMoves() {
        val perfect = listOf(1.0, 1.0, 1.0)
        assertEquals(3, next(3, perfect, pinned = 3))
        assertEquals(3, next(1, perfect, pinned = 3))                   // the pin is the level, whatever the state had
        assertEquals(2, next(4, listOf(0.0, 0.0), pinned = 2))
        assertEquals(4, next(1, perfect, pinned = 4, maxLevel = 2))     // a pin is the coach's explicit choice
        assertEquals(4, next(1, perfect, pinned = 9))
        assertEquals(1, next(3, perfect, pinned = -1))
    }

    @Test
    fun currentLevelFromStatePinsAndCeiling() {
        val st = LearnerState(contrasts = mapOf("th" to ContrastState(level = 3)))
        val plain = effectivePlan(Plan(), Fixture.catalog, Fixture.voices)
        assertEquals(3, LevelPolicy.currentLevel("th", st, plain))
        assertEquals(1, LevelPolicy.currentLevel("s/z", st, plain))    // new contrast starts at 1
        assertEquals(1, LevelPolicy.currentLevel("s/z", LearnerState(), plain))
        val pinned = effectivePlan(Plan(levels = mapOf("s/z" to 2, "th" to 1)), Fixture.catalog, Fixture.voices)
        assertEquals(1, LevelPolicy.currentLevel("th", st, pinned))
        assertEquals(2, LevelPolicy.currentLevel("s/z", st, pinned))
        val capped = effectivePlan(Plan(maxLevel = 2), Fixture.catalog, Fixture.voices)
        assertEquals(2, LevelPolicy.currentLevel("th", st, capped))
        val broken = LearnerState(contrasts = mapOf("th" to ContrastState(level = 0)))
        assertEquals(1, LevelPolicy.currentLevel("th", broken, plain))
    }
}
