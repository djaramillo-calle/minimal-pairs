package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.AttemptScore
import com.djaramillo.minimalpairs.domain.model.SayItWord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Everything about scoring a Say-it attempt that is not a socket: the request
 * Azure is asked with, the answer it gives read into four numbers and a list of
 * words, and the decision of whether a `sayit/scores/<ts>_<id>.json` may be
 * written at all (docs/CONTRACT.md, "`sayit/scores/`").
 *
 * Two rules run through all of it.
 *
 * **The unit is the word in its sentence.** The reference text is the
 * [SayItWord.sentence] the coach wrote, copied verbatim into the request and
 * into the file — never the word alone, never anything rebuilt from what the
 * screen laid out. The failures being drilled are connected-speech failures,
 * and an isolated word is a different motor task.
 *
 * **No score is better than a wrong score.** Every path that cannot produce a
 * real Azure number — no key, no network, an HTTP error, a recording Azure
 * heard nothing in, an answer with no assessment in it — ends in
 * [Outcome.NotScored], which writes no file and leaves the attempt for the
 * coach. Nothing here estimates, interpolates or defaults a score.
 *
 * No Android types and no I/O: unit tested on the JVM, and the tests never call
 * Azure.
 */
object SayItScoring {

    /** `RecognitionStatus` that means Azure heard the sentence. */
    const val STATUS_SUCCESS = "Success"

    /** The assessment locale; the coach's model clips are en-GB and so is the learner's target. */
    const val LANGUAGE = AttemptScore.LOCALE_EN_GB

    /** Azure's own `ErrorType` for a word it had nothing against. */
    private const val ERROR_NONE = "None"

    /**
     * `ErrorType`s that mean a word of the **reference sentence** went wrong.
     * `Insertion` is deliberately not among them: it marks a word the learner
     * said that is not in the sentence, so it is not one of the coach's flagged
     * words and listing it would tell him to practise a word he was never asked
     * to say. The prosody types (`UnexpectedBreak`, `MissingBreak`, `Monotone`)
     * are not requested and are not mispronunciations either.
     */
    private val WORD_ERRORS = setOf("Mispronunciation", "Omission")

    /** Why an attempt has no score file. Every one of these keeps the recording and the sidecar. */
    enum class Unscored {
        /** No key or no region in Settings: the ordinary state of a phone that has not been set up. */
        NO_KEY,

        /** The recording could not be turned into the PCM Azure takes. */
        NO_AUDIO,

        /** Azure could not be reached at all — the underground, a dead Wi-Fi, a wrong region. */
        OFFLINE,

        /** Azure answered, but with an error: a rejected key, a throttled resource, a server fault. */
        AZURE_ERROR,

        /** Azure heard no speech (`NoMatch`, `InitialSilenceTimeout`, …). */
        NOT_HEARD,

        /** Azure answered without a usable pronunciation assessment in it. */
        NOT_ASSESSED,

        /** The attempt itself did not reach the folder, so there is nothing a score could belong to. */
        NO_ATTEMPT,
    }

    /** The four numbers and the words Azure still marked wrong. */
    data class Assessment(
        val accuracy: Double?,
        val fluency: Double?,
        val completeness: Double?,
        val pron: Double?,
        val flagged: List<String>,
    ) {
        /** Nothing usable came back; [parse] refuses such an answer rather than writing zeros. */
        val empty: Boolean get() = accuracy == null && fluency == null && completeness == null && pron == null
    }

    /** What is to be done with one finished attempt. */
    sealed class Outcome {
        /** Write [fileName] into `sayit/scores/` with this exact [json], and show [assessment]. */
        data class Scored(
            val fileName: String,
            val json: String,
            val assessment: Assessment,
            val score: AttemptScore,
        ) : Outcome()

        /** Write nothing. The recording and its sidecar stay as they are. */
        data class NotScored(val reason: Unscored) : Outcome()
    }

    // ---- the request ------------------------------------------------------

    /**
     * The `Pronunciation-Assessment` header body before base64.
     *
     * Scripted against the whole sentence, `en-GB`, phonemes, every dimension,
     * and **miscue detection on** so a word he skipped comes back as an
     * `Omission` instead of quietly not lowering anything. Built through the
     * JSON encoder rather than string concatenation: a sentence is the coach's
     * text and may hold a quote, a backslash or a non-ASCII character, and an
     * escape done by hand is exactly how a reference text drifts from the
     * sentence it is supposed to be.
     */
    fun assessmentJson(referenceText: String): String {
        val obj = buildJsonObject {
            put("ReferenceText", referenceText)
            put("GradingSystem", "HundredMark")
            put("Granularity", "Phoneme")
            put("Dimension", "Comprehensive")
            put("EnableMiscue", true)
            put("PhonemeAlphabet", "IPA")
        }
        return compact.encodeToString(JsonObject.serializer(), obj)
    }

    /** Base64 of [assessmentJson], which is what the header carries. */
    fun assessmentHeader(referenceText: String): String =
        Base64.getEncoder().encodeToString(assessmentJson(referenceText).toByteArray(Charsets.UTF_8))

    /** The scripted-assessment endpoint for [region]. */
    fun endpoint(region: String): String =
        "https://$region.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1" +
            "?language=$LANGUAGE&format=detailed"

    // ---- the answer -------------------------------------------------------

