package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.Catalog
import com.djaramillo.minimalpairs.domain.model.ContrastState
import com.djaramillo.minimalpairs.domain.model.DayTally
import com.djaramillo.minimalpairs.domain.model.EffectivePlan
import com.djaramillo.minimalpairs.domain.model.LearnerState
import com.djaramillo.minimalpairs.domain.model.PlanDefaults
import com.djaramillo.minimalpairs.domain.model.Practice
import com.djaramillo.minimalpairs.domain.model.SessionRecord
import com.djaramillo.minimalpairs.domain.model.WordState
import kotlin.math.roundToInt

/** Applies one completed [SessionRecord] to the rolling `state.json`. Pure: returns a new state. */
object StateUpdater {
    /** `recent_untrained_pct` keeps the last up-to-5 sessions. */
    const val RECENT_CAP = 5

    /** `practice.days` keeps this many UTC days, the session's day included. */
    const val PRACTICE_DAYS = 120

    /**
     * @param plan the plan the session ran on: pins and `max_level` decide the
     *   level transitions. `null` means no pins and ceiling 4.
     */
    fun apply(state: LearnerState, record: SessionRecord, catalog: Catalog, plan: EffectivePlan? = null): LearnerState {
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

        // Level ladder: one step at most, only on the evidence this session added.
        val pins = plan?.levels ?: emptyMap()
        val maxLevel = plan?.maxLevel ?: PlanDefaults.MAX_LEVEL
        // The list is judged only by the session that appended to it (an untrained trial on the
        // contrast). A contrast with no probe keeps its rung: re-judging the same list would move
        // it again.
        val probed = record.summary.contrasts.filterValues { it.untrainedTrials > 0 }.keys
        for (id in contrasts.keys.toList()) {
            val c = contrasts.getValue(id)
            val current = record.levels[id] ?: LevelPolicy.currentLevel(id, state, pins, maxLevel)
            val next = if (id in probed) LevelPolicy.nextLevel(
                current = current,
                recentUntrainedPct = c.recentUntrainedPct,
                pinned = pins[id],
                maxLevel = maxLevel,
                untrainedEvidence = true,
            ) else pins[id]?.coerceIn(LevelPolicy.MIN_LEVEL, LevelPolicy.MAX_LEVEL)
                ?: current.coerceIn(LevelPolicy.MIN_LEVEL, maxLevel.coerceIn(LevelPolicy.MIN_LEVEL, LevelPolicy.MAX_LEVEL))
            contrasts[id] = c.copy(
                level = next,
                levelChanged = if (next != c.level) record.ended else c.levelChanged,
            )
        }

        val streak = streakAfter(state.streakDays, state.lastSession, record.started)
        val practice = practiceAfter(state.practice, record, streak)

        return state.copy(
            version = 1,
            updated = record.ended,
            appVersion = record.appVersion,
            catalogVersion = record.catalogVersion,
            sessionsCompleted = state.sessionsCompleted + 1,
            streakDays = streak,
            lastSession = record.started,
            planSource = record.planSource,
            contrasts = contrasts,
            words = words,
            practice = practice,
        )
    }

    /**
     * The consistency tally after [record]: the session's UTC day gets one
     * session, its `duration_s` and its perception trials; days
     * older than [PRACTICE_DAYS] (counted back from that day) are dropped;
     * `total_seconds` grows by `duration_s`; `longest_streak` is the maximum
     * of itself and [streak].
     */
    fun practiceAfter(practice: Practice, record: SessionRecord, streak: Int): Practice {
        val day = TimeUtil.utcDay(record.started) ?: TimeUtil.utcDay(record.ended)
        val seconds = record.summary.durationS.coerceAtLeast(0)
        val days = LinkedHashMap(practice.days)
        if (day != null) {
            val key = day.toString()
            val t = days[key] ?: DayTally()
            days[key] = t.copy(
                sessions = t.sessions + 1,
                seconds = t.seconds + seconds,
                perceptionTrials = t.perceptionTrials + record.summary.trials,
            )
            val cutoff = day.minusDays((PRACTICE_DAYS - 1).toLong()).toString()
            days.keys.filter { it < cutoff }.forEach { days.remove(it) }
        }
        return Practice(
            longestStreak = maxOf(practice.longestStreak, streak),
            totalSeconds = practice.totalSeconds + seconds,
            days = days.toSortedMap().let { LinkedHashMap(it) },
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
