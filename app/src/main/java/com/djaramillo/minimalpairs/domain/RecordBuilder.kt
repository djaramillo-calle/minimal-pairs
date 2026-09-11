package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.ContrastSummary
import com.djaramillo.minimalpairs.domain.model.ProductionContrastSummary
import com.djaramillo.minimalpairs.domain.model.ProductionRow
import com.djaramillo.minimalpairs.domain.model.ProductionSummary
import com.djaramillo.minimalpairs.domain.model.SessionRecord
import com.djaramillo.minimalpairs.domain.model.Summary
import com.djaramillo.minimalpairs.domain.model.TrialRow
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** Builds the immutable `sessions/<id>.json` record from the answered trials and the Say-it rows. */
object RecordBuilder {

    /**
     * @param levels the ladder snapshot at session start ([SessionScheduler.levels]).
     * @param production the scored Say-it rows; `null` (or empty) when the block was off or skipped
     *   → `"production": null` and `summary.production: null`.
     * @param perceptionEnded when the last perception trial was answered; `null` = [ended]
     *   (no Say-it block, or its timing unknown) so `perception_duration_s` = `duration_s`.
     * @param productionStarted when the Say-it block began; `null` = [perceptionEnded], so
     *   `summary.production.duration_s` is the time after the trials (0 when that is unknown too).
     */
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
        levels: Map<String, Int> = emptyMap(),
        production: List<ProductionRow>? = null,
        perceptionEnded: Instant? = null,
        productionStarted: Instant? = null,
    ): SessionRecord {
        val rows = production?.takeIf { it.isNotEmpty() }
        return SessionRecord(
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
            levels = levels,
            production = rows,
            summary = summary(trials, started, ended, untrainedShortfall, rows, perceptionEnded, productionStarted),
        )
    }

    fun summary(
        trials: List<TrialRow>,
        started: Instant,
        ended: Instant,
        untrainedShortfall: Int,
        production: List<ProductionRow>? = null,
        perceptionEnded: Instant? = null,
        productionStarted: Instant? = null,
    ): Summary {
        val n = trials.size
        val correct = trials.count { it.correct }
        val untrained = trials.filter { !it.trained }
        val untrainedCorrect = untrained.count { it.correct }
        val duration = seconds(started, ended)
        val perceptionEnd = perceptionEnded ?: ended
        val perceptionDuration = minOf(seconds(started, perceptionEnd), duration)
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
        val rows = production?.takeIf { it.isNotEmpty() }
        val productionSummary = rows?.let {
            val begin = productionStarted ?: perceptionEnd
            productionSummary(it, minOf(seconds(begin, ended), duration))
        }
        return Summary(
            trials = n,
            correct = correct,
            pct = ratio(correct, n),
            untrainedTrials = untrained.size,
            untrainedCorrect = untrainedCorrect,
            untrainedPct = if (untrained.isEmpty()) null else ratio(untrainedCorrect, untrained.size),

            durationS = duration,
            perceptionDurationS = perceptionDuration,
            meanRtMs = meanRt(trials),
            untrainedShortfall = untrainedShortfall,
            contrasts = perContrast,
            production = productionSummary,
        )
    }

    /** `summary.production` for the scored rows: points over `2 × pairs`, per contrast too. */
    fun productionSummary(rows: List<ProductionRow>, durationS: Int): ProductionSummary {
        val points = rows.sumOf { it.points }
        val perContrast = LinkedHashMap<String, ProductionContrastSummary>()
        for ((id, rs) in rows.groupBy { it.contrast }) {
            perContrast[id] = ProductionContrastSummary(pairs = rs.size, points = rs.sumOf { it.points })
        }
        return ProductionSummary(
            pairs = rows.size,
            points = points,
            maxPoints = rows.size * 2,
            pct = ratio(points, rows.size * 2),
            durationS = durationS.coerceAtLeast(0),
            contrasts = perContrast,
        )
    }

    private fun seconds(from: Instant, to: Instant): Int =
        Duration.between(from, to).seconds.coerceAtLeast(0L).toInt()

    /** `num / den` rounded to 4 decimals; 0.0 when `den` is 0. */
    fun ratio(num: Int, den: Int): Double =
        if (den <= 0) 0.0 else Math.round(num.toDouble() / den * 10000.0) / 10000.0

    fun meanRt(rows: List<TrialRow>): Int =
        if (rows.isEmpty()) 0 else (rows.sumOf { it.rtMs.toDouble() } / rows.size).roundToInt()
}