    /**
     * Read a `format=detailed` answer into an [Assessment], or null when it
     * holds no score this app may show.
     *
     * Null covers three different answers and the caller tells them apart with
     * [statusOf]: a body that is not the expected JSON, a `RecognitionStatus`
     * other than `Success` (Azure heard no speech), and a `Success` with no
     * `PronunciationAssessment` block or with nothing usable in it. All three
     * end the same way — no file, and the coach scores the attempt — because
     * none of them is a score.
     */
    fun parse(body: String): Assessment? {
        val root = rootOf(body) ?: return null
        if (root["RecognitionStatus"]?.jsonPrimitive?.content != STATUS_SUCCESS) return null
        val best = (root["NBest"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val pa = best["PronunciationAssessment"] as? JsonObject ?: return null
        val assessment = Assessment(
            accuracy = score(pa["AccuracyScore"]),
            fluency = score(pa["FluencyScore"]),
            completeness = score(pa["CompletenessScore"]),
            pron = score(pa["PronScore"]),
            flagged = flaggedWords(best),
        )
        // An assessment with no number in it is not a score. Writing it would
        // put a row in front of the learner that says nothing and a file in
        // front of the coach that means nothing.
        return if (assessment.empty) null else assessment
    }

    /** `RecognitionStatus` of an answer, or null when the body is not the expected JSON. */
    fun statusOf(body: String): String? =
        rootOf(body)?.get("RecognitionStatus")?.jsonPrimitive?.content

    /**
     * Why [body] produced no [Assessment]: [Unscored.NOT_HEARD] when Azure
     * reports it heard no speech, [Unscored.NOT_ASSESSED] for a body that is
     * unreadable or carries no usable assessment.
     */
    fun unscoredReason(body: String): Unscored {
        val status = statusOf(body)
        return if (status != null && status != STATUS_SUCCESS) Unscored.NOT_HEARD else Unscored.NOT_ASSESSED
    }

    /**
     * The words Azure marked wrong, in the order it returned them and each one
     * once. `Words[].Word` is Azure's own spelling of the reference word, which
     * is what the coach's `results.json` `flagged` list holds too.
     */
    private fun flaggedWords(best: JsonObject): List<String> {
        val words = best["Words"] as? JsonArray ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (el in words) {
            val w = el as? JsonObject ?: continue
            val pa = w["PronunciationAssessment"] as? JsonObject
            val error = pa?.get("ErrorType")?.jsonPrimitive?.content ?: ERROR_NONE
            if (error !in WORD_ERRORS) continue
            val text = w["Word"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (text.isNotEmpty()) out.add(text)
        }
        return out.toList()
    }

    /** One Azure score: a finite number in 0–100, rounded to one decimal; null otherwise. */
    private fun score(element: kotlinx.serialization.json.JsonElement?): Double? {
        val v = (element as? JsonPrimitive)?.doubleOrNull ?: return null
        if (!v.isFinite() || v < 0.0 || v > 100.0) return null
        return Math.round(v * 10.0) / 10.0
    }

    private fun rootOf(body: String): JsonObject? = try {
        lenient.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        null
    }

    // ---- the file ---------------------------------------------------------

    /**
     * The score file's name for an attempt stem. It is the stem and nothing
     * else, so the three files of one attempt — `<stem>.m4a`, `<stem>.json` in
     * `attempts/` and `<stem>.json` in `scores/` — always agree, and the coach
     * joins them without parsing anything out of a name.
     */
    fun fileName(stem: String): String = stem + SayItNames.SIDECAR_EXTENSION

    /** The sidecar this score belongs to: the same stem, in `sayit/attempts/`. */
    fun attemptFileName(stem: String): String = fileName(stem)

    /**
     * What to do with a finished attempt.
     *
     * [stem] is the stem `DataFolder` actually wrote the attempt under, not one
     * rebuilt here: a score file may only ever exist for an attempt that
     * reached the folder, and it must carry that attempt's name exactly. A null
     * stem is the attempt that did not land, and scores nothing.
     *
     * [startedIso] is the sidecar's own `started`, for the same reason. [body]
     * is Azure's answer when there was one; [failure] is the reason there was
     * not. When both are present the failure wins — a body that arrived
     * alongside an error is not a result.
     */
    fun outcome(
        word: SayItWord,
        stem: String?,
        startedIso: String,
        body: String?,
        failure: Unscored? = null,
    ): Outcome {
        if (stem.isNullOrEmpty()) return Outcome.NotScored(Unscored.NO_ATTEMPT)
        if (failure != null) return Outcome.NotScored(failure)
        if (body == null) return Outcome.NotScored(Unscored.AZURE_ERROR)
        val assessment = parse(body) ?: return Outcome.NotScored(unscoredReason(body))
        val score = AttemptScore(
            id = word.id,
            word = word.word,
            sentence = word.sentence,
            at = startedIso,
            accuracy = assessment.accuracy,
            fluency = assessment.fluency,
            completeness = assessment.completeness,
            pron = assessment.pron,
            flagged = assessment.flagged,
            attemptFile = attemptFileName(stem),
        )
        return Outcome.Scored(
            fileName = fileName(stem),
            json = AppJson.writer.encodeToString(AttemptScore.serializer(), score),
            assessment = assessment,
            score = score,
        )
    }

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
    private val compact = Json { encodeDefaults = true }
}
