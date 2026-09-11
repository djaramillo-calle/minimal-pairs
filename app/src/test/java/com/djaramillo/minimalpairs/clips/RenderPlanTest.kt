package com.djaramillo.minimalpairs.clips

import com.djaramillo.minimalpairs.domain.model.Catalog
import com.djaramillo.minimalpairs.domain.model.Contrast
import com.djaramillo.minimalpairs.domain.model.Pair
import com.djaramillo.minimalpairs.domain.model.PairWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderPlanTest {
    private fun pw(w: String) = PairWord(word = w, ipa = w, rank = 1, band = "high")
    private fun pair(c: String, a: String, b: String, trainable: Boolean = true) =
        Pair(id = "$c:$a-$b", a = pw(a), b = pw(b), diff = listOf("a", "b"), variant = "a/b", position = "medial", trainable = trainable)
    private fun contrast(id: String, weight: Double, vararg pairs: Pair, trainable: Boolean = true, productionOnly: Boolean = false) =
        Contrast(id = id, label = id, kind = "x", phonemes = listOf("a", "b"), defaultWeight = weight,
            trainable = trainable, productionOnly = productionOnly, description = "", pairs = pairs.toList())

    private val catalog = Catalog(
        version = "t.1", generated = "", sources = emptyMap(), voices = listOf("v1", "v2"),
        contrasts = listOf(
            contrast("low", 0.2, pair("low", "cat", "cut")),
            contrast("high", 1.0, pair("high", "think", "sink"), pair("high", "thin", "tin"), pair("high", "x", "y", trainable = false)),
            contrast("prod", 0.0, trainable = false, productionOnly = true),
        ),
    )

    @Test
    fun renderOrderPutsHeaviestContrastFirstAndEachWordOnce() {
        assertEquals(listOf("think", "sink", "thin", "tin", "cat", "cut"), RenderPlan.renderOrder(catalog))
    }

    @Test
    fun ssmlEscapesAndNamesTheVoice() {
        val s = RenderPlan.ssml("en-GB-SoniaNeural", "rock&roll")
        assertTrue(s.contains("<voice name=\"en-GB-SoniaNeural\">rock&amp;roll</voice>"))
        assertTrue(s.startsWith("<speak version=\"1.0\""))
    }

    @Test
    fun validateClipChecksMagicAndLength() {
        assertEquals("empty response", RenderPlan.validateClip(ByteArray(0)))
        assertEquals("not a WebM file", RenderPlan.validateClip("OggS".toByteArray() + ByteArray(2000)))
        val webm = byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte())
        assertTrue(RenderPlan.validateClip(webm + ByteArray(100))!!.startsWith("too short"))
        assertNull(RenderPlan.validateClip(webm + ByteArray(3000)))
    }

    @Test
    fun buildIndexCountsOnlyWordsPresentInEveryVoice() {
        val present = mapOf("v1/think" to 3000L, "v2/think" to 3100L, "v1/sink" to 3000L, "v2/sink" to 3000L,
            "v1/thin" to 3000L, "v2/thin" to 3000L, "v1/tin" to 3000L, "v2/tin" to 3000L, "v1/cat" to 3000L)
        val idx = RenderPlan.buildIndex(catalog, listOf("v1", "v2")) { v, w -> present["$v/$w"] }
        assertEquals(listOf("sink", "thin", "think", "tin"), idx.words)
        assertEquals(listOf("high"), idx.contrasts)
        assertFalse(idx.complete)
        assertEquals(9, idx.files)
        assertEquals(27100L, idx.bytes)
        assertEquals("t.1", idx.catalogVersion)
        val full = RenderPlan.buildIndex(catalog, listOf("v1", "v2")) { _, _ -> 3000L }
        assertTrue(full.complete)
        assertEquals(listOf("low", "high"), full.contrasts)
    }

    @Test
    fun retryDelayHonoursRetryAfterAndBacksOff() {
        assertEquals(7000L, RenderPlan.retryDelayMs(1, "7"))
        assertEquals(1500L, RenderPlan.retryDelayMs(1, null))
        assertEquals(3000L, RenderPlan.retryDelayMs(2, "garbage"))
        assertEquals(6000L, RenderPlan.retryDelayMs(3, null))
    }

    @Test
    fun regionAndKeyShapes() {
        assertEquals("uksouth", RenderPlan.normalizeRegion("  UKSouth "))
        assertNull(RenderPlan.normalizeRegion("uk south"))
        assertNull(RenderPlan.normalizeRegion("https://uksouth.api"))
        assertEquals("abcdefghijklmnopqrstuvwxyz0123456789", RenderPlan.normalizeKey(" abcdefghijklmnopqrstuvwxyz0123456789 "))
        assertNull(RenderPlan.normalizeKey("short"))
        assertNull(RenderPlan.normalizeKey("has space abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun azureVoiceListingParsesBritishNames() {
        val listing = """[{"ShortName":"en-GB-SoniaNeural","Locale":"en-GB"},{"ShortName":"en-US-AriaNeural","Locale":"en-US"},{"ShortName":"en-GB-RyanNeural","Locale":"en-GB"}]"""
        assertEquals(listOf("en-GB-SoniaNeural", "en-GB-RyanNeural"), AzureTts.parseVoices(listing))
        assertEquals(emptyList<String>(), AzureTts.parseVoices("nope"))
    }
}
