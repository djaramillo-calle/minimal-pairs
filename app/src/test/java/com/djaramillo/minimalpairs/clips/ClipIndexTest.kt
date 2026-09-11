package com.djaramillo.minimalpairs.clips

import com.djaramillo.minimalpairs.domain.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipIndexTest {
    private val bundled = ClipIndex(
        catalogVersion = "2026-09-11.1", voices = listOf("en-GB-SoniaNeural", "en-GB-RyanNeural"),
        words = listOf("ship", "sheep", "bit"), complete = false, files = 6,
    )
    private val downloaded = ClipIndex(
        catalogVersion = "2026-09-11.1",
        voices = listOf("en-GB-SoniaNeural", "en-GB-RyanNeural", "en-GB-LibbyNeural"),
        words = listOf("ship", "sheep", "beat", "cat", "cut"), complete = true, files = 15,
    )

    @Test
    fun parses_the_design_doc_example() {
        val text = """{"version": 1, "catalog_version": "2026-09-11.1", "format": "webm/opus 24 kHz 24 kbps mono",
            "voices": ["en-GB-SoniaNeural"], "words": ["ship", "sheep"], "complete": true,
            "contrasts": ["i/ii"], "files": 2, "bytes": 8000, "extra": 1}"""
        val idx = AppJson.json.decodeFromString(ClipIndex.serializer(), text)
        assertEquals(listOf("ship", "sheep"), idx.words)
        assertTrue(idx.complete)
        assertTrue(idx.has("ship", "en-GB-SoniaNeural"))
        assertFalse(idx.has("ship", "en-GB-RyanNeural"))
    }

    @Test
    fun empty_when_no_source() {
        val m = MergedIndex(null, null)
        assertTrue(m.isEmpty)
        assertTrue(m.words.isEmpty())
        assertTrue(m.voices.isEmpty())
        assertFalse(m.declaredComplete)
        assertNull(m.sourceFor("ship", "en-GB-SoniaNeural"))
    }

    @Test
    fun bundled_only() {
        val m = MergedIndex(bundled, null)
        assertEquals(setOf("ship", "sheep", "bit"), m.words)
        assertEquals(ClipSourceKind.BUNDLED, m.sourceFor("bit", "en-GB-RyanNeural"))
        assertFalse(m.declaredComplete)
    }

    @Test
    fun merged_words_need_every_merged_voice() {
        val m = MergedIndex(bundled, downloaded)
        assertEquals(listOf("en-GB-SoniaNeural", "en-GB-RyanNeural", "en-GB-LibbyNeural"), m.voices)
        // "bit" is only bundled and the bundle lacks Libby: not available in every voice.
        assertEquals(setOf("ship", "sheep", "beat", "cat", "cut"), m.words)
        assertTrue(m.declaredComplete)
        assertEquals(ClipSourceKind.BUNDLED, m.sourceFor("ship", "en-GB-SoniaNeural"))
        assertEquals(ClipSourceKind.DOWNLOADED, m.sourceFor("ship", "en-GB-LibbyNeural"))
        assertEquals(ClipSourceKind.DOWNLOADED, m.sourceFor("cat", "en-GB-RyanNeural"))
        assertNull(m.sourceFor("bit", "en-GB-LibbyNeural"))
        assertEquals(setOf("bit", "dog"), m.missingWords(setOf("ship", "bit", "dog")))
    }
}
