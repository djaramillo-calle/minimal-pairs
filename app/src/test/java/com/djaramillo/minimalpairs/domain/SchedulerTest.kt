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

    /** Every contrast pinned at level 2 (high + mid): the word pool the perception rules were written against. */
    private val level2 = mapOf("th" to 2, "s/z" to 2, "i/ii" to 2, "b/v" to 2)

    private fun scheduler(
        plan: Plan? = null,
        state: LearnerState = LearnerState(),
        words: Set<String> = Fixture.allWords,
        seed: Int = 1,
        levels: Map<String, Int>? = level2,
    ) = SessionScheduler(
        catalog,
        effectivePlan((plan ?: Plan()).let { if (levels != null && it.levels == null) it.copy(levels = levels) else it }, catalog, pack),
        state, words, Random(seed),
    )

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
        // A ceiling of one band: no rung can widen, so the probe really does run out.
        val untrained = setOf("think", "mouse", "ice", "bit", "boat")
        val s = scheduler(Plan(band = listOf("high")), state = Fixture.trainedState(untrained = untrained), seed = 7)
        val trials = run(s, 40)
        assertTrue(trials.count { !it.trained } >= 5)
        assertTrue(s.untrainedShortfall > 0)
        assertEquals(trials.count { it.shortfall }, s.untrainedShortfall)
        // only the fresh words are heard untrained, and never twice
        val fresh = trials.filter { !it.trained && !it.shortfall }.map { it.target }
        assertTrue(fresh.all { it in untrained })
        assertEquals(fresh.size, fresh.toSet().size)
    }

    @Test
    fun untrainedProbeWidensToTheHigherRungsBeforeItFallsShort() {
        // th at level 1, every word trained but the low-band "thaw": the rung's high pairs hold no
        // untrained word, so the probe widens to the first higher rung's bands that do (docs/ADAPTATION.md).
        val plan = Plan(weights = mapOf("th" to 1.0, "s/z" to 0.0, "i/ii" to 0.0, "b/v" to 0.0), levels = mapOf("th" to 1))
        val s = scheduler(plan, state = Fixture.trainedState(untrained = setOf("thaw")), seed = 3, levels = null)
        val trials = run(s, 40)
        val probe = trials.first { !it.trained }
        assertEquals("thaw", probe.target)
        assertEquals("low", probe.band)
        assertFalse(probe.shortfall)
        assertEquals(2, probe.index)                    // trial 1 is never a probe; trial 2 is the first
        assertEquals(1, trials.count { !it.trained })   // the ceiling holds exactly one untrained word
        assertEquals(trials.count { it.shortfall }, s.untrainedShortfall)
        assertTrue(s.untrainedShortfall > 30)           // and only then, with the ceiling exhausted, the shortfall
        // the rung itself does not move: every trained trial stays on the level-1 pairs
        val highPairs = Fixture.th.trainablePairs.filter { it.a.band == "high" && it.b.band == "high" }.map { it.id }.toSet()
        assertTrue(trials.filter { it.trained }.all { it.pair in highPairs })
    }

    @Test
    fun aContrastWithTwoHighWordsKeepsGettingProbesAndCanPromote() {
        // b/v at level 1 has one high/high pair (boat/vote) — the shape that used to make the real
        // b/v and j/y unpromotable: the probe dried up, recent_untrained_pct froze, the rung stuck.
        val plan = effectivePlan(
            Plan(weights = mapOf("b/v" to 1.0, "th" to 0.0, "s/z" to 0.0, "i/ii" to 0.0), trialsPerSession = 10),
            catalog, pack,
        )
        var state = LearnerState()
        val words = Fixture.bv.trainableWords().size
        for (session in 1..6) {
            val trainedBefore = state.contrasts["b/v"]?.wordsTrained ?: 0
            val s = SessionScheduler(catalog, plan, state, Fixture.allWords, Random(session))
            val rows = (1..10).map { i -> s.next(i).let { t -> s.record(t, t.target, 900, 0) } }
            val started = java.time.Instant.parse("2026-09-11T07:00:00Z").plusSeconds(86400L * session)
            val rec = RecordBuilder.build(
                started, started.plusSeconds(300), "0.1.0", catalog.version, "coach", null, plan.voices,
                rows, s.untrainedShortfall, s.levels,
            )
            // while the ceiling still holds an untrained word the probe finds one, whatever the rung
            val probes = rec.summary.contrasts.getValue("b/v").untrainedTrials
            if (trainedBefore < words) assertTrue("session $session had no probe", probes > 0)
            // a shortfall only once the session has drilled the last untrained word of the ceiling
            if (trainedBefore + probes < words) assertEquals("session $session fell short", 0, s.untrainedShortfall)
            state = StateUpdater.apply(state, rec, catalog, plan)
        }
        // the whole pool got drilled and the rung moved, which the level-1 high pair alone could never do
        assertEquals(words, state.contrasts.getValue("b/v").wordsTrained)
        assertTrue(state.contrasts.getValue("b/v").level >= 3)
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
    fun levelLadderPicksBandsWithinCeiling() {
        // level 1 (fresh state, no pins): high only — th has 4 high/high pairs
        val l1 = scheduler(Plan(weights = mapOf("th" to 1.0, "s/z" to 0.0, "i/ii" to 0.0, "b/v" to 0.0)), levels = null)
        assertEquals(1, l1.levels.getValue("th"))
        assertEquals(listOf("high"), l1.bandsFor("th"))
        assertEquals(setOf("th"), l1.levels.keys) // only contrasts the plan weights
        val first = run(l1, 4).map { it.pair }
        assertEquals(4, first.toSet().size)
        assertTrue(run(l1, 40).all { it.band == "high" })

        // level 3 from state: the whole lexicon (thaw/saw is low)
        val st3 = LearnerState(contrasts = mapOf("th" to ContrastState(level = 3)))
        val l3 = scheduler(Plan(weights = mapOf("th" to 1.0, "s/z" to 0.0, "i/ii" to 0.0, "b/v" to 0.0)), state = st3, levels = null)
        assertEquals(listOf("high", "mid", "low"), l3.bandsFor("th"))
        assertTrue(run(l3, 100).any { it.pair.contains("thaw") })

        // the ceiling still applies: level 3 ∩ band [high, mid] = high, mid
        val capped = scheduler(Plan(band = listOf("high", "mid"), weights = mapOf("th" to 1.0, "s/z" to 0.0, "i/ii" to 0.0, "b/v" to 0.0)), state = st3, levels = null)
        assertEquals(listOf("high", "mid"), capped.bandsFor("th"))
        assertTrue(run(capped, 100).none { it.pair.contains("thaw") })

        // a pin wins over the state; max_level caps a stored level
        val pinned = scheduler(Plan(levels = mapOf("th" to 1)), state = st3, levels = null)
        assertEquals(1, pinned.levels.getValue("th"))
        val maxed = scheduler(Plan(maxLevel = 2), state = st3, levels = null)
        assertEquals(2, maxed.levels.getValue("th"))
    }

    @Test
    fun rungWithoutPairsWidensInsteadOfStarving() {
        // a ceiling of [mid] leaves level 1 nothing: s/z widens to its level-2 rung (sip/zip is mid/mid);
        // th has no pair without a high word, so no rung helps and it is skipped
        val t = scheduler(Plan(band = listOf("mid"), weights = mapOf("th" to 1.0, "s/z" to 1.0, "i/ii" to 0.0, "b/v" to 0.0)), levels = null)
        assertEquals(mapOf("th" to 1, "s/z" to 1), t.levels)
        assertEquals(mapOf("s/z" to listOf("mid")), t.poolBands)
        assertEquals(listOf("mid"), t.bandsFor("s/z"))
        assertEquals(emptyList<String>(), t.bandsFor("th"))
        assertTrue(t.skipped.getValue("th").startsWith("no pair in bands"))
        val trials = run(t, 40)
        assertTrue(trials.all { it.contrast == "s/z" && it.pair == "s/z:sip-zip" && it.band == "mid" })
        // the same ceiling with a stored level 3 stays within the ceiling: still [mid]
        val st3 = LearnerState(contrasts = mapOf("s/z" to ContrastState(level = 3)))
        val u = scheduler(Plan(band = listOf("mid"), weights = mapOf("th" to 0.0, "s/z" to 1.0, "i/ii" to 0.0, "b/v" to 0.0)), state = st3, levels = null)
        assertEquals(listOf("mid"), u.bandsFor("s/z"))
    }

    @Test
    fun level4HalvesTheShare() {
        val st = LearnerState(contrasts = mapOf("th" to ContrastState(level = 4)))
        val s = scheduler(Plan(weights = mapOf("th" to 1.0, "s/z" to 1.0, "i/ii" to 0.0, "b/v" to 0.0), levels = mapOf("s/z" to 2)), state = st, levels = null)
        assertEquals(0.5, s.levelModifier("th"), 1e-9)
        assertEquals(1.0, s.levelModifier("s/z"), 1e-9)
        assertEquals(0.5, s.effectiveWeight("th"), 1e-9)
        assertEquals(1.0, s.effectiveWeight("s/z"), 1e-9)
        val p = s.contrastProbabilities()
        assertEquals(1.0 / 3.0, p.getValue("th"), 1e-9)
        assertEquals(2.0 / 3.0, p.getValue("s/z"), 1e-9)
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
