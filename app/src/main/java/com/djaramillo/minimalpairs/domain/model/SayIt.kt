package com.djaramillo.minimalpairs.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The files of the Say-it sentence drill (docs/CONTRACT.md, "`sayit.zip`").
 *
 * Two of the three files belong to the coach and the app opens them read-only:
 * [SayItWords] is the `words.json` entry of `sayit.zip`, [SayItResults] its
 * `results.json` entry. The zip is the whole coach → app payload because the
 * Drive service account has no storage quota and can only PATCH a file that
 * already exists, so one file is overwritten in place forever.
 * The app writes only its own attempts, and [AttemptSidecar] is the JSON half
 * of one. The app never scores a Say-it attempt and makes no network call for
 * this mode, so nothing here carries a score the app itself produced: the
 * numbers in [SayItAttemptScore] arrive from the cloud coach after a sync.
 *
 * Every field has a default so that a partial or older file still decodes:
 * a missing key is the contract's "default when absent", never an exception.
 */

/**
 * `words.json` (inside `sayit.zip`) — the flagged words the coach wants practised.
 *
 * The list arrives in any order; the app sorts it ([com.djaramillo.minimalpairs.domain.SayItPlanner.ordered]),
 * because the contract fixes the order rather than leaving it to the writer.
 */
@Serializable
data class SayItWords(
    val version: Int = 1,
    /** When the coach last rebuilt the list; shown in Settings. Null when absent. */
    val written: String? = null,
    /** How many words one run shows, 1–50. Clamped by [com.djaramillo.minimalpairs.domain.SayItPlanner.perSession]. */
    @SerialName("per_session") val perSession: Int = 5,
    val words: List<SayItWord> = emptyList(),
)

/**
 * One flagged word together with the sentence it is practised in.
 *
 * The unit is the word in its sentence, never the word alone: the failures the
 * coach flags are connected-speech failures, and an isolated word is a
 * different motor task. So [sentence] is what is played, shown and recorded,
 * and [word] only says which part of it to highlight.
 */
@Serializable
data class SayItWord(
    /** Stable key, also a file-name part; see [com.djaramillo.minimalpairs.domain.SayItNames.isUsableId]. */
    val id: String = "",
    /** The target word, exactly as it appears inside [sentence]; highlighted on screen. */
    val word: String = "",
    /** The whole sentence. Copied verbatim into the attempt sidecar, never re-wrapped or normalised. */
    val sentence: String = "",
    /**
     * Model clip, a zip entry path (`clips/<id>.ogg`). Empty, null, or pointing
     * anywhere but inside `clips/` means the coach could render no audio: the
     * sentence is still practised, and the screen says the model is
     * unavailable rather than offering a dead button.
     */
    val clip: String? = null,
    /** Shown under the sentence; no IPA line when absent. */
    val ipa: String? = null,
    /** The coach's confusion classes for this word, informational. */
    val classes: List<String> = emptyList(),
    /** How many recordings this word was flagged in. The coach's evidence; a tie-break here. */
    @SerialName("flagged_on") val flaggedOn: Int = 0,
    /** How many times he actually read the word. The coach's evidence. */
    @SerialName("read_on") val readOn: Int = 0,
    /**
     * The coach's `flagged_on / read_on` ratio, and the primary sort key: the
     * worst offenders come first. A count alone would promote function words
     * ("the" flagged three times in two hundred readings is noise, while
     * "imperialist" flagged three times in four is broken).
     */
    @SerialName("miss_rate") val missRate: Double? = null,
    /** `YYYY-MM-DD`, when the word entered the set. */
    val added: String? = null,
)

/**
 * `results.json` (inside `sayit.zip`) — what the cloud coach made of the attempts it has seen.
 *
 * Absent or unparsable means no scores yet, not an error: every word is then
 * active and the run's summary says the scores arrive after the next sync.
 */
@Serializable
data class SayItResults(
    val version: Int = 1,
    /** When the coach last wrote the file; shown in Settings. */
    val updated: String? = null,
    /** Keyed by [SayItWord.id]. */
    val words: Map<String, SayItWordResult> = emptyMap(),
)

/** Every score the coach has for one word, plus whether the app should keep showing it. */
@Serializable
data class SayItWordResult(
    val attempts: List<SayItAttemptScore> = emptyList(),
    /** Best accuracy so far. */
    val best: Double? = null,
    /** Most recent accuracy. */
    val last: Double? = null,
    /** `active`, `retired` or `tutor`; anything else reads as active ([com.djaramillo.minimalpairs.domain.SayItPlanner.statusOf]). */
    val status: String = "active",
)

/**
 * One scored attempt.
 *
 * [file] is the **sidecar's** file name, which is how the coach records that an
 * attempt has been scored — and therefore also how the app knows which attempt
 * audio it may delete (docs/CONTRACT.md, "Housekeeping"). [at] is the
 * attempt's `started`, informational here.
 */
@Serializable
data class SayItAttemptScore(
    val at: String? = null,
    /** The sidecar file name, e.g. `20260913T180402Z_imperialist.json`. */
    val file: String? = null,
    val accuracy: Double? = null,
    val fluency: Double? = null,
    val pron: Double? = null,
    /** Words Azure still flagged in that attempt. */
    val flagged: List<String> = emptyList(),
)

/**
 * `sayit/attempts/<ts>_<id>.json` — the sidecar the app writes beside the
 * recording. The two attempt files are the only things the app ever creates
 * in the folder for this mode; `sayit.zip` is read-only to it.
 *
 * [sentence] is copied verbatim from `words.json`: the cloud scores the audio
 * against this string, so a drifted one silently ruins the score. [started]
 * equals the `<ts>` of both file names, so the name and the timestamp can
 * never disagree. Fields are required rather than defaulted because a sidecar
 * the app writes is always complete.
 */
@Serializable
data class AttemptSidecar(
    val version: Int = 1,
    val id: String,
    val word: String,
    val sentence: String,
    /** UTC, seconds precision, `2026-09-13T18:04:02Z`. */
    val started: String,
    /** Recorded length in seconds, one decimal. */
    @SerialName("duration_s") val durationS: Double,
    /** The app's `versionName`. */
    @SerialName("app_version") val appVersion: String,
    /** How many times the model clip was played for this word before the recording finished. */
    @SerialName("clip_played") val clipPlayed: Int,
)
