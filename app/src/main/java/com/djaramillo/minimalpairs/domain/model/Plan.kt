package com.djaramillo.minimalpairs.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `plan.json` as written by the coach (docs/CONTRACT.md). Every field is
 * nullable: absent keys are resolved to defaults by [effectivePlan]. The app
 * never writes this file.
 */
@Serializable
data class Plan(
    val version: Int? = null,
    val written: String? = null,
    @SerialName("written_by") val writtenBy: String? = null,
    val note: String? = null,
    @SerialName("trials_per_session") val trialsPerSession: Int? = null,
    @SerialName("untrained_ratio") val untrainedRatio: Double? = null,
    val voices: List<String>? = null,
    val band: List<String>? = null,
    val feedback: String? = null,
    val weights: Map<String, Double>? = null,
    @SerialName("max_level") val maxLevel: Int? = null,
    /** Pinned levels, contrast id → 1–4; the app never moves a pinned contrast. */
    val levels: Map<String, Int>? = null,
    @SerialName("weekly_minutes_target") val weeklyMinutesTarget: Int? = null,
)

/** Defaults from the "Default when absent" column of docs/CONTRACT.md. */
object PlanDefaults {
    const val TRIALS_PER_SESSION = 40
    const val MIN_TRIALS_PER_SESSION = 10
    const val MAX_TRIALS_PER_SESSION = 120
    const val UNTRAINED_RATIO = 0.5
    /** The band ceiling: all three bands; the level ladder picks within it (docs/ADAPTATION.md). */
    val BANDS: List<String> = listOf("high", "mid", "low")
    val VALID_BANDS: List<String> = listOf("high", "mid", "low")
    val FEEDBACK: Feedback = Feedback.FULL
    const val WRITTEN_BY = "coach"
    const val NOTE = ""
    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 4
    const val WEEKLY_MINUTES_TARGET = 20
    const val MIN_WEEKLY_MINUTES_TARGET = 0
    const val MAX_WEEKLY_MINUTES_TARGET = 300
}

enum class Feedback(val key: String) {
    FULL("full"), BRIEF("brief"), MINIMAL("minimal");

