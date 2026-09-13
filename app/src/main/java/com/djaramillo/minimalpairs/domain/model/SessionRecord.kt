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
    val summary: Summary,
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

    /** Whole session, start to Summary. */
    @SerialName("duration_s") val durationS: Int,
    @SerialName("mean_rt_ms") val meanRtMs: Int,
    @SerialName("untrained_shortfall") val untrainedShortfall: Int = 0,
    val contrasts: Map<String, ContrastSummary> = emptyMap(),
)

@Serializable
data class ContrastSummary(
    val trials: Int,
    val correct: Int,
    @SerialName("untrained_trials") val untrainedTrials: Int,
    @SerialName("untrained_correct") val untrainedCorrect: Int,
    @SerialName("mean_rt_ms") val meanRtMs: Int,
)
