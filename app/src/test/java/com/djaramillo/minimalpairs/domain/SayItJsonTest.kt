package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.AttemptSidecar
import com.djaramillo.minimalpairs.domain.model.SayItResults
import com.djaramillo.minimalpairs.domain.model.SayItWord
import com.djaramillo.minimalpairs.domain.model.SayItWords
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Say-it models against the example bytes printed in docs/CONTRACT.md. The
 * examples are the contract's own words, so they are pasted here verbatim: a
 * model that stops decoding them, or a sidecar that stops writing a documented
 * key, is a contract break and not merely a failing test.
 */
class SayItJsonTest {
    private val json = AppJson.json

    private fun assertKeys(text: String, vararg keys: String) {
        for (k in keys) assertTrue("missing \"$k\" in:\n$text", text.contains("\"$k\""))
    }

    // ---- words.json ------------------------------------------------------

    private val sentenceDoc =
        "This is not to deny that the unexpected revival of imperialist policies and " +
            "methods takes place under vastly changed conditions."

    private val wordsDoc = """
        {"version": 1, "written": "2026-09-13T18:06:00Z", "per_session": 5,
         "words": [{"id": "imperialist", "word": "imperialist",
                    "sentence": "$sentenceDoc", "clip": "clips/imperialist.ogg", "ipa": "",
                    "classes": ["i/ii", "s/z", "schwa"], "flagged_on": 3, "read_on": 2,
                    "miss_rate": 1.5, "added": "2026-09-13"}]}
    """.trimIndent()

    @Test
    fun wordsDecodeTheDocumentedExample() {
        val words = json.decodeFromString<SayItWords>(wordsDoc)
        assertEquals(1, words.version)
        assertEquals("2026-09-13T18:06:00Z", words.written)
        assertEquals(5, words.perSession)
        assertEquals(1, words.words.size)
        val w = words.words.first()
        assertEquals("imperialist", w.id)
        assertEquals("imperialist", w.word)
        assertEquals(sentenceDoc, w.sentence)
        assertEquals("clips/imperialist.ogg", w.clip)
        // The coach may write an empty ipa; the screen simply omits the line.
        assertEquals("", w.ipa)
        assertEquals(listOf("i/ii", "s/z", "schwa"), w.classes)
        assertEquals(3, w.flaggedOn)
        assertEquals(2, w.readOn)
        assertEquals(1.5, w.missRate!!, 1e-9)
        assertEquals("2026-09-13", w.added)
        // The example is exactly what the planner is meant to accept.
        assertEquals(listOf("imperialist"), SayItPlanner.select(words, null).map { it.id })
        assertEquals("imperialist.ogg", SayItZip.clipName(w.clip!!))
    }

    @Test
    fun wordsRoundTripThroughTheSerialNames() {
        val words = json.decodeFromString<SayItWords>(wordsDoc)
        val text = json.encodeToString(words)
        assertKeys(text, "version", "written", "per_session", "words", "id", "word", "sentence", "clip", "ipa",
            "classes", "flagged_on", "read_on", "miss_rate", "added")
        assertEquals(words, json.decodeFromString<SayItWords>(text))
    }

    @Test
    fun absentKeysReadAsTheDocumentedDefaults() {
        val words = json.decodeFromString<SayItWords>("""{"version": 1, "words": [{"id": "x", "word": "x", "sentence": "A x."}]}""")
        assertNull(words.written)
        assertEquals(5, words.perSession)
        val w = words.words.first()
        assertNull(w.clip)
        assertNull(w.ipa)
        assertEquals(emptyList<String>(), w.classes)
        assertEquals(0, w.flaggedOn)
        assertEquals(0, w.readOn)
        assertNull(w.missRate)
        assertNull(w.added)
    }

    @Test
    fun anEmptyOrUnknownFileIsNotAnException() {
        assertEquals(SayItWords(), json.decodeFromString<SayItWords>("{}"))
        // Unknown keys are ignored, so a newer coach does not break an older app.
        val words = json.decodeFromString<SayItWords>("""{"version": 1, "future": true, "words": [{"id": "x", "word": "x", "sentence": "A x.", "mood": "bleak"}]}""")
        assertEquals(listOf("x"), words.words.map { it.id })
    }

    // ---- results.json ----------------------------------------------------

    private val resultsDoc = """
        {"version": 1, "updated": "2026-09-13T19:12:00Z", "words": {"imperialist": {
           "attempts": [{"at": "2026-09-13T18:04:02Z", "file": "20260913T180402Z_imperialist.json",
                         "accuracy": 71.0, "fluency": 64.0, "pron": 68.0, "flagged": ["imperialist"]}],
           "best": 71.0, "last": 71.0, "status": "active"}}}
    """.trimIndent()

