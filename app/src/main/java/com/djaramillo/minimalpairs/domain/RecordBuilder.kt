package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.ContrastSummary
import com.djaramillo.minimalpairs.domain.model.SessionRecord
import com.djaramillo.minimalpairs.domain.model.Summary
import com.djaramillo.minimalpairs.domain.model.TrialRow
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** Builds the immutable `sessions/<id>.json` record from the answered trials. */
object RecordBuilder {

    fun build(
        started: Instant,
        ended: Instant,
        appVersion: String,
        catalogVersion: String,
        planSource: String,
        planWritten: String?,
        voices: List<String>,
        trials: List<TrialRow>,
        untrainedShortfall: Int,
    ): SessionRecord = SessionRecord(
        version = 1,
        id = TimeUtil.sessionIdFrom(started),
        started = TimeUtil.formatIso(started),
        ended = TimeUtil.formatIso(ended),
        appVersion = appVersion,
        catalogVersion = catalogVersion,
        planSource = planSource,
        planWritten = planWritten,
        voices = voices,
        trials = trials,
        summary = summary(trials, started, ended, untrainedShortfall),
    )

    fun summary(trials: List<TrialRow>, started: Instant, ended: Instant, untrainedShortfall: Int): Summary {
        val n = trials.size
        val correct = trials.count { it.correct }
        val untrained = trials.filter { !it.trained }
        val untrainedCorrect = untrained.count { it.correct }
        val duration = Duration.between(started, ended).seconds.coerceAtLeast(0L).toInt()
        val perContrast = LinkedHashMap<String, ContrastSummary>()
        for ((id, rows) in trials.groupBy { it.contrast }) {
            val ut = rows.filter { !it.trained }
            perContrast[id] = ContrastSummary(
                trials = rows.size,
                correct = rows.count { it.correct },
                untrainedTrials = ut.size,
                untrainedCorrect = ut.count { it.correct },
                meanRtMs = meanRt(rows),
            )
        }
        return Summary(
            trials = n,
            correct = correct,
            pct = ratio(correct, n),
            untrainedTrials = untrained.size,
            untrainedCorrect = untrainedCorrect,
            untrainedPct = ratio(untrainedCorrect, untrained.size),
            durationS = duration,
            meanRtMs = meanRt(trials),
            untrainedShortfall = untrainedShortfall,
            contrasts = perContrast,
        )
    }

    /** `num / den` rounded to 4 decimals; 0.0 when `den` is 0. */
    fun ratio(num: Int, den: Int): Double =
        if (den <= 0) 0.0 else Math.round(num.toDouble() / den * 10000.0) / 10000.0

    fun meanRt(rows: List<TrialRow>): Int =
        if (rows.isEmpty()) 0 else (rows.sumOf { it.rtMs.toDouble() } / rows.size).roundToInt()
}
