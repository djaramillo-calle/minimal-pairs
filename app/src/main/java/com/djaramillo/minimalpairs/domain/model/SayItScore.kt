package com.djaramillo.minimalpairs.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `sayit/scores/<ts>_<id>.json` — one scored attempt, written by the app
 * (docs/CONTRACT.md, "`sayit/scores/`").
 *
 * It is the third and last thing the app writes into the shared folder, beside
 * the attempt's own two files and under the very same `<ts>_<id>` stem. One
 * immutable file per scored attempt, never a shared mutable one: the coach
 * keeps the history in `results.json`, which the app still never writes, and a
 * file the app wrote once is never edited afterwards.
 *
 * Every number here came back from Azure Pronunciation Assessment, scored
 * against [sentence] — the coach's own string, copied verbatim — with the
 * learner's phone-only Speech resource. The app never estimates a score: when
 * the assessment does not happen, or does not come back usable, no file is
 * written at all and the recording waits for the coach ([source] therefore
 * only ever says where a score that exists came from).
 */
@Serializable
data class AttemptScore(
    val version: Int = 1,
    /** The coach's own word id, copied verbatim from `words.json` — never the file-name spelling. */
    val id: String,
    val word: String,
    /** Copied verbatim from `words.json`: the reference text the scores are against. */
    val sentence: String,
    /** The attempt's `started`, so this file, the audio and the sidecar all name one second. */
    val at: String,
    /** Who scored it. The phone writes only [SOURCE_PHONE_AZURE]. */
    val source: String = SOURCE_PHONE_AZURE,
    /** The assessment locale, `en-GB`. */
    val locale: String = LOCALE_EN_GB,
    val accuracy: Double?,
    val fluency: Double?,
    val completeness: Double?,
    /** Azure's overall `PronScore`. */
    val pron: Double?,
    /** The words of [sentence] Azure still marked wrong, in the order it returned them. */
    val flagged: List<String> = emptyList(),
    /** The sidecar beside the recording, `<ts>_<id>.json`; the same stem as this file. */
    @SerialName("attempt_file") val attemptFile: String,
) {
    companion object {
        /** Scored on the phone with the learner's own Azure Speech resource. */
        const val SOURCE_PHONE_AZURE = "phone-azure"

        /** The only locale the phone assesses in: the coach's model clips are en-GB. */
        const val LOCALE_EN_GB = "en-GB"
    }
}
