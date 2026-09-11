package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.Contrast
import com.djaramillo.minimalpairs.domain.model.EffectivePlan
import com.djaramillo.minimalpairs.domain.model.LearnerState
import com.djaramillo.minimalpairs.domain.model.PlanDefaults

/**
 * The per-contrast level ladder of docs/ADAPTATION.md ("The level ladder").
 * Pure functions; the state carries the rung, the plan the ceiling and pins.
 *
 * | Level | Bands (∩ ceiling)   | Share of trials |
 * |-------|---------------------|-----------------|
 * | 1     | high                | normal          |
 * | 2     | high, mid           | normal          |
 * | 3     | high, mid, low      | normal          |
 * | 4     | high, mid, low      | × 0.5           |
 */
object LevelPolicy {
    const val MIN_LEVEL = PlanDefaults.MIN_LEVEL
    const val MAX_LEVEL = PlanDefaults.MAX_LEVEL

    /** Untrained accuracy the last three probing sessions must all reach for a promotion. */
    const val PROMOTE_UNTRAINED = 0.90
    /** Production percent the last two Say-it sessions must both reach for a promotion (when Say it is on). */
    const val PROMOTE_PRODUCTION = 0.75
    /** Untrained accuracy below which two sessions in a row demote (regression guard). */
    const val DEMOTE_UNTRAINED = 0.60
    /** Production percent below which two sessions in a row demote. */
    const val DEMOTE_PRODUCTION = 0.40

    /** Trial-share modifier `m_level`: maintenance at level 4 halves the share. */
    const val MAINTENANCE_MODIFIER = 0.5

    private val LADDER: Map<Int, List<String>> = mapOf(
        1 to listOf("high"),
        2 to listOf("high", "mid"),
        3 to listOf("high", "mid", "low"),
        4 to listOf("high", "mid", "low"),
    )

    /**
     * Word bands a contrast at [level] may use: the ladder's bands for that
     * rung intersected with the plan's [ceiling] (`plan.band`), in ladder
     * order. Out-of-range levels are clamped. May be empty when the ceiling
     * has none of the rung's bands (a contrast then has no eligible pair).
     */
    fun bandsFor(level: Int, ceiling: List<String>): List<String> {
        val rung = LADDER.getValue(level.coerceIn(MIN_LEVEL, MAX_LEVEL))
        return rung.filter { it in ceiling }
    }

    /**
     * The bands [contrast] actually draws from at [level]: [bandsFor] of its
     * rung, or — when that leaves no trainable pair (j/y has no high/high
     * pair; a ceiling of `["mid"]` leaves level 1 nothing) — of the first
     * higher rung that holds one, so nothing weighted above 0 starves. The
     * rung's bands when no rung has a pair (the contrast is then skipped).
     */
    fun poolBands(level: Int, ceiling: List<String>, contrast: Contrast): List<String> {
        val start = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        for (rung in start..MAX_LEVEL) {
            val bands = bandsFor(rung, ceiling)
            if (contrast.trainablePairs.any { it.a.band in bands && it.b.band in bands }) return bands
        }
        return bandsFor(start, ceiling)
    }

    /** `m_level`: 0.5 at level 4 (maintenance), else 1.0. */
    fun levelModifier(level: Int): Double = if (level >= MAX_LEVEL) MAINTENANCE_MODIFIER else 1.0

    /**
     * The rung a contrast sits on at session start: the pin when the plan
     * has one, else the state's level (1 for a new contrast), never above
     * `plan.max_level`. This is what the session record snapshots in `levels`.
     */
    fun currentLevel(contrastId: String, state: LearnerState, plan: EffectivePlan): Int =
        currentLevel(contrastId, state, plan.levels, plan.maxLevel)

    fun currentLevel(contrastId: String, state: LearnerState, pins: Map<String, Int>, maxLevel: Int): Int {
        pins[contrastId]?.let { return it.coerceIn(MIN_LEVEL, MAX_LEVEL) }
        val stored = state.contrasts[contrastId]?.level ?: MIN_LEVEL
        return stored.coerceIn(MIN_LEVEL, maxLevel.coerceIn(MIN_LEVEL, MAX_LEVEL))
    }

    /**
     * The rung after a session, one step at most, on the evidence **this
     * session produced** (docs/ADAPTATION.md "The level ladder"):
     *
     * - a [pinned] contrast never moves (the pin is returned as is);
     * - promotion when this session probed the contrast ([untrainedEvidence])
     *   and the last three untrained results are all ≥ 90 %; when
     *   [productionOn] **and** the contrast has at least two production
     *   results, the last two must also be ≥ 75 % (a contrast with no
     *   production history is promoted on perception alone, so a Say-it block
     *   that never runs cannot freeze the ladder); never above [maxLevel];
     * - demotion when this session probed the contrast and the last two
     *   untrained results are both < 60 %, or when this session produced it
     *   ([productionEvidence], with [productionOn]) and the last two
     *   production results are both < 40 %; never below 1. With Say it off the
     *   production lists are stale evidence and are ignored both ways;
     * - a rung above [maxLevel] (the coach lowered the ceiling) comes down to it.
     *
     * Call it once per session: the lists are the evidence, so a session that
     * added none to a list must not re-judge it — that is what the two
     * evidence flags are for.
     *
     * @param recentUntrainedPct `recent_untrained_pct`, oldest first (this session's value included).
     * @param recentProductionPct `recent_production_pct`, oldest first (this session's value included).
     * @param untrainedEvidence this session appended to `recent_untrained_pct` for the contrast.
     * @param productionEvidence this session appended to `recent_production_pct` for the contrast.
     */
    fun nextLevel(
        current: Int,
        recentUntrainedPct: List<Double>,
        recentProductionPct: List<Double>,
        productionOn: Boolean,
        pinned: Int?,
        maxLevel: Int,
        untrainedEvidence: Boolean = true,
        productionEvidence: Boolean = true,
    ): Int {
        if (pinned != null) return pinned.coerceIn(MIN_LEVEL, MAX_LEVEL)
        val ceiling = maxLevel.coerceIn(MIN_LEVEL, MAX_LEVEL)
        val level = current.coerceIn(MIN_LEVEL, MAX_LEVEL)
        if (level > ceiling) return ceiling

        val u = recentUntrainedPct
        val p = recentProductionPct
        val lastTwoUntrainedBad = untrainedEvidence && u.size >= 2 && u.takeLast(2).all { it < DEMOTE_UNTRAINED }
        val lastTwoProductionBad = productionOn && productionEvidence &&
            p.size >= 2 && p.takeLast(2).all { it < DEMOTE_PRODUCTION }
        if (lastTwoUntrainedBad || lastTwoProductionBad) return (level - 1).coerceAtLeast(MIN_LEVEL)

        val perceptionReady = untrainedEvidence && u.size >= 3 && u.takeLast(3).all { it >= PROMOTE_UNTRAINED }
        // No production history: perception alone decides. Two results or more: they must hold up.
        val productionReady = !productionOn || p.size < 2 || p.takeLast(2).all { it >= PROMOTE_PRODUCTION }
        if (perceptionReady && productionReady && level < ceiling) return level + 1

        return level
    }
}
