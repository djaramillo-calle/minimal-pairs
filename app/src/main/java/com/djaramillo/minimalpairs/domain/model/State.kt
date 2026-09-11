package com.djaramillo.minimalpairs.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `state.json` (docs/CONTRACT.md). Every field has a default so an empty `{}`
 * (or a missing file) parses to a fresh learner.
 */
@Serializable
data class LearnerState(
    val version: Int = 1,
    val updated: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("catalog_version") val catalogVersion: String? = null,
    @SerialName("sessions_completed") val sessionsCompleted: Int = 0,
    @SerialName("streak_days") val streakDays: Int = 0,
    @SerialName("last_session") val lastSession: String? = null,
    @SerialName("plan_source") val planSource: String? = null,
    val contrasts: Map<String, ContrastState> = emptyMap(),
    val words: Map<String, WordState> = emptyMap(),
    /** Say-it history per catalog pair id. */
    val pairs: Map<String, PairState> = emptyMap(),
    /** Consistency tally (docs/ADAPTATION.md "Consistency"). */
    val practice: Practice = Practice(),
) {
    /** Exposures of [word] as a target so far (0 when never heard). */
    fun exposures(word: String): Int = words[word]?.exposures ?: 0

    /** A word is trained once it has been the target at least once. */
    fun isTrained(word: String): Boolean = exposures(word) >= 1
}

@Serializable
data class ContrastState(
    val trials: Int = 0,
    val correct: Int = 0,
    @SerialName("untrained_trials") val untrainedTrials: Int = 0,
    @SerialName("untrained_correct") val untrainedCorrect: Int = 0,
    @SerialName("last_pct") val lastPct: Double? = null,
    @SerialName("last_untrained_pct") val lastUntrainedPct: Double? = null,
    /** Untrained percent of the last up-to-5 sessions that probed this contrast, oldest first. */
    @SerialName("recent_untrained_pct") val recentUntrainedPct: List<Double> = emptyList(),
    @SerialName("mean_rt_ms") val meanRtMs: Int? = null,
    @SerialName("words_trained") val wordsTrained: Int = 0,
    @SerialName("words_total") val wordsTotal: Int = 0,
    /** Rung on the level ladder, 1–4 (docs/ADAPTATION.md). New contrasts start at 1. */
    val level: Int = 1,
    /** When [level] last moved; `null` while it never has. */
    @SerialName("level_changed") val levelChanged: String? = null,
    /** Lifetime Say-it pairs on this contrast. */
    @SerialName("production_pairs") val productionPairs: Int = 0,
    /** Lifetime Say-it points (2 per pair max). */
    @SerialName("production_points") val productionPoints: Int = 0,
    /** points ÷ max points of the most recent session with Say-it pairs on this contrast. */
    @SerialName("last_production_pct") val lastProductionPct: Double? = null,
    /** The last up-to-5 sessions' production percent, oldest first. */
    @SerialName("recent_production_pct") val recentProductionPct: List<Double> = emptyList(),
)

/** `state.json.pairs.<pair id>`: Say-it history of one pair. */
@Serializable
data class PairState(
    val attempts: Int = 0,
    /** Points (0–2) of the most recent attempt. */
    @SerialName("last_points") val lastPoints: Int = 0,
    val best: Int = 0,
    /** Sessions in which the pair scored < 2 points. */
    val fails: Int = 0,
    val last: String? = null,
)

/** `state.json.practice`: consistency, tracked by the app and judged by the coach. */
@Serializable
data class Practice(
    @SerialName("longest_streak") val longestStreak: Int = 0,
    @SerialName("total_seconds") val totalSeconds: Int = 0,
    /** UTC date (`2026-09-11`) → tally, the last 120 days only. */
    val days: Map<String, DayTally> = emptyMap(),
)

@Serializable
data class DayTally(
    val sessions: Int = 0,
    val seconds: Int = 0,
    @SerialName("perception_trials") val perceptionTrials: Int = 0,
    @SerialName("production_pairs") val productionPairs: Int = 0,
)

@Serializable
data class WordState(
    val exposures: Int = 0,
    val correct: Int = 0,
    val last: String? = null,
)
