package com.djaramillo.minimalpairs.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpaTest {
    @Test
    fun tokenisesMultiCharUnitsAndStress() {
        assertEquals(listOf("ʃ", "iː", "p"), Ipa.phonemes("ʃˈiːp"))
        assertEquals(listOf("tʃ", "ɜː", "tʃ"), Ipa.phonemes("tʃˈɜːtʃ"))
        assertEquals(listOf("dʒ", "ʌ", "dʒ"), Ipa.phonemes("dʒˈʌdʒ"))
        assertEquals(listOf("ɹ", "eɪ", "n", "b", "əʊ"), Ipa.phonemes("ɹˈeɪnbˌəʊ"))
        assertEquals(listOf("k", "j", "ʊə"), Ipa.phonemes("kjˈʊə"))
        assertEquals(listOf("aʊ", "ə"), Ipa.phonemes("ˈaʊə"))
        assertEquals(listOf("b", "ɪə"), Ipa.phonemes("bˈɪə"))
        assertEquals(listOf("b", "ɛə"), Ipa.phonemes("bˈɛə"))
        assertEquals(listOf("θ", "ɔː"), Ipa.phonemes("θˈɔː"))
        assertEquals(emptyList<String>(), Ipa.phonemes(""))

        val t = Ipa.tokens("ʃˈiːp")
        assertEquals(Ipa.Token("ʃ", 0, 1, null), t[0])
        assertEquals(Ipa.Token("iː", 2, 4, 1), t[1])
        assertEquals(1..3, t[1].fullRange)
        assertEquals(2..3, t[1].range)
        assertEquals(Ipa.Token("p", 4, 5, null), t[2])
    }

    @Test
    fun highlightSubstitution() {
        val h = Ipa.highlight("ʃˈɪp", "ʃˈiːp", listOf("ɪ", "iː"))
        assertEquals(2..2, h.a.range)
        assertEquals(2..3, h.b.range)
        assertEquals("ʃˈ", h.a.before); assertEquals("ɪ", h.a.highlighted); assertEquals("p", h.a.after)
        assertEquals("ʃˈ", h.b.before); assertEquals("iː", h.b.highlighted); assertEquals("p", h.b.after)

        val c = Ipa.highlight("tʃˈɪp", "ʃˈɪp", listOf("tʃ", "ʃ"))
        assertEquals(0..1, c.a.range)
        assertEquals(0..0, c.b.range)

        val f = Ipa.highlight("mˈaʊθ", "mˈaʊs", listOf("θ", "s"))
        assertEquals(4..4, f.a.range)
        assertEquals(4..4, f.b.range)
    }

    @Test
    fun highlightInsertion() {
        val h = Ipa.highlight("ˈaʊə", "hˈaʊə", listOf("", "h"))
        assertNull(h.a.range)
        assertEquals(0..0, h.b.range)
        assertEquals("ˈaʊə", h.a.before); assertEquals("", h.a.highlighted)

        val ed = Ipa.highlight("wˈɔːk", "wˈɔːkt", listOf("", "t"))
        assertNull(ed.a.range)
        assertEquals(5..5, ed.b.range)
        assertEquals("t", ed.b.highlighted)
    }

    @Test
    fun highlightFallsBackToFirstOccurrence() {
        // inconsistent lengths: fall back to searching the diff phoneme
        val h = Ipa.highlight("θˈɪŋk", "sˈɪŋkɪŋ", listOf("θ", "s"))
        assertEquals(0..0, h.a.range)
        assertEquals(0..0, h.b.range)
        val none = Ipa.highlight("θˈɪŋk", "sˈɪŋk", listOf("x", "y"))
        assertNull(none.a.range)
        assertNull(none.b.range)
    }
}
