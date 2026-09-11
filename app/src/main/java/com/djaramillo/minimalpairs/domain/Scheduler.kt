package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.Catalog
import com.djaramillo.minimalpairs.domain.model.Contrast
import com.djaramillo.minimalpairs.domain.model.EffectivePlan
import com.djaramillo.minimalpairs.domain.model.LearnerState
import com.djaramillo.minimalpairs.domain.model.Pair
import com.djaramillo.minimalpairs.domain.model.TrialRow
import kotlin.random.Random

/** One trial the scheduler has drawn; the UI plays `target` in `voice` and shows both words. */
data class PlannedTrial(
    /** 1-based trial index (the `i` of the session row). */
    val index: Int,
    val contrast: String,
    val pair: String,
    val target: String,
    val other: String,
    val voice: String,
    /** `target` had ≥ 1 exposure before this session (decided at session start). */
    val trained: Boolean,
    val band: String,
    val position: String,
    /** Show `target` as the left button (else right). Foil goes on the other side. */
    val targetOnLeft: Boolean,
    /** This trial was meant to be an untrained probe (running-ratio rule). */
    val probe: Boolean,
    /** The probe found no untrained word left; the least-exposed word was used instead. */
    val shortfall: Boolean,
) {
    val leftWord: String get() = if (targetOnLeft) target else other
    val rightWord: String get() = if (targetOnLeft) other else target
}

/**
 * Draws the trials of one session exactly as docs/ADAPTATION.md describes.
 *
 * Deterministic for a seeded [Random]: every random choice goes through it and
 * all iteration is over ordered lists.
 *
 * Usage: `next(i)` for i = 1..plan.trialsPerSession, then `record(...)` with
 * the learner's answer so `m_session` and the summary can see it.
 *
 * @param availableWords words that have a clip in every plan voice; contrasts
 *   with any trainable word missing are skipped (partial / placeholder pack).
 * @throws IllegalStateException from the constructor when nothing is schedulable.
 */
