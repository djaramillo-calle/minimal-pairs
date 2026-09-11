package com.djaramillo.minimalpairs.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `sessions/<id>.json` (docs/CONTRACT.md). Written once, never edited. */
@Serializable
data class SessionRecord(
    val version: Int = 1,
    /** Equals the file name without extension: `started` in basic ISO form. */
    val id: String,
    val started: String,
    val ended: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("catalog_version") val catalogVersion: String,
    @SerialName("plan_source") val planSource: String,
    @SerialName("plan_written") val planWritten: String? = null,
    val voices: List<String> = emptyList(),
    val trials: List<TrialRow> = emptyList(),
    /** Level-ladder snapshot at session start, contrast id → 1–4. */
    val levels: Map<String, Int> = emptyMap(),
    /**
     * Say-it rows; `null` when the block was off (`production_pairs: 0`) or
     * skipped (no microphone, no key, no network). Written as `"production": null`.
     */
    val production: List<ProductionRow>? = null,
    val summary: Summary,
)

/** One Say-it pair: both words recorded and scored; 0, 1 or 2 points. */
@Serializable
data class ProductionRow(
    /** 1-based index within the block. */
    val i: Int,
    val contrast: String,
    val pair: String,
    val a: String,
    val b: String,
    val points: Int,
    /** The contrast's level when the pair was offered. */
    val level: Int,
    /** word → its result; keys are the two words of the pair. */
    val words: Map<String, WordResult> = emptyMap(),
)

/** The verdict on one recorded word (docs/CONTRACT.md "Say it"). */
@Serializable
data class WordResult(
    /** The intended word, the other word, or `"?"`. */
    val heard: String,
    /** Word accuracy 0–100 under the intended reference. */
    val acc: Int,
    /** Word accuracy 0–100 under the other word as reference. */
    @SerialName("acc_other") val accOther: Int,
    /** Differing-phoneme accuracy under the intended reference; `null` when unavailable or skipped. */
    val ph: Int? = null,
    @SerialName("ph_other") val phOther: Int? = null,
    /** `"phoneme:intended word:intended recognition:other"` — always these three, each `intended`, `other` or `none`. */
    val votes: String,
    /** en-GB recognition text, lower-cased, punctuation stripped; `null` when nothing was recognised. */
    val recognised: String? = null,
    /** Recording length in ms before padding. */
    val ms: Int,
    /** Recordings made for this word. */
    val attempts: Int = 1,
)

@Serializable
data class TrialRow(
    /** 1-based trial index. */
    val i: Int,
    val contrast: String,
    val pair: String,
    val target: String,
    val other: String,
    val chosen: String,
    val correct: Boolean,
    val voice: String,
    @SerialName("rt_ms") val rtMs: Int,
    val replays: Int = 0,
    /** Whether `target` had ≥ 1 exposure before this session. */
    val trained: Boolean,
    val band: String,
    val position: String,
)

@Serializable
data class Summary(
    val trials: Int,
    val correct: Int,
    val pct: Double,
    @SerialName("untrained_trials") val untrainedTrials: Int,
    @SerialName("untrained_correct") val untrainedCorrect: Int,
    /** Correct share of the untrained trials; `null` when the session had none (never `0.0`). */
    @SerialName("untrained_pct") val untrainedPct: Double? = null,

    /** Whole session, perception trials and Say it included. */
    @SerialName("duration_s") val durationS: Int,
    /** The perception trials only. */
    @SerialName("perception_duration_s") val perceptionDurationS: Int,
    @SerialName("mean_rt_ms") val meanRtMs: Int,
    @SerialName("untrained_shortfall") val untrainedShortfall: Int = 0,
    val contrasts: Map<String, ContrastSummary> = emptyMap(),
    /** `null` when the Say-it block was off or skipped (written as `"production": null`). */
    val production: ProductionSummary? = null,
)

@Serializable
data class ProductionSummary(
    val pairs: Int,
    val points: Int,
    /** `2 × pairs`. */
    @SerialName("max_points") val maxPoints: Int,
    /** `points ÷ max_points`, 4 decimals; `0.0` when there are no pairs. */
    val pct: Double,
    @SerialName("duration_s") val durationS: Int,
    val contrasts: Map<String, ProductionContrastSummary> = emptyMap(),
)

@Serializable
data class ProductionContrastSummary(
    val pairs: Int,
    val points: Int,
)

@Serializable
data class ContrastSummary(
    val trials: Int,
    val correct: Int,
    @SerialName("untrained_trials") val untrainedTrials: Int,
    @SerialName("untrained_correct") val untrainedCorrect: Int,
    @SerialName("mean_rt_ms") val meanRtMs: Int,
)
