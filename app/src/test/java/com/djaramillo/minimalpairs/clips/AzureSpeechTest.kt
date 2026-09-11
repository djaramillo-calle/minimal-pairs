package com.djaramillo.minimalpairs.clips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AzureSpeechTest {
    /** Shape of a `format=detailed` en-US assessment answer (reference "ship"), as Azure returned it live. */
    private val assessment = """
        {"RecognitionStatus":"Success","Offset":5100000,"Duration":6800000,"DisplayText":"Ship.","SNR":38.2,
         "NBest":[{"Confidence":0.95902085,"Lexical":"ship","ITN":"ship","MaskedITN":"ship","Display":"Ship.",
           "AccuracyScore":99.0,"FluencyScore":100.0,"CompletenessScore":100.0,"PronScore":99.4,
           "Words":[{"Word":"ship","Offset":5100000,"Duration":6800000,"Confidence":0.0,"AccuracyScore":99.0,"ErrorType":"None",
             "Syllables":[{"Syllable":"ship","Grapheme":"ship","Offset":5100000,"Duration":6800000,"AccuracyScore":99.0}],
             "Phonemes":[{"Phoneme":"ʃ","Offset":5100000,"Duration":2200000,"AccuracyScore":97.0},
                         {"Phoneme":"ɪ","Offset":7400000,"Duration":1500000,"AccuracyScore":100.0},
                         {"Phoneme":"p","Offset":9000000,"Duration":2900000,"AccuracyScore":100.0}]}]}]}
    """.trimIndent()

    /** en-GB recognition (no `Pronunciation-Assessment` header): no scores, no phonemes. */
    private val recognition = """
        {"RecognitionStatus":"Success","Offset":5100000,"Duration":5600000,"DisplayText":"Ship.",
         "NBest":[{"Confidence":0.7493683,"Lexical":"ship","ITN":"ship","MaskedITN":"ship","Display":"Ship."}]}
    """.trimIndent()

    @Test
    fun parses_word_accuracy_phonemes_and_lexical_text() {
        val r = AzureSpeech.parse(assessment)!!
        assertEquals("Success", r.status)
        assertTrue(r.success)
        assertEquals(99.0, r.acc!!, 0.0)
        assertEquals(listOf("ʃ" to 97.0, "ɪ" to 100.0, "p" to 100.0), r.phonemes)
        assertEquals("ship", r.recognised)
    }

    @Test
    fun recognition_answer_has_text_but_no_scores() {
        val r = AzureSpeech.parse(recognition)!!
        assertTrue(r.success)
        assertNull(r.acc)
        assertTrue(r.phonemes.isEmpty())
        assertEquals("ship", r.recognised)
    }

    @Test
    fun lexical_text_is_normalised_and_display_text_is_the_fallback() {
        val r = AzureSpeech.parse("""{"RecognitionStatus":"Success","DisplayText":"Very, very!","NBest":[{"Lexical":"Very very"}]}""")!!
        assertEquals("very very", r.recognised)
        val d = AzureSpeech.parse("""{"RecognitionStatus":"Success","DisplayText":"Court.","NBest":[{"Confidence":0.5}]}""")!!
        assertEquals("court", d.recognised)
    }

    @Test
    fun no_match_yields_status_only() {
        for (status in listOf("NoMatch", "InitialSilenceTimeout", "BabbleTimeout")) {
            val r = AzureSpeech.parse("""{"RecognitionStatus":"$status","Offset":0,"Duration":0}""")!!
            assertEquals(status, r.status)
            assertFalse(r.success)
            assertNull(r.acc)
            assertNull(r.recognised)
            assertTrue(r.phonemes.isEmpty())
        }
    }

    @Test
    fun empty_nbest_on_success_is_treated_like_no_match() {
        val r = AzureSpeech.parse("""{"RecognitionStatus":"Success","DisplayText":"","NBest":[]}""")!!
        assertNull(r.acc)
        assertNull(r.recognised)
    }

    @Test
    fun garbage_is_null_so_the_caller_retries() {
        assertNull(AzureSpeech.parse(""))
        assertNull(AzureSpeech.parse("<html>Bad gateway</html>"))
        assertNull(AzureSpeech.parse("[1,2,3]"))
        assertNull(AzureSpeech.parse("""{"error":"no status here"}"""))
    }

    @Test
    fun phonemes_without_a_score_or_symbol_are_skipped() {
        val r = AzureSpeech.parse(
            """{"RecognitionStatus":"Success","NBest":[{"Lexical":"go","AccuracyScore":80,
                "Words":[{"Word":"go","AccuracyScore":80,"Phonemes":[{"Phoneme":"g","AccuracyScore":70},{"Phoneme":"oʊ"},{"AccuracyScore":5}]}]}]}""",
        )!!
        assertEquals(listOf("g" to 70.0), r.phonemes)
        assertEquals(80.0, r.acc!!, 0.0)
    }

    @Test
    fun assessment_header_is_base64_of_the_contract_json() {
        val decoded = String(Base64.getDecoder().decode(AzureSpeech.assessmentHeader("ship")), Charsets.UTF_8)
        assertEquals(
            """{"ReferenceText":"ship","GradingSystem":"HundredMark","Granularity":"Phoneme","Dimension":"Comprehensive","EnableMiscue":false,"PhonemeAlphabet":"IPA"}""",
            decoded,
        )
        assertTrue(AzureSpeech.assessmentJson("a\"b").contains("\"ReferenceText\":\"a\\\"b\""))
    }

    @Test
    fun nothing_heard_is_recognised_from_the_three_answers() {
        val ship = AzureSpeech.parse(assessment)!!
        val heard = AzureSpeech.parse(recognition)!!
        val silence = AzureSpeech.Result("InitialSilenceTimeout", null, emptyList(), null)
        // A burst of noise: Azure answers Success for all three but scores 0 and recognises nothing.
        val zero = AzureSpeech.Result("Success", 0.0, listOf("ʃ" to 0.0, "ɪ" to 0.0, "p" to 0.0), null)
        assertTrue(AzureSpeech.PairResult(silence, silence, silence).heardNothing)
        assertTrue(AzureSpeech.PairResult(zero, zero, AzureSpeech.Result("Success", null, emptyList(), null)).heardNothing)
        // A real word is not "nothing heard", nor is one Azure only recognised.
        assertFalse(AzureSpeech.PairResult(ship, ship, heard).heardNothing)
        assertFalse(AzureSpeech.PairResult(zero, zero, heard).heardNothing)
        assertFalse(AzureSpeech.PairResult(silence, ship, silence).heardNothing)
    }

    @Test
    fun endpoints_use_the_region_and_language_with_detailed_format() {
        assertEquals(
            "https://uksouth.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1?language=en-GB&format=detailed",
            AzureSpeech.endpoint("uksouth", AzureSpeech.RECOGNITION_LANGUAGE),
        )
        assertEquals("en-US", AzureSpeech.ASSESSMENT_LANGUAGE)
        assertEquals("audio/wav; codecs=audio/pcm; samplerate=16000", AzureSpeech.CONTENT_TYPE)
    }
}
