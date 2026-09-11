package com.djaramillo.minimalpairs.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.djaramillo.minimalpairs.domain.Ipa
import com.djaramillo.minimalpairs.domain.model.DayTally
import com.djaramillo.minimalpairs.domain.model.LearnerState
import com.djaramillo.minimalpairs.domain.model.Pair
import com.djaramillo.minimalpairs.domain.model.WordResult
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** `0.775` → `78 %`; null → `—`. */
fun pctText(v: Double?): String = if (v == null) "—" else "${(v * 100).roundToInt()} %"

// ---- pure helpers behind Home / Summary / Say it (unit tested in ui/CommonTest) ----

/** Direction of a contrast's last untrained result against the one before it. */
enum class Trend { UP, DOWN, FLAT }

/**
 * [Trend] from `recent_untrained_pct` (oldest first): the last value against
 * the previous one, [tolerance] either way counting as flat. `null` with
 * fewer than two results.
 */
fun trendOf(recent: List<Double>, tolerance: Double = TREND_TOLERANCE): Trend? {
    if (recent.size < 2) return null
    val d = recent[recent.size - 1] - recent[recent.size - 2]
    return when {
        abs(d) <= tolerance + 1e-9 -> Trend.FLAT
        d > 0 -> Trend.UP
        else -> Trend.DOWN
    }
}

const val TREND_TOLERANCE = 0.02

/** Monday of the ISO week that holds [day]. */
fun weekStart(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

/**
 * Minutes practised in the Monday–Sunday week (UTC days, like the tally)
 * that contains [today], from `state.practice.days`; unparsable keys are
 * ignored. Rounded to the nearest minute.
 */
fun weekMinutes(days: Map<String, DayTally>, today: LocalDate): Int {
    val start = weekStart(today)
    val end = start.plusDays(6)
    var seconds = 0L
    for ((key, tally) in days) {
        val d = try { LocalDate.parse(key) } catch (e: java.time.format.DateTimeParseException) { continue }
        if (!d.isBefore(start) && !d.isAfter(end)) seconds += tally.seconds.coerceAtLeast(0)
    }
    return ((seconds + 30) / 60).toInt()
}

/** A rung that moved when a session was applied ("th moved to level 2"). */
data class LevelChange(val id: String, val from: Int, val to: Int)

/** Contrasts whose level differs between [before] and [after], in [after]'s order; a new contrast counts from level 1. */
fun levelChanges(before: LearnerState, after: LearnerState): List<LevelChange> =
    after.contrasts.mapNotNull { (id, c) ->
        val from = before.contrasts[id]?.level ?: 1
        if (c.level != from) LevelChange(id, from, c.level) else null
    }

/** The one-line verdict shown under a recorded word in the Say-it block. */
sealed class SayHint {
    /** Heard as intended and above the threshold: the point is earned. */
    data object Good : SayHint()
    /** Heard as intended but the accuracy missed `production_threshold`. */
    data class BelowThreshold(val acc: Int, val threshold: Int) : SayHint()
    /**
     * Heard as the other word. [intended] / [other] are the differing phonemes
     * of the two words (`""` for an absent one: `our` vs `hour`), so the screen
     * can say "the θ was heard as s", "the h was not heard" or "an extra t was heard".
     */
    data class HeardOther(val intended: String, val other: String) : SayHint()
    /** The votes tied or nothing voted (`heard == "?"`). */
    data object Unclear : SayHint()
    /** Nothing was said twice: scored without an Azure call. */
    data object NoSpeech : SayHint()
}

/**
 * The hint for [word]'s [result] in [pair]; `null` when [word] is not in the
 * pair. [noSpeech] marks a word scored without an Azure call because two
 * recordings held no speech.
 */
fun sayHint(pair: Pair, word: String, result: WordResult, threshold: Int, noSpeech: Boolean = false): SayHint? {
    val other = pair.other(word) ?: return null
    if (noSpeech) return SayHint.NoSpeech
    val isA = word == pair.a.word
    val diffIntended = pair.diff.getOrNull(if (isA) 0 else 1) ?: ""
    val diffOther = pair.diff.getOrNull(if (isA) 1 else 0) ?: ""
    return when (result.heard) {
        word -> if (result.acc >= threshold) SayHint.Good else SayHint.BelowThreshold(result.acc, threshold)
        other -> SayHint.HeardOther(diffIntended, diffOther)
        else -> SayHint.Unclear
    }
}

/**
 * Whether the activity must show the `RECORD_AUDIO` prompt now. [request] is
 * the ViewModel's pending request id (`0`: nothing pending), [launched] the id
 * the activity has already prompted for and saved across rotation. The two are
 * compared for inequality, not order: after process death the saved id survives
 * while the ViewModel's counter starts at 0 again, and a "greater than" test
 * would then never prompt again (the Say-it block would sit at "Preparing Say
 * it…" for good). A request of `0` means nothing is pending and the caller
 * clears [launched] instead.
 */
fun shouldPromptMic(request: Int, launched: Int): Boolean = request != 0 && request != launched

fun ratioText(v: Double): String = String.format(Locale.UK, "%.2f", v)

fun bytesText(bytes: Long): String = when {
    bytes >= 1_000_000L -> String.format(Locale.UK, "%.1f MB", bytes / 1e6)
    bytes >= 1_000L -> String.format(Locale.UK, "%.0f kB", bytes / 1e3)
    else -> "$bytes B"
}

/** IPA with the differing phoneme in colour + bold; stress marks shown at the syllable onset (/ˈʃɪp/). */
@Composable
fun ipaAnnotated(h: Ipa.Highlight, highlight: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        val d = Ipa.display(h)
        append("/")
        append(d.before)
        if (d.range != null) {
            withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.Bold)) { append(d.highlighted) }
            append(d.after)
        }
        append("/")
    }

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
            content()
        }
    }
}
