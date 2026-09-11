package com.djaramillo.minimalpairs.ui

import com.djaramillo.minimalpairs.domain.model.ContrastState
import com.djaramillo.minimalpairs.domain.model.DayTally
import com.djaramillo.minimalpairs.domain.model.LearnerState
import com.djaramillo.minimalpairs.domain.model.Pair
import com.djaramillo.minimalpairs.domain.model.PairWord
import com.djaramillo.minimalpairs.domain.model.WordResult
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

    private val pair = Pair(
        id = "th:think-sink",
        a = PairWord("think", "θˈɪŋk", 1000, "high"),
        b = PairWord("sink", "sˈɪŋk", 1200, "high"),
        diff = listOf("θ", "s"), variant = "θ/s", position = "initial",
    )
    private val hPair = Pair(
        id = "h:our-hour",
        a = PairWord("our", "ˈaʊə", 500, "high"),
        b = PairWord("hour", "ˈaʊə", 900, "high"),
        diff = listOf("", "h"), variant = "/h", position = "initial",
    )

    private fun result(heard: String, acc: Int) =
        WordResult(heard = heard, acc = acc, accOther = 50, ph = null, phOther = null, votes = "phoneme:none word:none recognition:none", recognised = null, ms = 900)

    @Test
    fun hint_follows_heard_and_the_threshold() {
        assertEquals(SayHint.Good, sayHint(pair, "think", result("think", 81), 60))
        assertEquals(SayHint.BelowThreshold(55, 60), sayHint(pair, "think", result("think", 55), 60))
        assertEquals(SayHint.HeardOther("θ", "s"), sayHint(pair, "think", result("sink", 90), 60))
        assertEquals(SayHint.HeardOther("s", "θ"), sayHint(pair, "sink", result("think", 90), 60))
        assertEquals(SayHint.Unclear, sayHint(pair, "think", result("?", 90), 60))
        assertEquals(SayHint.NoSpeech, sayHint(pair, "think", result("?", 0), 60, noSpeech = true))
        assertNull(sayHint(pair, "thing", result("thing", 90), 60))
    }

    @Test
    fun hint_carries_empty_phonemes_for_absent_sounds() {
        assertEquals(SayHint.HeardOther("", "h"), sayHint(hPair, "our", result("hour", 90), 60))
        assertEquals(SayHint.HeardOther("h", ""), sayHint(hPair, "hour", result("our", 90), 60))
    }

    @Test
    fun mic_prompt_is_shown_once_per_request_and_survives_a_restored_activity() {
        assertFalse(shouldPromptMic(request = 0, launched = 0))
        assertTrue(shouldPromptMic(request = 1, launched = 0))
        // Already prompted for this request (a rotation while the system dialog is up).
        assertFalse(shouldPromptMic(request = 1, launched = 1))
        assertTrue(shouldPromptMic(request = 2, launched = 1))
        // After process death the activity's saved id is ahead of the new ViewModel's counter:
        // the block must still get its prompt.
        assertTrue(shouldPromptMic(request = 1, launched = 3))
        // Nothing pending: the caller clears the saved id instead of prompting.
        assertFalse(shouldPromptMic(request = 0, launched = 3))
    }

    @Test
    fun pct_text() {
        assertEquals("78 %", pctText(0.775))
        assertEquals("—", pctText(null))
    }
}
