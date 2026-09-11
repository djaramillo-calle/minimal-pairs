package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.Catalog
import com.djaramillo.minimalpairs.domain.model.Contrast
import com.djaramillo.minimalpairs.domain.model.EffectivePlan
import com.djaramillo.minimalpairs.domain.model.LearnerState
import com.djaramillo.minimalpairs.domain.model.Pair
import com.djaramillo.minimalpairs.domain.model.PairState
import kotlin.random.Random

/**
 * Picks the pairs of the Say-it block (docs/ADAPTATION.md "Say it — what adapts").
 *
 * 1. Contrast shares: `w_eff × (1 + (1 − last_production_pct))`, with
 *    `last_production_pct` taken as 0 for a contrast never produced (so an
 *    untested contrast weighs double). `w_eff = w_plan × m_history × m_session × m_level`,
 *    `m_session` from the perception block just played (1 when not given).
 * 2. Within a contrast, in order: pairs scored below 2 last time (due again),
 *    pairs missed in this session's perception trials, never-attempted pairs
 *    (commonest first), then the rest (least recently attempted first).
 *    Both words must be in the level's bands ∩ ceiling ([LevelPolicy.poolBands],
 *    so a rung without a pair widens) and have clips when [pick] is given
 *    `availableWords`. Never the same pair twice; a pair that
 *    scored 2 in the very last session is left out.
 * 3. Contrasts with weight 0, non-trainable or production-only are excluded.
 *
 * Deterministic for a seeded [Random]: the only random choice is the contrast draw.
 */
object ProductionPlanner {

    /** A contrast's share of the block; 0 when it cannot be produced. */
    fun contrastShare(
        contrastId: String,
        plan: EffectivePlan,
        state: LearnerState,
        sessionModifier: Double = 1.0,
    ): Double {
        val w = plan.weights[contrastId] ?: 0.0
        if (w <= 0.0) return 0.0
        val level = LevelPolicy.currentLevel(contrastId, state, plan)
        val modifier = (historyModifier(state, contrastId) * sessionModifier)
            .coerceIn(SessionScheduler.MODIFIER_MIN, SessionScheduler.MODIFIER_MAX)
        val wEff = w * modifier * LevelPolicy.levelModifier(level)
        val lastPct = state.contrasts[contrastId]?.lastProductionPct ?: 0.0
        return wEff * (1.0 + (1.0 - lastPct.coerceIn(0.0, 1.0)))
    }

    /**
     * The pairs of [contrast] a Say-it block may offer, in priority order
     * (rule 2 above). Empty when the contrast is excluded (rule 3).
     */
    fun queue(
        contrast: Contrast,
        plan: EffectivePlan,
        state: LearnerState,
        perceptionMissesThisSession: Set<String>,
        availableWords: Set<String>? = null,
    ): List<Pair> {
        if ((plan.weights[contrast.id] ?: 0.0) <= 0.0) return emptyList()
        if (!contrast.trainable || contrast.productionOnly) return emptyList()
        val level = LevelPolicy.currentLevel(contrast.id, state, plan)
        val bands = LevelPolicy.poolBands(level, plan.bands, contrast).toSet()
        val lastSession = state.lastSession

        val candidates = contrast.trainablePairs.filter { p ->
            p.a.band in bands && p.b.band in bands &&
                (availableWords == null || (p.a.word in availableWords && p.b.word in availableWords)) &&
                !scoredFullLastSession(state.pairs[p.id], lastSession)
        }

        fun rankSum(p: Pair): Int = (p.a.rank ?: Int.MAX_VALUE / 2) + (p.b.rank ?: Int.MAX_VALUE / 2)
        fun tier(p: Pair): Int {
            val ps = state.pairs[p.id]
            return when {
                ps != null && ps.attempts > 0 && ps.lastPoints < 2 -> 1
                p.id in perceptionMissesThisSession -> 2
                ps == null || ps.attempts == 0 -> 3
                else -> 4
            }
        }
        return candidates.sortedWith(
            compareBy<Pair> { tier(it) }
                .thenBy { if (tier(it) == 1) state.pairs[it.id]?.lastPoints ?: 0 else 0 }
                .thenBy { if (tier(it) == 1 || tier(it) == 4) state.pairs[it.id]?.last ?: "" else "" }
                .thenBy { rankSum(it) }
        )
    }

    /** A pair that earned both points in the most recent session is not offered in the very next one. */
    fun scoredFullLastSession(ps: PairState?, lastSessionIso: String?): Boolean {
        if (ps == null || ps.lastPoints < 2) return false
        val last = ps.last ?: return false
        val session = lastSessionIso ?: return false
        return last >= session
    }

    /**
     * Pick up to [n] pairs for the block.
     *
     * @param perceptionMissesThisSession pair ids answered wrongly in this session's perception trials.
     * @param availableWords words with a clip (model voice); `null` = no filter.
     * @param sessionModifiers `m_session` per contrast from the perception block ([SessionScheduler.sessionModifier]); absent = 1.
     */
    fun pick(
        n: Int,
        catalog: Catalog,
        plan: EffectivePlan,
        state: LearnerState,
        perceptionMissesThisSession: Set<String>,
        random: Random,
        availableWords: Set<String>? = null,
        sessionModifiers: Map<String, Double> = emptyMap(),
    ): List<Pair> {
        if (n <= 0) return emptyList()
        val queues = LinkedHashMap<String, ArrayDeque<Pair>>()
        val shares = LinkedHashMap<String, Double>()
        for (c in catalog.contrasts) {
            val q = queue(c, plan, state, perceptionMissesThisSession, availableWords)
            if (q.isEmpty()) continue
            val share = contrastShare(c.id, plan, state, sessionModifiers[c.id] ?: 1.0)
            if (share <= 0.0) continue
            queues[c.id] = ArrayDeque(q)
            shares[c.id] = share
        }
        val out = ArrayList<Pair>(n)
        while (out.size < n) {
            val live = queues.filter { it.value.isNotEmpty() }.keys.toList()
            if (live.isEmpty()) break
            val id = draw(live, live.map { shares.getValue(it) }, random)
            out.add(queues.getValue(id).removeFirst())
        }
        return out
    }

    private fun draw(ids: List<String>, weights: List<Double>, random: Random): String {
        val total = weights.sum()
        if (total <= 0.0) return ids[random.nextInt(ids.size)]
        val r = random.nextDouble() * total
        var acc = 0.0
        for (i in ids.indices) {
            acc += weights[i]
            if (r < acc) return ids[i]
        }
        return ids.last()
    }
}
