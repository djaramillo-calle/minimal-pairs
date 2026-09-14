package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.AttemptScore
import com.djaramillo.minimalpairs.domain.model.SayItWord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64

/**
 * The whole scoring decision, exercised without a socket: the request Azure is
 * asked with, the answer read back, and what may be written to
 * `sayit/scores/`. **No test here calls Azure**; every body is a fixture.
 */
class SayItScoringTest {

    private val sentence =
        "This is not to deny that the unexpected revival of imperialist policies and methods " +
            "takes place under vastly changed conditions."

    private val word = SayItWord(
        id = "imperialist",
        word = "imperialist",
        sentence = sentence,
        clip = "clips/imperialist.ogg",
    )

    private val started = "2026-09-14T07:10:02Z"
    private val stem = SayItNames.stem("20260914T071002Z", word.id)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A `format=detailed` answer shaped exactly as the **REST API for short
     * audio** documents it: the assessment scores are plain fields of
     * `NBest[0]` and `ErrorType` a plain field of each word. This is the shape
     * the app actually meets.
     */
    private fun body(
        status: String = "Success",
        assessment: String? =
            """"AccuracyScore": 71.0, "FluencyScore": 64.0,
               "CompletenessScore": 100.0, "PronScore": 68.0,""",
        words: String = """
            {"Word": "this", "AccuracyScore": 95.0, "ErrorType": "None"},
            {"Word": "imperialist", "AccuracyScore": 40.0, "ErrorType": "Mispronunciation"},
            {"Word": "policies", "AccuracyScore": 0.0, "ErrorType": "Omission"},
            {"Word": "erm", "AccuracyScore": 0.0, "ErrorType": "Insertion"}
        """,
    ): String = """
        {"RecognitionStatus": "$status", "Offset": 300000, "Duration": 42000000, "SNR": 38.7,
         "DisplayText": "${sentence.replace("\"", "")}",
         "NBest": [{"Confidence": 0.93, "Lexical": "lexical text", "ITN": "itn", "MaskedITN": "itn",
           "Display": "display",
           ${assessment ?: ""}
           "Words": [$words]}]}
    """.trimIndent()

    /**
     * The same answer in the **nested** shape the Speech SDK and the newer
     * transcription APIs use. The app reads both, so an endpoint that changes
     * shape does not silently stop scoring for ever.
     */
    private fun nestedBody(): String = """
        {"RecognitionStatus": "Success",
         "NBest": [{"Confidence": 0.93, "Lexical": "lexical text",
           "PronunciationAssessment": {"AccuracyScore": 71.0, "FluencyScore": 64.0,
             "CompletenessScore": 100.0, "PronScore": 68.0},
           "Words": [
             {"Word": "this", "PronunciationAssessment": {"AccuracyScore": 95.0, "ErrorType": "None"}},
             {"Word": "imperialist", "PronunciationAssessment": {"AccuracyScore": 40.0, "ErrorType": "Mispronunciation"}},
             {"Word": "policies", "PronunciationAssessment": {"AccuracyScore": 0.0, "ErrorType": "Omission"}},
             {"Word": "erm", "PronunciationAssessment": {"AccuracyScore": 0.0, "ErrorType": "Insertion"}}]}]}
    """.trimIndent()

    // ---- the request ------------------------------------------------------

    @Test
    fun theRequestIsScriptedAgainstTheSentenceVerbatim() {
        val messy = "  He said \"don't\" — and a backslash \\ too.  "
        val header = SayItScoring.assessmentJson(messy)
        val obj = json.parseToJsonElement(header).jsonObject
        assertEquals(messy, obj["ReferenceText"]!!.jsonPrimitive.content)
        assertEquals("HundredMark", obj["GradingSystem"]!!.jsonPrimitive.content)
        assertEquals("Phoneme", obj["Granularity"]!!.jsonPrimitive.content)
        assertEquals("Comprehensive", obj["Dimension"]!!.jsonPrimitive.content)
        // Miscue detection on: a skipped word must come back as an Omission.
        // The REST parameter table documents this as the string "True".
        assertEquals("True", obj["EnableMiscue"]!!.jsonPrimitive.content)
        // Only the parameters the REST API documents are sent.
        assertEquals(
            setOf("ReferenceText", "GradingSystem", "Granularity", "Dimension", "EnableMiscue"),
            obj.keys,
        )
    }

