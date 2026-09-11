package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.Catalog
import com.djaramillo.minimalpairs.domain.model.ContrastState
import com.djaramillo.minimalpairs.domain.model.LearnerState
import com.djaramillo.minimalpairs.domain.model.SessionRecord
import com.djaramillo.minimalpairs.domain.model.WordState
import kotlin.math.roundToInt

/** Applies one completed [SessionRecord] to the rolling `state.json`. Pure: returns a new state. */
object StateUpdater {
    /** `recent_untrained_pct` keeps the last up-to-5 sessions. */
    const val RECENT_CAP = 5

    fun apply(state: LearnerState, record: SessionRecord, catalog: Catalog): LearnerState {
        val words = LinkedHashMap(state.words)
        for (t in record.trials) {
            val w = words[t.target] ?: WordState()
            words[t.target] = w.copy(
                exposures = w.exposures + 1,
                correct = w.correct + (if (t.correct) 1 else 0),
                last = record.ended,
            )
        }

        val contrasts = LinkedHashMap(state.contrasts)
        for ((id, s) in record.summary.contrasts) {
            val c = contrasts[id] ?: ContrastState()
            val totalTrials = c.trials + s.trials
            val meanRt = if (totalTrials == 0) null else {
                val oldSum = (c.meanRtMs ?: 0).toDouble() * c.trials
                ((oldSum + s.meanRtMs.toDouble() * s.trials) / totalTrials).roundToInt()
            }
            val untrainedPct = if (s.untrainedTrials > 0) RecordBuilder.ratio(s.untrainedCorrect, s.untrainedTrials) else null
            contrasts[id] = c.copy(
                trials = totalTrials,
                correct = c.correct + s.correct,
                untrainedTrials = c.untrainedTrials + s.untrainedTrials,
                untrainedCorrect = c.untrainedCorrect + s.untrainedCorrect,
                lastPct = if (s.trials > 0) RecordBuilder.ratio(s.correct, s.trials) else c.lastPct,
                lastUntrainedPct = untrainedPct ?: c.lastUntrainedPct,
                recentUntrainedPct = if (untrainedPct == null) c.recentUntrainedPct
                else (c.recentUntrainedPct + untrainedPct).takeLast(RECENT_CAP),
                meanRtMs = meanRt,
            )
        }
        // words_trained / words_total for every contrast we know about.
        for (id in contrasts.keys.toList()) {
            val catalogContrast = catalog.contrast(id) ?: continue
            val all = catalogContrast.trainableWords()
            contrasts[id] = contrasts.getValue(id).copy(
                wordsTotal = all.size,
                wordsTrained = all.count { (words[it]?.exposures ?: 0) >= 1 },
            )
        }

        return state.copy(
            version = 1,
            updated = record.ended,
            appVersion = record.appVersion,
            catalogVersion = record.catalogVersion,
            sessionsCompleted = state.sessionsCompleted + 1,
            streakDays = streakAfter(state.streakDays, state.lastSession, record.started),
            lastSession = record.started,
            planSource = record.planSource,
            contrasts = contrasts,
            words = words,
        )
    }

    /**
     * Streak in consecutive UTC days: same day keeps it, the next day extends it,
     * anything else (gap, no history, unparsable) restarts at 1.
     */
    fun streakAfter(currentStreak: Int, lastSessionIso: String?, thisSessionIso: String): Int {
        val today = TimeUtil.utcDay(thisSessionIso) ?: return maxOf(1, currentStreak)
        val last = TimeUtil.utcDay(lastSessionIso) ?: return 1
        return when {
            last == today -> maxOf(1, currentStreak)
            last.plusDays(1) == today -> currentStreak + 1
            else -> 1
        }
    }
}
