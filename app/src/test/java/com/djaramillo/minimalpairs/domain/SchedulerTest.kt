package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.ContrastState
import com.djaramillo.minimalpairs.domain.model.LearnerState
import com.djaramillo.minimalpairs.domain.model.Plan
import com.djaramillo.minimalpairs.domain.model.effectivePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class SchedulerTest {
    private val catalog = Fixture.catalog
    private val pack = Fixture.voices

    private fun scheduler(
        plan: Plan? = null,
        state: LearnerState = LearnerState(),
        words: Set<String> = Fixture.allWords,
        seed: Int = 1,
    ) = SessionScheduler(catalog, effectivePlan(plan, catalog, pack), state, words, Random(seed))

    /** Run [n] trials answering everything correctly with a fixed RT. */
    private fun run(s: SessionScheduler, n: Int): List<PlannedTrial> =
        (1..n).map { i -> s.next(i).also { s.record(it, it.target, 900, 0) } }

    @Test
    fun weightZeroIsNeverDrawn() {
        val s = scheduler(Plan(weights = mapOf("th" to 0.0)))
        assertEquals("weight 0", s.skipped["th"])
        val trials = run(s, 200)
        assertTrue(trials.none { it.contrast == "th" })
        assertEquals(setOf("s/z", "i/ii", "b/v"), trials.map { it.contrast }.toSet())
    }

    @Test
    fun floorKeepsEveryWeightedContrastPresent() {
        val s = scheduler(Plan(weights = mapOf("th" to 1.0, "s/z" to 0.001, "i/ii" to 0.001, "b/v" to 0.001)))
        val probs = s.contrastProbabilities()
        assertEquals(1.0, probs.values.sum(), 1e-9)
        for (id in listOf("s/z", "i/ii", "b/v")) assertEquals(SessionScheduler.FLOOR, probs.getValue(id), 1e-9)
        assertEquals(1.0 - 3 * SessionScheduler.FLOOR, probs.getValue("th"), 1e-9)

        val trials = run(s, 3000)
        for (id in listOf("s/z", "i/ii", "b/v")) {
            val share = trials.count { it.contrast == id } / 3000.0
            assertTrue("$id share $share", share > 0.02)
        }
    }

    @Test
    fun weightsAreProportionalWithoutFloor() {
        val s = scheduler(Plan(weights = mapOf("th" to 0.5, "s/z" to 0.25, "i/ii" to 0.25, "b/v" to 0.0)))
        val p = s.contrastProbabilities()
        assertEquals(0.5, p.getValue("th"), 1e-9)
        assertEquals(0.25, p.getValue("s/z"), 1e-9)
        assertEquals(0.25, p.getValue("i/ii"), 1e-9)
        assertFalse(p.containsKey("b/v"))
    }

    @Test
    fun neverFourInARow() {
        // heavily skewed so runs would be common without the rule
        val s = scheduler(Plan(weights = mapOf("th" to 1.0, "s/z" to 0.01, "i/ii" to 0.0, "b/v" to 0.0)))
        val ids = run(s, 500).map { it.contrast }
        var run = 1
        for (i in 1 until ids.size) {
            run = if (ids[i] == ids[i - 1]) run + 1 else 1
            assertTrue("run of $run at $i", run <= 3)
        }
        assertTrue(ids.count { it == "th" } > 300)
    }

    @Test
    fun singleContrastMayRepeat() {
        val s = scheduler(Plan(weights = mapOf("th" to 1.0, "s/z" to 0.0, "i/ii" to 0.0, "b/v" to 0.0)))
        val ids = run(s, 10).map { it.contrast }
        assertTrue(ids.all { it == "th" })
    }

    @Test
    fun untrainedRatioIsMetWhenTrainedWordsAbound() {
        // one side of every eligible pair is still fresh (≈30 words), the other side is trained
        val fresh = catalog.trainableContrasts.flatMap { c -> c.trainablePairs.map { it.a.word } }.toSet()
        val s = scheduler(state = Fixture.trainedState(untrained = fresh), seed = 7)
        val trials = run(s, 40)
        val untrainedCount = trials.count { !it.trained }
        val probes = trials.count { it.probe }
        assertTrue("untrained $untrainedCount", untrainedCount >= 19)
        assertTrue("probes $probes", probes <= 20) // random sides on non-probe trials keep the count ahead
        // a shortfall is only possible when one contrast is drawn more often than it has fresh words
        assertTrue("shortfall ${s.untrainedShortfall}", s.untrainedShortfall <= 3)
        // the running rule never lets the share fall behind by more than one trial
        var seen = 0
        trials.forEachIndexed { k, t ->
            assertTrue("trial ${k + 1}: $seen untrained so far", seen >= 0.5 * k - 1.0)
            if (!t.trained) seen++
        }
        // a fresh word is never the target twice while other fresh words remain (shortfall trials excepted)
        val targets = trials.filter { !it.trained && !it.shortfall }.map { it.target }
        assertEquals(targets.size, targets.toSet().size)
    }

    @Test
    fun untrainedProbeFallsBackToLeastExposedWhenFreshWordsRunOut() {
        val untrained = setOf("think", "mouse", "sip", "eyes", "ship", "feel", "very", "van")
        val s = scheduler(state = Fixture.trainedState(untrained = untrained), seed = 7)
        val trials = run(s, 40)
        assertTrue(trials.count { !it.trained } >= 8)
        assertTrue(s.untrainedShortfall > 0)
        assertEquals(trials.count { it.shortfall }, s.untrainedShortfall)
        // every fresh word got heard exactly once before any shortfall
        val firstShortfall = trials.indexOfFirst { it.shortfall }
        assertEquals(untrained, trials.take(firstShortfall).filter { !it.trained }.map { it.target }.toSet())
    }

    @Test
    fun freshLearnerHearsOnlyUntrainedWords() {
        val trials = run(scheduler(), 40)
        assertTrue(trials.all { !it.trained })
        assertEquals(0, trials.count { it.shortfall })
    }

    @Test
    fun ratioZeroMakesNoProbes() {
        val s = scheduler(Plan(untrainedRatio = 0.0), state = Fixture.trainedState())
        val trials = run(s, 40)
        assertTrue(trials.none { it.probe })
        assertEquals(0, s.untrainedShortfall)
    }

    @Test
    fun shortfallCountedWhenUntrainedWordsRunOut() {
        val s = scheduler(state = Fixture.trainedState())
        val trials = run(s, 40)
        assertTrue(trials.all { it.trained })
        // trial 1 is never a probe (0 < 0.5 × 0 is false); every later trial is a probe that falls short
        assertEquals(39, s.untrainedShortfall)
        assertEquals(39, trials.count { it.shortfall })
    }

    @Test
    fun sessionModifierKicksInAfterFourTrialsBelow80pct() {
        val s = scheduler(Plan(weights = mapOf("th" to 1.0, "s/z" to 1.0, "i/ii" to 0.0, "b/v" to 0.0)))
        assertEquals(1.0, s.sessionModifier("th"), 1e-9)
        val th = catalog.contrast("th")!!.trainablePairs.first()
        fun thTrial(i: Int) = PlannedTrial(i, "th", th.id, th.a.word, th.b.word, pack[0], false, "high", "initial", true, probe = false, shortfall = false)
        repeat(3) { s.record(thTrial(it + 1), th.b.word, 1000, 0) } // 3 wrong
        assertEquals(1.0, s.sessionModifier("th"), 1e-9)          // < 4 trials: no change yet
        s.record(thTrial(4), th.b.word, 1000, 0)                   // 4th wrong → 0 % < 80 %
        assertEquals(1.5, s.sessionModifier("th"), 1e-9)
        val p = s.contrastProbabilities()
        assertEquals(0.6, p.getValue("th"), 1e-9)
        assertEquals(0.4, p.getValue("s/z"), 1e-9)

        // mastered: > 95 % with ≥ 6 trials → 0.5
        val sz = catalog.contrast("s/z")!!.trainablePairs.first()
        fun szTrial(i: Int) = PlannedTrial(i, "s/z", sz.id, sz.a.word, sz.b.word, pack[0], false, "mid", "initial", true, probe = false, shortfall = false)
        repeat(5) { s.record(szTrial(it + 1), sz.a.word, 800, 0) }
        assertEquals(1.0, s.sessionModifier("s/z"), 1e-9)  // 5 trials: not yet
        s.record(szTrial(6), sz.a.word, 800, 0)
        assertEquals(0.5, s.sessionModifier("s/z"), 1e-9)
        assertEquals(0.75, s.contrastProbabilities().getValue("th"), 1e-9)
    }

    @Test
    fun historyModifierFromState() {
        fun st(vararg recent: Double) = LearnerState(contrasts = mapOf("th" to ContrastState(recentUntrainedPct = recent.toList())))
        assertEquals(1.0, scheduler(state = LearnerState()).historyModifier("th"), 1e-9)
        assertEquals(1.25, scheduler(state = st(0.9, 0.5)).historyModifier("th"), 1e-9)
        assertEquals(0.75, scheduler(state = st(0.5, 0.97, 0.98)).historyModifier("th"), 1e-9)
        assertEquals(1.0, scheduler(state = st(0.98)).historyModifier("th"), 1e-9)   // only one session > 95 %
        assertEquals(1.0, scheduler(state = st(0.5, 0.98)).historyModifier("th"), 1e-9)
        assertEquals(1.0, scheduler(state = st(0.9)).historyModifier("th"), 1e-9)

        // combined modifier is clamped to [0.5, 2.0]: 0.75 × 0.5 = 0.375 → 0.5
        val s = scheduler(state = st(0.97, 0.98))
        val th = catalog.contrast("th")!!.trainablePairs.first()
        repeat(6) { s.record(PlannedTrial(it + 1, "th", th.id, th.a.word, th.b.word, pack[0], false, "high", "initial", true, false, false), th.a.word, 700, 0) }
        assertEquals(0.5, s.sessionModifier("th"), 1e-9)
        assertEquals(0.5, s.modifier("th"), 1e-9)
        assertEquals(0.5, s.effectiveWeight("th"), 1e-9)
    }

    @Test
    fun deterministicForSeed() {
        fun play(seed: Int): List<String> {
            val s = scheduler(seed = seed, state = Fixture.trainedState(untrained = setOf("ship", "very", "sink")))
            return (1..40).map { i ->
                val t = s.next(i)
                // alternate right/wrong so m_session moves
                s.record(t, if (i % 3 == 0) t.other else t.target, 500 + i, i % 2)
                "${t.contrast}|${t.pair}|${t.target}|${t.voice}|${t.targetOnLeft}|${t.trained}|${t.probe}"
            }
        }
        assertEquals(play(42), play(42))
        assertTrue(play(42) != play(43))
    }

    @Test
    fun skipsContrastsWithMissingClips() {
        val s = scheduler(words = Fixture.allWords - "think")
        assertNotNull(s.skipped["th"])
        assertTrue(s.skipped.getValue("th").startsWith("missing clips"))
        assertTrue(run(s, 100).none { it.contrast == "th" })
        try {
            scheduler(words = emptySet())
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Nothing to schedule"))
        }
    }

    @Test
    fun pairsAreNotReusedUntilAllUsed() {
        val s = scheduler(Plan(weights = mapOf("th" to 1.0, "s/z" to 0.0, "i/ii" to 0.0, "b/v" to 0.0)))
        // th has 7 trainable pairs in high/mid (thaw/saw is low → excluded)
        val first = run(s, 7).map { it.pair }
        assertEquals(7, first.toSet().size)
        assertTrue(first.none { it.contains("thaw") })
        val next = run(s, 7).map { it.pair }
        assertEquals(7, next.toSet().size)
    }

    @Test
    fun bandsRestrictPairs() {
        val s = scheduler(Plan(band = listOf("high")))
        val trials = run(s, 100)
        for (t in trials) {
            val pair = catalog.contrast(t.contrast)!!.pairs.first { it.id == t.pair }
            assertEquals("high", pair.a.band)
            assertEquals("high", pair.b.band)
            assertEquals("high", t.band)
        }
    }

    @Test
    fun trialFieldsAreConsistent() {
        val s = scheduler(seed = 3)
        var leftCount = 0
        val voices = HashSet<String>()
        for (t in run(s, 60)) {
            val pair = catalog.contrast(t.contrast)!!.pairs.first { it.id == t.pair }
            assertEquals(pair.other(t.target), t.other)
            assertEquals(pair.side(t.target)!!.band, t.band)
            assertEquals(pair.position, t.position)
            assertTrue(t.voice in pack)
            voices += t.voice
            if (t.targetOnLeft) { leftCount++; assertEquals(t.target, t.leftWord) } else assertEquals(t.target, t.rightWord)
        }
        assertEquals(pack.toSet(), voices)
        assertTrue(leftCount in 15..45)
        assertEquals(60, s.answeredTrials.size)
        assertEquals(60, s.plannedTrials.size)
    }

    @Test
    fun recordBuildsRow() {
        val s = scheduler()
        val t = s.next(1)
        val row = s.record(t, t.other, 1210, 2)
        assertEquals(1, row.i)
        assertFalse(row.correct)
        assertEquals(t.other, row.chosen)
        assertEquals(1210, row.rtMs)
        assertEquals(2, row.replays)
        assertEquals(t.trained, row.trained)
    }
}