    @Test
    fun theHeaderIsBase64OfThatJson() {
        val decoded = String(Base64.getDecoder().decode(SayItScoring.assessmentHeader(sentence)), Charsets.UTF_8)
        assertEquals(SayItScoring.assessmentJson(sentence), decoded)
        assertEquals(sentence, json.parseToJsonElement(decoded).jsonObject["ReferenceText"]!!.jsonPrimitive.content)
    }

    @Test
    fun theEndpointIsTheScriptedEnGbOne() {
        val url = SayItScoring.endpoint("uksouth")
        assertTrue(url.startsWith("https://uksouth.stt.speech.microsoft.com/"))
        assertTrue(url.contains("language=en-GB"))
        assertTrue(url.contains("format=detailed"))
        assertEquals("en-GB", AttemptScore.LOCALE_EN_GB)
    }

    // ---- the answer -------------------------------------------------------

    @Test
    fun anAzureResultParsesIntoTheFourNumbersAndTheFlaggedWords() {
        val a = SayItScoring.parse(body())!!
        assertEquals(71.0, a.accuracy!!, 0.001)
        assertEquals(64.0, a.fluency!!, 0.001)
        assertEquals(100.0, a.completeness!!, 0.001)
        assertEquals(68.0, a.pron!!, 0.001)
        // The words of the sentence that went wrong, in Azure's order. "erm" is
        // an Insertion: a word he said that the coach never asked for, so it is
        // not one of the sentence's flagged words.
        assertEquals(listOf("imperialist", "policies"), a.flagged)
    }

    @Test
    fun theNestedSdkShapeIsReadToo() {
        val a = SayItScoring.parse(nestedBody())!!
        assertEquals(71.0, a.accuracy!!, 0.001)
        assertEquals(64.0, a.fluency!!, 0.001)
        assertEquals(100.0, a.completeness!!, 0.001)
        assertEquals(68.0, a.pron!!, 0.001)
        assertEquals(listOf("imperialist", "policies"), a.flagged)
    }

    @Test
    fun aWordFlaggedTwiceIsListedOnce() {
        val a = SayItScoring.parse(
            body(
                words = """
                    {"Word": "imperialist", "ErrorType": "Mispronunciation"},
                    {"Word": "imperialist", "ErrorType": "Mispronunciation"}
                """,
            )
        )!!
        assertEquals(listOf("imperialist"), a.flagged)
    }

    @Test
    fun aCleanReadingFlagsNothing() {
        val a = SayItScoring.parse(body(words = """{"Word": "this", "ErrorType": "None"}"""))!!
        assertTrue(a.flagged.isEmpty())
        assertFalse(a.empty)
    }

    @Test
    fun aStatusOtherThanSuccessIsNoScoreAtAll() {
        for (status in listOf("NoMatch", "InitialSilenceTimeout", "BabbleTimeout", "Error")) {
            val text = body(status = status)
            assertNull(SayItScoring.parse(text))
            assertEquals(SayItScoring.Unscored.NOT_HEARD, SayItScoring.unscoredReason(text))
        }
    }

    @Test
    fun anAnswerWithNoAssessmentBlockIsNoScore() {
        val text = body(assessment = null)
        assertNull(SayItScoring.parse(text))
        assertEquals(SayItScoring.Unscored.NOT_ASSESSED, SayItScoring.unscoredReason(text))
    }

    @Test
    fun anAssessmentWithNoUsableNumberIsNoScore() {
        val text = body(assessment = """"AccuracyScore": "n/a",""")
        assertNull(SayItScoring.parse(text))
        assertEquals(SayItScoring.Unscored.NOT_ASSESSED, SayItScoring.unscoredReason(text))
    }

    @Test
    fun scoresOutsideZeroToAHundredAreDroppedNotClamped() {
        val a = SayItScoring.parse(
            body(
                assessment = """"AccuracyScore": 71.0, "FluencyScore": -3.0,
                   "CompletenessScore": 140.0, "PronScore": 68.0,"""
            )
        )!!
        assertEquals(71.0, a.accuracy!!, 0.001)
        assertNull(a.fluency)
        assertNull(a.completeness)
        assertEquals(68.0, a.pron!!, 0.001)
    }

    @Test
    fun rubbishFromTheNetworkIsNeverAScore() {
        for (text in listOf("", "not json", "[]", "<html>502</html>", "{}")) {
            assertNull(SayItScoring.parse(text))
            assertEquals(SayItScoring.Unscored.NOT_ASSESSED, SayItScoring.unscoredReason(text))
        }
    }

