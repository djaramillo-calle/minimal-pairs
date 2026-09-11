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

class DownloadCheckTest {
    private val catalogWords = setOf("ship", "sheep", "bit", "beat", "cat", "cut")
    private val voices = listOf("en-GB-SoniaNeural", "en-GB-RyanNeural")
    private val bundled = ClipIndex(catalogVersion = "2026-09-11.1", voices = voices, words = listOf("ship", "sheep", "bit"), complete = false)
    private val full = ClipIndex(catalogVersion = "2026-09-11.1", voices = voices, words = catalogWords.toList(), complete = true)
    private val placeholder = ClipIndex(catalogVersion = "2026-09-11.1", voices = voices, words = listOf("ship", "cat"), complete = false)
    private val allThere = { _: String, _: String -> true }

    @Test
    fun full_pack_over_bundled_only_is_accepted() {
        assertNull(DownloadCheck.refuse(full, bundled, MergedIndex(bundled, null), "2026-09-11.1", catalogWords, allThere))
        // Re-downloading the same full pack (repair) is fine too: coverage is equal, not smaller.
        assertNull(DownloadCheck.refuse(full, bundled, MergedIndex(bundled, full), "2026-09-11.1", catalogWords, allThere))
    }

    @Test
    fun placeholder_never_replaces_a_full_pack() {
        val reason = DownloadCheck.refuse(placeholder, bundled, MergedIndex(bundled, full), "2026-09-11.1", catalogWords, allThere)
        assertTrue(reason, reason != null && reason.contains("placeholder or partial"))
        // Nor is it accepted when nothing is downloaded yet: complete=false is refused outright.
        assertTrue(DownloadCheck.refuse(placeholder, bundled, MergedIndex(bundled, null), "2026-09-11.1", catalogWords, allThere) != null)
        assertTrue(DownloadCheck.refuse(null, bundled, MergedIndex(bundled, null), "2026-09-11.1", catalogWords, allThere)!!.contains("does not parse"))
    }

    @Test
    fun smaller_coverage_and_missing_files_are_refused() {
        val smaller = ClipIndex(catalogVersion = "2026-09-11.1", voices = voices, words = listOf("ship", "sheep", "bit", "beat"), complete = true)
        val r = DownloadCheck.refuse(smaller, bundled, MergedIndex(bundled, full), "2026-09-11.1", catalogWords, allThere)
        assertTrue(r, r != null && r.contains("fewer than the installed"))
        val missing = DownloadCheck.refuse(full, bundled, MergedIndex(bundled, null), "2026-09-11.1", catalogWords) { v, w -> !(v == "en-GB-RyanNeural" && w == "cut") }
        assertTrue(missing, missing != null && missing.contains("en-GB-RyanNeural/cut.webm"))
    }

    @Test
    fun other_catalog_version_is_fine_only_when_it_covers_this_catalog() {
        val newer = full.copy(catalogVersion = "2026-10-01.1", words = catalogWords.toList() + "extra")
        assertNull(DownloadCheck.refuse(newer, bundled, MergedIndex(bundled, null), "2026-09-11.1", catalogWords, allThere))
        val older = full.copy(catalogVersion = "2026-08-01.1", words = listOf("ship", "sheep", "bit", "beat", "cat"))
        val r = DownloadCheck.refuse(older, bundled, MergedIndex(bundled, null), "2026-09-11.1", catalogWords, allThere)
        assertTrue(r, r != null && r.contains("rendered for catalog 2026-08-01.1"))
    }
}
