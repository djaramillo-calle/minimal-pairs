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
)

@Serializable
data class WordState(
    val exposures: Int = 0,
    val correct: Int = 0,
    val last: String? = null,
)