    @Test
    fun scoresAreRoundedToOneDecimal() {
        val a = SayItScoring.parse(
            body(assessment = """"AccuracyScore": 71.2666, "PronScore": 68.05,""")
        )!!
        assertEquals(71.3, a.accuracy!!, 0.0001)
        assertEquals(68.1, a.pron!!, 0.0001)
    }

    // ---- the file ---------------------------------------------------------

    @Test
    fun aScoredAttemptBuildsTheContractsFile() {
        val outcome = SayItScoring.outcome(word, stem, started, body())
        val scored = outcome as SayItScoring.Outcome.Scored
        assertEquals("20260914T071002Z_imperialist.json", scored.fileName)

        val obj = json.parseToJsonElement(scored.json).jsonObject
        assertEquals(
            listOf(
                "version", "id", "word", "sentence", "at", "source", "locale",
                "accuracy", "fluency", "completeness", "pron", "flagged", "attempt_file",
            ),
            obj.keys.toList(),
        )
        assertEquals("1", obj["version"]!!.jsonPrimitive.content)
        assertEquals("imperialist", obj["id"]!!.jsonPrimitive.content)
        assertEquals("imperialist", obj["word"]!!.jsonPrimitive.content)
        assertEquals(sentence, obj["sentence"]!!.jsonPrimitive.content)
        assertEquals("2026-09-14T07:10:02Z", obj["at"]!!.jsonPrimitive.content)
        assertEquals("phone-azure", obj["source"]!!.jsonPrimitive.content)
        assertEquals("en-GB", obj["locale"]!!.jsonPrimitive.content)
        assertEquals("71.0", obj["accuracy"]!!.jsonPrimitive.content)
        assertEquals("64.0", obj["fluency"]!!.jsonPrimitive.content)
        assertEquals("100.0", obj["completeness"]!!.jsonPrimitive.content)
        assertEquals("68.0", obj["pron"]!!.jsonPrimitive.content)
        assertEquals("20260914T071002Z_imperialist.json", obj["attempt_file"]!!.jsonPrimitive.content)

        // It decodes back into the model it was written from.
        val back = json.decodeFromString(AttemptScore.serializer(), scored.json)
        assertEquals(listOf("imperialist", "policies"), back.flagged)
    }

    @Test
    fun theFileNameAndTheAttemptNameAreTheSameStem() {
        // The coach joins the recording, the sidecar and the score by stem, so
        // the three names are built from one string and can never drift.
        val scored = SayItScoring.outcome(word, stem, started, body()) as SayItScoring.Outcome.Scored
        assertEquals(SayItNames.sidecarName("20260914T071002Z", word.id), scored.fileName)
        assertEquals(scored.fileName, scored.score.attemptFile)
        assertEquals(SayItNames.audioName("20260914T071002Z", word.id), scored.fileName.removeSuffix(".json") + ".m4a")
        assertTrue(SayItNames.isAttemptSidecar(scored.fileName))
        assertEquals("20260914T071002Z", SayItNames.timestampOf(scored.fileName))
    }