    companion object {
        fun fromKey(key: String?): Feedback? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The learner's manual override from Settings. While [enabled], sessions run
 * on these weights (and trials count when given) and are recorded with
 * `plan_source = "override"`. Contrasts missing from [weights] keep their
 * catalog default; switch a contrast off with weight `0`.
 */
data class Override(
    val enabled: Boolean = false,
    val weights: Map<String, Double> = emptyMap(),
    val trialsPerSession: Int? = null,
)

/** Where the weights of an [EffectivePlan] came from; the value written to `plan_source`. */
object PlanSource {
    const val COACH = "coach"
    const val DEFAULT = "default"
    const val OVERRIDE = "override"
}

/** Fully resolved plan: defaults applied, ranges clamped, ids and voices validated. */
data class EffectivePlan(
    /** `"coach"`, `"default"` or `"override"`. */
    val planSource: String,
    /** `plan.written`, copied into every session record as `plan_written`. */
    val planWritten: String?,
    val writtenBy: String,
    val note: String,
    val trialsPerSession: Int,
    val untrainedRatio: Double,
    /** Voices to draw from: `plan.voices ∩ pack voices`, or the whole pack. Never empty when the pack is not. */
    val voices: List<String>,
    /**
     * The band ceiling, subset of `high`, `mid`, `low`: the bands a contrast may
     * ever use. The level ladder picks within it ([com.djaramillo.minimalpairs.domain.LevelPolicy.bandsFor]).
     */
    val bands: List<String>,
    val feedback: Feedback,
    /** Resolved weight 0–1 for every contrast of the catalog (unknown plan ids dropped). */
    val weights: Map<String, Double>,
    /** Ceiling of the level ladder, 1–4. */
    val maxLevel: Int = PlanDefaults.MAX_LEVEL,
    /** Pinned levels (catalog contrast ids only, values 1–4); a pinned contrast never moves. */
    val levels: Map<String, Int> = emptyMap(),
    /** Consistency target in minutes per week, shown on Home. */
    val weeklyMinutesTarget: Int = PlanDefaults.WEEKLY_MINUTES_TARGET,
) {
    /** Contrast ids the plan asks for (weight > 0), in catalog order. */
    val activeContrastIds: List<String> get() = weights.filter { it.value > 0.0 }.keys.toList()
}

/**
 * Merge the coach plan (possibly null: absent or unparsable file), the catalog
 * defaults and the learner override into an [EffectivePlan].
 *
 * - `null` plan → all defaults, `planSource = "default"`.
 * - Ranges are clamped, unknown bands / feedback values fall back to defaults.
 * - `voices ∩ packVoices`; empty intersection → whole pack.
 * - Weights: unknown contrast ids are dropped, missing ids keep the catalog
 *   `default_weight`, values are clamped to 0–1, non-trainable contrasts get 0.
 * - No usable contrast (all weights 0) → catalog defaults and `planSource = "default"`.
 * - Enabled [override] replaces the weights (and trials count when given) and
 *   yields `planSource = "override"`; the rest still comes from the plan.
 * - The level ceiling, pins and the weekly target are clamped to their
 *   contract ranges; pins for unknown contrast ids are dropped.
 */
fun effectivePlan(
    plan: Plan?,
    catalog: Catalog,
    packVoices: List<String>,
    override: Override? = null,
): EffectivePlan {
    val trialsFromPlan = (plan?.trialsPerSession ?: PlanDefaults.TRIALS_PER_SESSION)
    val trials = (override?.takeIf { it.enabled }?.trialsPerSession ?: trialsFromPlan)
        .coerceIn(PlanDefaults.MIN_TRIALS_PER_SESSION, PlanDefaults.MAX_TRIALS_PER_SESSION)

    val ratio = (plan?.untrainedRatio ?: PlanDefaults.UNTRAINED_RATIO).let {
        if (it.isNaN()) PlanDefaults.UNTRAINED_RATIO else it.coerceIn(0.0, 1.0)
    }

    val voices = plan?.voices
        ?.filter { it in packVoices }
        ?.distinct()
        ?.takeIf { it.isNotEmpty() }
        ?: packVoices.distinct()

    val bands = plan?.band
        ?.filter { it in PlanDefaults.VALID_BANDS }
        ?.distinct()
        ?.takeIf { it.isNotEmpty() }
        ?: PlanDefaults.BANDS

    val feedback = Feedback.fromKey(plan?.feedback) ?: PlanDefaults.FEEDBACK

    val defaultWeights = resolveWeights(catalog, null)
    val overrideOn = override != null && override.enabled

    var source: String
    var weights: Map<String, Double>
    if (overrideOn) {
        source = PlanSource.OVERRIDE
        weights = resolveWeights(catalog, override!!.weights)
    } else if (plan == null) {
        source = PlanSource.DEFAULT
        weights = defaultWeights
    } else {
        source = PlanSource.COACH
        weights = resolveWeights(catalog, plan.weights)
    }
    if (weights.values.none { it > 0.0 }) {
        source = PlanSource.DEFAULT
        weights = defaultWeights
    }

    val maxLevel = (plan?.maxLevel ?: PlanDefaults.MAX_LEVEL)
        .coerceIn(PlanDefaults.MIN_LEVEL, PlanDefaults.MAX_LEVEL)
    val levels = LinkedHashMap<String, Int>()
    for (c in catalog.contrasts) {
        val pinned = plan?.levels?.get(c.id) ?: continue
        levels[c.id] = pinned.coerceIn(PlanDefaults.MIN_LEVEL, PlanDefaults.MAX_LEVEL)
    }
    val weeklyMinutesTarget = (plan?.weeklyMinutesTarget ?: PlanDefaults.WEEKLY_MINUTES_TARGET)
        .coerceIn(PlanDefaults.MIN_WEEKLY_MINUTES_TARGET, PlanDefaults.MAX_WEEKLY_MINUTES_TARGET)

    return EffectivePlan(
        planSource = source,
        planWritten = plan?.written,
        writtenBy = plan?.writtenBy ?: PlanDefaults.WRITTEN_BY,
        note = plan?.note ?: PlanDefaults.NOTE,
        trialsPerSession = trials,
        untrainedRatio = ratio,
        voices = voices,
        bands = bands,
        feedback = feedback,
        weights = weights,
        maxLevel = maxLevel,
        levels = levels,
        weeklyMinutesTarget = weeklyMinutesTarget,
    )
}

private fun resolveWeights(catalog: Catalog, requested: Map<String, Double>?): Map<String, Double> {
    val out = LinkedHashMap<String, Double>()
    for (c in catalog.contrasts) {
        val usable = c.trainable && c.trainablePairs.isNotEmpty()
        val raw = requested?.get(c.id) ?: c.defaultWeight
        val w = if (raw.isNaN()) 0.0 else raw.coerceIn(0.0, 1.0)
        out[c.id] = if (usable) w else 0.0
    }
    return out
}
