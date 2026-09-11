package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.ContrastState
import com.djaramillo.minimalpairs.domain.model.LearnerState
import com.djaramillo.minimalpairs.domain.model.PairState
import com.djaramillo.minimalpairs.domain.model.Plan
import com.djaramillo.minimalpairs.domain.model.effectivePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ProductionPlannerTest {
    private val catalog = Fixture.catalog
    private val pack = Fixture.voices
    private fun plan(p: Plan = Plan()) = effectivePlan(p, catalog, pack)
    private val thOnly = Plan(weights = mapOf("th" to 1.0, "s/z" to 0.0, "i/ii" to 0.0, "b/v" to 0.0))

    private fun pick(n: Int, plan: Plan = Plan(), state: LearnerState = LearnerState(), misses: Set<String> = emptySet(), seed: Int = 1) =
        ProductionPlanner.pick(n, catalog, plan(plan), state, misses, Random(seed)).map { it.id }

    @Test
    fun withinContrastOrderAndExclusions() {
        // th at level 1: think-sink, mouth-mouse, thing-sing, path-pass are the high/high pairs
        val st = LearnerState(
            lastSession = "2026-09-10T07:00:00Z",
            pairs = mapOf(
                "th:think-sink" to PairState(attempts = 2, lastPoints = 1, best = 2, fails = 1, last = "2026-09-10T07:03:00Z"),   // due again
                "th:thing-sing" to PairState(attempts = 1, lastPoints = 2, best = 2, fails = 0, last = "2026-09-10T07:03:30Z"),   // scored 2 last session
                "th:path-pass" to PairState(attempts = 1, lastPoints = 2, best = 2, fails = 0, last = "2026-09-08T07:03:30Z"),    // scored 2 two sessions ago
            ),
        )
        val picked = pick(8, thOnly, st, misses = setOf("th:mouth-mouse", "th:thing-sing"))
        assertEquals(listOf("th:think-sink", "th:mouth-mouse", "th:path-pass"), picked)
        // the same without a miss: never-attempted before the rest
        assertEquals(listOf("th:think-sink", "th:mouth-mouse", "th:path-pass"), pick(8, thOnly, st))
        // a pair scored 2 last session is offered again the session after
        val later = st.copy(lastSession = "2026-09-11T07:00:00Z")
        assertEquals(listOf("th:think-sink", "th:mouth-mouse", "th:path-pass", "th:thing-sing"), pick(8, thOnly, later))
    }

    @Test
    fun neverAttemptedCommonestFirstAndNoRepeats() {
        // level 2: the four high/high pairs (rank sum 2000) before the high/mid ones (6000)
        val st = LearnerState(contrasts = mapOf("th" to ContrastState(level = 2)))
        val picked = pick(7, thOnly, st)
        assertEquals(7, picked.toSet().size)
        assertEquals(listOf("th:think-sink", "th:mouth-mouse", "th:thing-sing", "th:path-pass"), picked.take(4))
        assertEquals(setOf("th:thick-sick", "th:thumb-sum", "th:faith-face"), picked.drop(4).toSet())
        // asking for more than there is stops at the pool
        assertEquals(7, pick(30, thOnly, st).size)
        assertEquals(0, pick(0, thOnly, st).size)
    }

    @Test
    fun levelBandsAndCeilingLimitThePool() {
        val szOnly = Plan(weights = mapOf("th" to 0.0, "s/z" to 1.0, "i/ii" to 0.0, "b/v" to 0.0))
        // level 1: sip/zip (mid/mid) and bus/buzz (high/mid) are out
        val l1 = pick(10, szOnly)
        assertEquals(setOf("s/z:ice-eyes", "s/z:race-raise", "s/z:loose-lose"), l1.toSet())
        val l2 = pick(10, szOnly, LearnerState(contrasts = mapOf("s/z" to ContrastState(level = 2))))
        assertTrue("s/z:sip-zip" in l2 && "s/z:bus-buzz" in l2 && "s/z:price-prize" in l2)
        assertTrue("s/z:sue-zoo" !in l2)
        val l3 = pick(10, szOnly, LearnerState(contrasts = mapOf("s/z" to ContrastState(level = 3))))
        assertTrue("s/z:sue-zoo" in l3)
        // ceiling: level 3 but band [high] → high/high only
        val capped = pick(10, szOnly.copy(band = listOf("high")), LearnerState(contrasts = mapOf("s/z" to ContrastState(level = 3))))
        assertEquals(l1.toSet(), capped.toSet())
        // a pin wins
        val pinned = pick(10, szOnly.copy(levels = mapOf("s/z" to 1)), LearnerState(contrasts = mapOf("s/z" to ContrastState(level = 3))))
        assertEquals(l1.toSet(), pinned.toSet())
        // a ceiling that leaves level 1 nothing widens to the next rung instead of starving the contrast
        val midOnly = pick(10, szOnly.copy(band = listOf("mid")))
        assertEquals(setOf("s/z:sip-zip"), midOnly.toSet())
        // clips filter
        val noIce = ProductionPlanner.pick(10, catalog, plan(szOnly), LearnerState(), emptySet(), Random(1), availableWords = Fixture.allWords - "ice")
        assertEquals(setOf("s/z:race-raise", "s/z:loose-lose"), noIce.map { it.id }.toSet())
    }

    @Test
    fun weightZeroAndNonTrainableAreExcluded() {
        val picked = pick(40, Plan(weights = mapOf("s/z" to 0.0, "schwa" to 1.0, "s-cluster" to 1.0)))
        assertTrue(picked.isNotEmpty())
        assertTrue(picked.none { it.startsWith("s/z:") })
        assertTrue(picked.none { it.startsWith("schwa:") })
        assertTrue(picked.none { it.startsWith("s-cluster:") })
        assertEquals(picked.size, picked.toSet().size)
    }

    @Test
    fun sharesFollowWeightsAndProductionHistory() {
        val p = plan()
        // no history: w_eff 1.0 × (1 + (1 − 0)) = 2
        assertEquals(2.0, ProductionPlanner.contrastShare("th", p, LearnerState()), 1e-9)
        assertEquals(1.6, ProductionPlanner.contrastShare("s/z", p, LearnerState()), 1e-9)
        // a contrast the mouth gets right needs fewer pairs
        val good = LearnerState(contrasts = mapOf("th" to ContrastState(lastProductionPct = 0.75)))
        assertEquals(1.25, ProductionPlanner.contrastShare("th", p, good), 1e-9)
        val perfect = LearnerState(contrasts = mapOf("th" to ContrastState(lastProductionPct = 1.0)))
        assertEquals(1.0, ProductionPlanner.contrastShare("th", p, perfect), 1e-9)
        // m_history and m_level are in w_eff
        val struggling = LearnerState(contrasts = mapOf("th" to ContrastState(recentUntrainedPct = listOf(0.5))))
        assertEquals(2.5, ProductionPlanner.contrastShare("th", p, struggling), 1e-9)
        val maintenance = LearnerState(contrasts = mapOf("th" to ContrastState(level = 4)))
        assertEquals(1.0, ProductionPlanner.contrastShare("th", p, maintenance), 1e-9)
        assertEquals(3.0, ProductionPlanner.contrastShare("th", p, LearnerState(), sessionModifier = 1.5), 1e-9)
        assertEquals(0.0, ProductionPlanner.contrastShare("schwa", p, LearnerState()), 1e-9)
        assertEquals(0.0, ProductionPlanner.contrastShare("nope", p, LearnerState()), 1e-9)

        // over many draws the shares show: th ≈ 2 : i/ii ≈ 1.2 with weights 1.0 and 0.6
        var th = 0; var iii = 0
        for (seed in 1..300) {
            val ids = pick(1, Plan(weights = mapOf("th" to 1.0, "s/z" to 0.0, "i/ii" to 0.6, "b/v" to 0.0)), seed = seed)
            if (ids[0].startsWith("th:")) th++ else iii++
        }
        assertTrue("th $th vs i/ii $iii", th > iii && th < 3 * iii)
    }

    @Test
    fun deterministicForSeed() {
        val st = LearnerState(contrasts = mapOf("th" to ContrastState(level = 2), "s/z" to ContrastState(level = 2)))
        assertEquals(pick(8, state = st, seed = 42), pick(8, state = st, seed = 42))
        val runs = (1..20).map { pick(8, state = st, seed = it) }.toSet()
        assertTrue(runs.size > 1)
        for (r in runs) assertEquals(8, r.toSet().size)
    }
}