    @Test
    fun anIdThatIsNotAFileNameKeepsItsSpellingInsideAndIsSafeOutside() {
        val awkward = word.copy(id = "don't", word = "don't", sentence = "I don't know.")
        val safeStem = SayItNames.stem("20260914T071002Z", awkward.id)
        val scored = SayItScoring.outcome(awkward, safeStem, started, body()) as SayItScoring.Outcome.Scored
        assertEquals("20260914T071002Z_don_t.json", scored.fileName)
        // The coach's own id, verbatim, is what the file contains.
        assertEquals("don't", scored.score.id)
        assertEquals("don't", json.parseToJsonElement(scored.json).jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun theSentenceIsCopiedVerbatimIntoTheFileAndTheRequest() {
        val messy = word.copy(sentence = "  He said \"don't\"  —  twice.  ")
        val scored = SayItScoring.outcome(
            messy,
            stem,
            started,
            body(),
        ) as SayItScoring.Outcome.Scored
        assertEquals(messy.sentence, scored.score.sentence)
        assertEquals(
            messy.sentence,
            json.parseToJsonElement(scored.json).jsonObject["sentence"]!!.jsonPrimitive.content,
        )
        val header = String(Base64.getDecoder().decode(SayItScoring.assessmentHeader(messy.sentence)), Charsets.UTF_8)
        assertEquals(
            messy.sentence,
            json.parseToJsonElement(header).jsonObject["ReferenceText"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun aDimensionAzureLeftOutIsWrittenAsNullNotAsZero() {
        val scored = SayItScoring.outcome(
            word,
            stem,
            started,
            body(assessment = """"AccuracyScore": 71.0,"""),
        ) as SayItScoring.Outcome.Scored
        val obj = json.parseToJsonElement(scored.json).jsonObject
        assertTrue(obj.containsKey("fluency"))
        assertEquals("null", obj["fluency"].toString())
        assertEquals("null", obj["pron"].toString())
        assertNull(scored.score.fluency)
    }

    // ---- every path that must write no file at all ------------------------

    @Test
    fun noKeyWritesNoScoreFile() {
        assertNotScored(
            SayItScoring.outcome(word, stem, started, null, SayItScoring.Unscored.NO_KEY),
            SayItScoring.Unscored.NO_KEY,
        )
    }

    @Test
    fun noNetworkWritesNoScoreFile() {
        assertNotScored(
            SayItScoring.outcome(word, stem, started, null, SayItScoring.Unscored.OFFLINE),
            SayItScoring.Unscored.OFFLINE,
        )
    }

    @Test
    fun anAzureErrorWritesNoScoreFile() {
        // A rejected key, a throttled free resource, a 5xx: all the same answer.
        assertNotScored(
            SayItScoring.outcome(word, stem, started, null, SayItScoring.Unscored.AZURE_ERROR),
            SayItScoring.Unscored.AZURE_ERROR,
        )
    }

    @Test
    fun aFailureWinsOverAnyBodyThatCameWithIt() {
        // An error page that happens to parse must never become a score.
        assertNotScored(
            SayItScoring.outcome(word, stem, started, body(), SayItScoring.Unscored.AZURE_ERROR),
            SayItScoring.Unscored.AZURE_ERROR,
        )
    }

    @Test
    fun anUnheardRecordingWritesNoScoreFile() {
        assertNotScored(
            SayItScoring.outcome(word, stem, started, body(status = "NoMatch")),
            SayItScoring.Unscored.NOT_HEARD,
        )
    }

    @Test
    fun anUnreadableAnswerWritesNoScoreFile() {
        assertNotScored(SayItScoring.outcome(word, stem, started, "<html>502</html>"), SayItScoring.Unscored.NOT_ASSESSED)
        assertNotScored(SayItScoring.outcome(word, stem, started, null), SayItScoring.Unscored.AZURE_ERROR)
    }

    @Test
    fun aStemThatDoesNotDescribeThisAttemptScoresNothing() {
        // A score file that named an attempt it is not a score of would pass
        // every other check and quietly attach a number to the wrong recording.
        val otherSecond = SayItNames.stem("20260914T071003Z", word.id)
        assertNotScored(SayItScoring.outcome(word, otherSecond, started, body()), SayItScoring.Unscored.NO_ATTEMPT)
        val otherWord = SayItNames.stem("20260914T071002Z", "different")
        assertNotScored(SayItScoring.outcome(word, otherWord, started, body()), SayItScoring.Unscored.NO_ATTEMPT)
        assertNotScored(SayItScoring.outcome(word, stem, "not a timestamp", body()), SayItScoring.Unscored.NO_ATTEMPT)
    }

    @Test
    fun anAttemptThatNeverReachedTheFolderIsNeverScored() {
        // No recording in the folder means nothing for a score to belong to,
        // even when Azure answered perfectly.
        assertNotScored(SayItScoring.outcome(word, null, started, body()), SayItScoring.Unscored.NO_ATTEMPT)
        assertNotScored(SayItScoring.outcome(word, "", started, body()), SayItScoring.Unscored.NO_ATTEMPT)
    }

    // ---- the app never writes a coach file --------------------------------

    @Test
    fun aScoreFileIsNeverOneOfTheCoachsFiles() {
        val ids = listOf("imperialist", "don't", "results", "words", "sayit", "..", "", "  ", "people's")
        for (id in ids) {
            val at = Instant.parse("2026-09-14T07:10:02Z")
            val s = SayItNames.stem(SayItNames.stampOf(at), id)
            val name = SayItScoring.fileName(s)
            assertFalse(name == "results.json")
            assertFalse(name == "words.json")
            assertFalse(name == "sayit.zip")
            assertFalse(name == "plan.json")
            assertFalse(name == "state.json")
            // Always an attempt stem, so the folder can refuse anything else.
            assertTrue("not an attempt name: $name", SayItNames.isAttemptSidecar(name))
            assertNotNull(SayItNames.timestampOf(name))
        }
    }

    private fun assertNotScored(outcome: SayItScoring.Outcome, reason: SayItScoring.Unscored) {
        val not = outcome as SayItScoring.Outcome.NotScored
        assertEquals(reason, not.reason)
    }
}