    @Test
    fun resultsDecodeTheDocumentedExample() {
        val results = json.decodeFromString<SayItResults>(resultsDoc)
        assertEquals(1, results.version)
        assertEquals("2026-09-13T19:12:00Z", results.updated)
        val r = results.words.getValue("imperialist")
        assertEquals(1, r.attempts.size)
        val a = r.attempts.first()
        assertEquals("2026-09-13T18:04:02Z", a.at)
        assertEquals("20260913T180402Z_imperialist.json", a.file)
        assertEquals(71.0, a.accuracy!!, 1e-9)
        assertEquals(64.0, a.fluency!!, 1e-9)
        assertEquals(68.0, a.pron!!, 1e-9)
        assertEquals(listOf("imperialist"), a.flagged)
        assertEquals(71.0, r.best!!, 1e-9)
        assertEquals(71.0, r.last!!, 1e-9)
        assertEquals("active", r.status)
        assertEquals(SayItPlanner.Status.ACTIVE, SayItPlanner.statusOf("imperialist", results))
        assertEquals(71.0, SayItPlanner.lastScore("imperialist", results)!!, 1e-9)
        // 'file' is the sidecar name, which is what the retention rule joins on,
        // and it is exactly the name the app would have written for that attempt.
        assertEquals(
            mapOf("imperialist" to setOf("20260913T180402Z_imperialist.json")),
            SayItCleanup.scoredFiles(results),
        )
        assertEquals("20260913T180402Z_imperialist.json", SayItNames.sidecarName("20260913T180402Z", "imperialist"))
        assertEquals("2026-09-13T18:04:02Z", SayItNames.isoOf("20260913T180402Z"))
    }

    @Test
    fun resultsRoundTripThroughTheSerialNames() {
        val results = json.decodeFromString<SayItResults>(resultsDoc)
        val text = json.encodeToString(results)
        assertKeys(text, "version", "updated", "words", "attempts", "at", "file", "accuracy", "fluency", "pron",
            "flagged", "best", "last", "status")
        assertEquals(results, json.decodeFromString<SayItResults>(text))
    }

    @Test
    fun aWordWithNoScoresYetStillDecodes() {
        val results = json.decodeFromString<SayItResults>("""{"version": 1, "words": {"x": {}}}""")
        val r = results.words.getValue("x")
        assertEquals(emptyList<Any>(), r.attempts)
        assertNull(r.best)
        assertNull(r.last)
        assertEquals("active", r.status)
        assertEquals(SayItResults(), json.decodeFromString<SayItResults>("{}"))
    }

    // ---- the attempt sidecar --------------------------------------------

    private val sidecarDoc = """
        {"version": 1, "id": "imperialist", "word": "imperialist",
         "sentence": "$sentenceDoc",
         "started": "2026-09-13T18:04:02Z", "duration_s": 4.2,
         "app_version": "0.2.0", "clip_played": 2}
    """.trimIndent()

    @Test
    fun sidecarDecodesTheDocumentedExample() {
        val s = json.decodeFromString<AttemptSidecar>(sidecarDoc)
        assertEquals(1, s.version)
        assertEquals("imperialist", s.id)
        assertEquals("imperialist", s.word)
        assertEquals(sentenceDoc, s.sentence)
        assertEquals("2026-09-13T18:04:02Z", s.started)
        assertEquals(4.2, s.durationS, 1e-9)
        assertEquals("0.2.0", s.appVersion)
        assertEquals(2, s.clipPlayed)
        // `started` is exactly the stem of both file names.
        assertEquals("20260913T180402Z", SayItNames.stampOf(TimeUtil.parseIso(s.started)!!))
        assertEquals("20260913T180402Z_imperialist.json", SayItNames.sidecarName("20260913T180402Z", s.id))
    }

    @Test
    fun sidecarEncodesEveryDocumentedKeyAndNoOther() {
        val s = json.decodeFromString<AttemptSidecar>(sidecarDoc)
        val text = json.encodeToString(s)
        val keys = json.parseToJsonElement(text).jsonObject.keys
        assertEquals(
            setOf("version", "id", "word", "sentence", "started", "duration_s", "app_version", "clip_played"),
            keys,
        )
        assertKeys(text, "duration_s", "app_version", "clip_played")
        assertEquals(s, json.decodeFromString<AttemptSidecar>(text))
        // The writer keeps every key too; it is what validate-contract.py checks.
        val written = AppJson.writer.encodeToString(s)
        assertEquals(keys, AppJson.writer.parseToJsonElement(written).jsonObject.keys)
    }

    @Test
    fun theSidecarCarriesTheSentenceVerbatim() {
        val sentence = "  He was the agent  of imperialist expansion overseas.  "
        val word = SayItWord(id = "imperialist", word = "imperialist", sentence = sentence)
        val s = AttemptSidecar(
            id = word.id,
            word = word.word,
            sentence = word.sentence,
            started = "2026-09-13T18:04:02Z",
            durationS = 4.2,
            appVersion = "0.2.0",
            clipPlayed = 2,
        )
        // The cloud scores the audio against this string, so it is never trimmed or re-wrapped.
        assertEquals(sentence, json.decodeFromString<AttemptSidecar>(json.encodeToString(s)).sentence)
    }
}
