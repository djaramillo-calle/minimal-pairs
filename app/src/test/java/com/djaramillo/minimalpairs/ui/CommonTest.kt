package com.djaramillo.minimalpairs.ui

import com.djaramillo.minimalpairs.domain.model.ContrastState
import com.djaramillo.minimalpairs.domain.model.DayTally
import com.djaramillo.minimalpairs.domain.model.LearnerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CommonTest {

    @Test
    fun trend_compares_the_last_two_results_with_a_tolerance() {
        assertNull(trendOf(emptyList()))
        assertNull(trendOf(listOf(0.8)))
        assertEquals(Trend.UP, trendOf(listOf(0.6, 0.7, 0.75)))
        assertEquals(Trend.DOWN, trendOf(listOf(0.9, 0.7)))
        assertEquals(Trend.FLAT, trendOf(listOf(0.5, 0.80, 0.81)))
        assertEquals(Trend.FLAT, trendOf(listOf(0.80, 0.78)))
        assertEquals(Trend.DOWN, trendOf(listOf(0.80, 0.77)))
    }

    @Test
    fun week_runs_monday_to_sunday_and_rounds_to_minutes() {
        // 2026-09-11 is a Friday: the week is 2026-09-07 … 2026-09-13.
        val today = LocalDate.of(2026, 9, 11)
        assertEquals(LocalDate.of(2026, 9, 7), weekStart(today))
        assertEquals(LocalDate.of(2026, 9, 7), weekStart(LocalDate.of(2026, 9, 7)))
        assertEquals(LocalDate.of(2026, 9, 7), weekStart(LocalDate.of(2026, 9, 13)))
        val days = mapOf(
            "2026-09-06" to DayTally(sessions = 1, seconds = 600), // Sunday before: out
            "2026-09-07" to DayTally(sessions = 1, seconds = 290),
            "2026-09-11" to DayTally(sessions = 2, seconds = 500),
            "2026-09-13" to DayTally(sessions = 1, seconds = 100), // Sunday: in
            "2026-09-14" to DayTally(sessions = 1, seconds = 6000), // next Monday: out
            "not-a-date" to DayTally(sessions = 1, seconds = 6000),
        )
        // 890 s → 14.8 → 15 min
        assertEquals(15, weekMinutes(days, today))
        assertEquals(0, weekMinutes(emptyMap(), today))
    }

    @Test
    fun level_changes_list_only_moved_contrasts_in_state_order() {
        val before = LearnerState(contrasts = mapOf("th" to ContrastState(level = 1), "s/z" to ContrastState(level = 3)))
        val after = LearnerState(
            contrasts = linkedMapOf(
                "th" to ContrastState(level = 2),
                "s/z" to ContrastState(level = 3),
                "b/v" to ContrastState(level = 1),
                "i/ii" to ContrastState(level = 2),
            ),
        )
        assertEquals(listOf(LevelChange("th", 1, 2), LevelChange("i/ii", 1, 2)), levelChanges(before, after))
        assertEquals(emptyList<LevelChange>(), levelChanges(after, after))
    }

    @Test
    fun pct_text() {
        assertEquals("78 %", pctText(0.775))
        assertEquals("—", pctText(null))
    }
}