class SessionScheduler(
    private val catalog: Catalog,
    val plan: EffectivePlan,
    private val state: LearnerState,
    availableWords: Set<String>,
    private val random: Random,
) {
    companion object {
        /** Minimum draw probability of any contrast the plan weights above 0. */
        const val FLOOR = 0.03
        /** Never the same contrast more than this many times in a row. */
        const val MAX_RUN = 3
        const val MODIFIER_MIN = 0.5
        const val MODIFIER_MAX = 2.0
    }

    /** Contrast id → why it is not in this session (weight 0, not trainable, no pair in band, missing clips). */
    val skipped: Map<String, String>

    /** Contrasts that can be drawn, in catalog order, each with its eligible pairs. */
    val schedulable: List<Contrast>

    private val eligiblePairs: Map<String, List<Pair>>

    private val planned = ArrayList<PlannedTrial>()
    private val answered = ArrayList<TrialRow>()
    /** Contrast id → pair ids used in the current cycle; cleared once every eligible pair has been used. */
    private val usedPairs = HashMap<String, LinkedHashSet<String>>()
    private val sessionTargetCount = HashMap<String, Int>()
    private val sessionTrials = HashMap<String, Int>()
    private val sessionCorrect = HashMap<String, Int>()

    /** `summary.untrained_shortfall` so far. */
    var untrainedShortfall: Int = 0
        private set

    /** Trials drawn so far, in order. */
    val plannedTrials: List<PlannedTrial> get() = planned

    /** Answered trials so far, in order — the rows of the session record. */
    val answeredTrials: List<TrialRow> get() = answered

    init {
        val skippedOut = LinkedHashMap<String, String>()
        val eligible = LinkedHashMap<String, List<Pair>>()
        val bands = plan.bands.toSet()
        for (c in catalog.contrasts) {
            val w = plan.weights[c.id] ?: 0.0
            if (w <= 0.0) { skippedOut[c.id] = "weight 0"; continue }
            if (!c.trainable || c.trainablePairs.isEmpty()) { skippedOut[c.id] = "not trainable"; continue }
            val missing = c.trainableWords().filter { it !in availableWords }
            if (missing.isNotEmpty()) {
                skippedOut[c.id] = "missing clips: " + missing.take(5).joinToString(", ") +
                    (if (missing.size > 5) " (+${missing.size - 5})" else "")
                continue
            }
            val pairs = c.trainablePairs.filter { it.a.band in bands && it.b.band in bands }
            if (pairs.isEmpty()) { skippedOut[c.id] = "no pair in bands ${plan.bands}"; continue }
            eligible[c.id] = pairs
        }
        skipped = skippedOut
        eligiblePairs = eligible
        schedulable = catalog.contrasts.filter { it.id in eligible }
        check(schedulable.isNotEmpty()) {
            "Nothing to schedule: no contrast has weight > 0, trainable pairs in bands ${plan.bands} " +
                "and clips for all its words. Skipped: $skippedOut"
        }
        check(plan.voices.isNotEmpty()) { "Nothing to schedule: the plan has no voices" }
    }

    // ---- weights -------------------------------------------------------

    /** `m_history(c)` from state.json (docs/ADAPTATION.md "Across sessions"). */
    fun historyModifier(contrastId: String): Double {
        val recent = state.contrasts[contrastId]?.recentUntrainedPct ?: return 1.0
        if (recent.isEmpty()) return 1.0
        val last = recent.last()
        if (last < 0.8) return 1.25
        if (recent.size >= 2 && last > 0.95 && recent[recent.size - 2] > 0.95) return 0.75
        return 1.0
    }

    /** `m_session(c)` from the answers recorded so far in this session. */
    fun sessionModifier(contrastId: String): Double {
        val n = sessionTrials[contrastId] ?: 0
        if (n < 4) return 1.0
        val acc = (sessionCorrect[contrastId] ?: 0).toDouble() / n
        if (acc < 0.8) return 1.5
        if (n >= 6 && acc > 0.95) return 0.5
        return 1.0
    }

    /** `m_history × m_session` clamped to [0.5, 2.0]. */
    fun modifier(contrastId: String): Double =
        (historyModifier(contrastId) * sessionModifier(contrastId)).coerceIn(MODIFIER_MIN, MODIFIER_MAX)

    /** `w_eff(c) = w_plan(c) × modifier(c)`. */
    fun effectiveWeight(contrastId: String): Double = (plan.weights[contrastId] ?: 0.0) * modifier(contrastId)

    /**
     * Draw probabilities for the next trial over [schedulable] (after the
     * run-length exclusion), with the 3 % floor applied. Sums to 1.
     */
    fun contrastProbabilities(): Map<String, Double> {
        val candidates = candidates()
        val probs = probabilities(candidates)
        return candidates.indices.associate { candidates[it].id to probs[it] }
    }

    private fun candidates(): List<Contrast> {
        if (schedulable.size > 1 && planned.size >= MAX_RUN) {
            val lastId = planned.last().contrast
            val run = planned.takeLast(MAX_RUN).all { it.contrast == lastId }
            if (run) return schedulable.filter { it.id != lastId }
        }
        return schedulable
    }

    private fun probabilities(cs: List<Contrast>): DoubleArray {
        val n = cs.size
        val w = DoubleArray(n) { effectiveWeight(cs[it].id) }
        val total = w.sum()
        if (n == 0) return DoubleArray(0)
        if (total <= 0.0 || n * FLOOR >= 1.0) return DoubleArray(n) { 1.0 / n }
        val p = DoubleArray(n) { w[it] / total }
        val floored = BooleanArray(n)
        // Lift everything below the floor and rescale the rest; repeat until stable.
        repeat(n) {
            var changed = false
            for (i in 0 until n) if (!floored[i] && p[i] < FLOOR) { floored[i] = true; changed = true }
            if (!changed) return p
            val fixedMass = floored.count { it } * FLOOR
            val freeWeight = (0 until n).filter { !floored[it] }.sumOf { w[it] }
            for (i in 0 until n) {
                p[i] = if (floored[i]) FLOOR else if (freeWeight > 0.0) w[i] / freeWeight * (1.0 - fixedMass) else 0.0
            }
        }
        return p
    }

    private fun drawContrast(): Contrast {
        val cs = candidates()
        val p = probabilities(cs)
        val r = random.nextDouble()
        var acc = 0.0
        for (i in cs.indices) {
            acc += p[i]
            if (r < acc) return cs[i]
        }
        return cs.last()
    }

    // ---- trials --------------------------------------------------------

    private fun exposuresNow(word: String): Int = state.exposures(word) + (sessionTargetCount[word] ?: 0)

    /** Untrained at session start and not yet heard in this session. */
    private fun isFreshUntrained(word: String): Boolean =
        !state.isTrained(word) && (sessionTargetCount[word] ?: 0) == 0

    private fun <T> pick(items: List<T>): T = items[random.nextInt(items.size)]

    /**
     * Draw trial number [trialIndex] (1-based, used as the row's `i`). The
     * running untrained-ratio rule and the run-length rule use the trials
     * already drawn, regardless of the index passed.
     */
    fun next(trialIndex: Int): PlannedTrial {
        val trialsSoFar = planned.size
        val untrainedSoFar = planned.count { !it.trained }
        val probe = untrainedSoFar < plan.untrainedRatio * trialsSoFar

        val contrast = drawContrast()
        val pairs = eligiblePairs.getValue(contrast.id)
        val used = usedPairs.getOrPut(contrast.id) { LinkedHashSet() }
        if (used.size >= pairs.size) used.clear() // every pair seen once: start a new cycle
        val unused = pairs.filter { it.id !in used }

        var pair: Pair
        var target: String
        var shortfall = false
        if (probe) {
            val freshUnused = unused.filter { isFreshUntrained(it.a.word) || isFreshUntrained(it.b.word) }
            val freshAny = if (freshUnused.isNotEmpty()) freshUnused
            else pairs.filter { isFreshUntrained(it.a.word) || isFreshUntrained(it.b.word) }
            if (freshAny.isNotEmpty()) {
                pair = pick(freshAny)
                target = pick(listOf(pair.a.word, pair.b.word).filter { isFreshUntrained(it) })
            } else {
                shortfall = true
                untrainedShortfall++
                pair = pick(unused)
                val ea = exposuresNow(pair.a.word)
                val eb = exposuresNow(pair.b.word)
                target = when {
                    ea < eb -> pair.a.word
                    eb < ea -> pair.b.word
                    else -> if (random.nextBoolean()) pair.a.word else pair.b.word
                }
            }
        } else {
            pair = pick(unused)
            // Random side, but do not play a fresh word a second time this session
            // while the other side is available (it would waste an honest probe).
            val sides = listOf(pair.a.word, pair.b.word)
            val preferred = sides.filter { !(!state.isTrained(it) && (sessionTargetCount[it] ?: 0) > 0) }
            target = pick(preferred.ifEmpty { sides })
        }

        val side = pair.side(target)!!
        val voice = pick(plan.voices)
        val trial = PlannedTrial(
            index = trialIndex,
            contrast = contrast.id,
            pair = pair.id,
            target = target,
            other = pair.other(target)!!,
            voice = voice,
            trained = state.isTrained(target),
            band = side.band,
            position = pair.position,
            targetOnLeft = random.nextBoolean(),
            probe = probe,
            shortfall = shortfall,
        )
        planned.add(trial)
        used.add(pair.id)
        sessionTargetCount[target] = (sessionTargetCount[target] ?: 0) + 1
        return trial
    }

    /**
     * Feed the learner's answer back. Updates `m_session` and returns the row
     * for the session record. [rtMs] is tap minus audio onset of the first play.
     */
    fun record(planned: PlannedTrial, chosen: String, rtMs: Int, replays: Int): TrialRow {
        val correct = chosen == planned.target
        sessionTrials[planned.contrast] = (sessionTrials[planned.contrast] ?: 0) + 1
        if (correct) sessionCorrect[planned.contrast] = (sessionCorrect[planned.contrast] ?: 0) + 1
        val row = TrialRow(
            i = planned.index,
            contrast = planned.contrast,
            pair = planned.pair,
            target = planned.target,
            other = planned.other,
            chosen = chosen,
            correct = correct,
            voice = planned.voice,
            rtMs = rtMs.coerceAtLeast(0),
            replays = replays.coerceAtLeast(0),
            trained = planned.trained,
            band = planned.band,
            position = planned.position,
        )
        answered.add(row)
        return row
    }
}
