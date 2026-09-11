package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.Catalog
import com.djaramillo.minimalpairs.domain.model.Contrast
import com.djaramillo.minimalpairs.domain.model.Pair
import com.djaramillo.minimalpairs.domain.model.PairWord
import com.djaramillo.minimalpairs.domain.model.WordResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductionScorerTest {
    private fun w(word: String, ipa: String, rank: Int = 500) = PairWord(word, ipa, rank, "high")
    private fun pair(c: String, a: PairWord, b: PairWord, diff: List<String>, position: String) =
        Pair("$c:${a.word}-${b.word}", a, b, diff, diff.joinToString("/"), position)

    /** Real catalog entries the measurements were taken on, plus the homophone court/caught. */
    private val catalog = Catalog(
        version = "t",
        contrasts = listOf(
            Contrast(id = "th", phonemes = listOf("θ", "s"), defaultWeight = 1.0, pairs = listOf(
                pair("th", w("think", "θˈɪŋk"), w("sink", "sˈɪŋk"), listOf("θ", "s"), "initial"),
            )),
            Contrast(id = "cat/cut", phonemes = listOf("æ", "ʌ"), defaultWeight = 0.4, pairs = listOf(
                pair("cat/cut", w("cat", "kˈæt"), w("cut", "kˈʌt"), listOf("æ", "ʌ"), "medial"),
            )),
            Contrast(id = "er/or", phonemes = listOf("ɜː", "ɔː"), defaultWeight = 0.2, pairs = listOf(
                pair("er/or", w("curt", "kˈɜːt"), w("court", "kˈɔːt"), listOf("ɜː", "ɔː"), "medial"),
                pair("er/or", w("curt", "kˈɜːt"), w("caught", "kˈɔːt"), listOf("ɜː", "ɔː"), "medial"),
                pair("er/or", w("sir", "sˈɜː"), w("saw", "sˈɔː"), listOf("ɜː", "ɔː"), "final"),
            )),
            Contrast(id = "long-back", phonemes = listOf("ɒ", "ɔː"), defaultWeight = 0.3, pairs = listOf(
                pair("long-back", w("cot", "kˈɒt"), w("caught", "kˈɔːt"), listOf("ɒ", "ɔː"), "medial"),
            )),
            Contrast(id = "b/v", phonemes = listOf("b", "v"), defaultWeight = 0.5, pairs = listOf(
                pair("b/v", w("berry", "bˈɛɹi"), w("very", "vˈɛɹi"), listOf("b", "v"), "initial"),
            )),
            Contrast(id = "i/ii", phonemes = listOf("ɪ", "iː"), defaultWeight = 0.6, pairs = listOf(
                pair("i/ii", w("ship", "ʃˈɪp"), w("sheep", "ʃˈiːp"), listOf("ɪ", "iː"), "medial"),
            )),
            Contrast(id = "-ed", phonemes = listOf("", "t"), defaultWeight = 0.3, pairs = listOf(
                pair("-ed", w("walk", "wˈɔːk"), w("walked", "wˈɔːkt"), listOf("", "t"), "final"),
            )),
            Contrast(id = "h", phonemes = listOf("h", ""), defaultWeight = 0.2, pairs = listOf(
                pair("h", w("heat", "hˈiːt"), w("eat", "ˈiːt"), listOf("h", ""), "initial"),
            )),
        ),
    )

    private fun input(acc: Double, accOther: Double, ph: Double?, phOther: Double?, recognised: String?, ms: Int = 900, attempts: Int = 1) =
        ProductionScorer.WordInput(acc, accOther, ph, phOther, recognised, ms, attempts)

    private fun score(input: ProductionScorer.WordInput, intended: String, other: String, contrast: String, threshold: Int = 60): WordResult =
        ProductionScorer.score(input, intended, other, contrast, catalog, threshold)

    private fun pairOf(contrast: String, word: String): Pair =
        catalog.contrast(contrast)!!.pairs.first { it.a.word == word || it.b.word == word }

    private fun scoreWithPair(
        input: ProductionScorer.WordInput, intended: String, other: String, contrast: String, threshold: Int = 60,
    ): WordResult = ProductionScorer.score(input, intended, other, contrast, catalog, threshold, pairOf(contrast, intended))

    @Test
    fun thinkSaidAsThinkIsHeardAsThink() {
        // measured today on a synthesized "think": acc 100 / 81, differing phoneme 100 / 10, recognised "Think."
        val r = score(input(100.0, 81.0, 100.0, 10.0, "Think."), "think", "sink", "th")
        assertEquals("think", r.heard)
        assertEquals("phoneme:intended word:intended recognition:intended", r.votes)
        assertEquals(100, r.acc); assertEquals(81, r.accOther)
        assertEquals(100, r.ph); assertEquals(10, r.phOther)
        assertEquals("think", r.recognised)
        assertEquals(900, r.ms); assertEquals(1, r.attempts)
        assertEquals(1, ProductionScorer.points(r, "think", 60))
    }

    @Test
    fun cutSaidAsCatIsHeardAsCat() {
        // measured today: intended "cut", acc 84 / 96, phoneme 74 / 100, recognised "cat"
        val r = score(input(84.0, 96.0, 74.0, 100.0, "cat"), "cut", "cat", "cat/cut")
        assertEquals("cat", r.heard)
        assertEquals("phoneme:other word:other recognition:other", r.votes)
        assertEquals(0, ProductionScorer.points(r, "cut", 60))
        // the accuracy alone would have passed the threshold: heard decides first
        assertEquals(84, r.acc)
    }

    @Test
    fun recognisedHomophoneCountsAsTheWord() {
        // "court" recognised as "caught": same IPA in the catalog → votes for the intended word
        val r = score(input(90.0, 85.0, 88.0, 80.0, "Caught"), "court", "curt", "er/or")
        assertEquals("phoneme:none word:none recognition:intended", r.votes)
        assertEquals("court", r.heard)
        assertEquals(1, ProductionScorer.points(r, "court", 60))
        // and the other way round: intended curt, heard caught → the other word
        val o = score(input(85.0, 90.0, 80.0, 88.0, "caught"), "curt", "court", "er/or")
        assertEquals("phoneme:none word:none recognition:other", o.votes)
        assertEquals("court", o.heard)
        // saw / sore: not in this catalog → spelling only
        val unknown = score(input(85.0, 84.0, 80.0, 79.0, "sore"), "saw", "sir", "er/or")
        assertEquals("phoneme:none word:none recognition:none", unknown.votes)
        assertEquals("?", unknown.heard)
        assertEquals(true, ProductionScorer.matches("caught", "court", catalog))
        assertEquals(true, ProductionScorer.matches("court", "court", catalog))
        assertEquals(false, ProductionScorer.matches("curt", "court", catalog))
    }

    @Test
    fun longBackSkipsThePhonemeVote() {
        // the phoneme numbers would have voted "other"; the contrast skips the signal and the other two decide
        val r = score(input(95.0, 70.0, 20.0, 99.0, "cot"), "cot", "caught", "long-back")
        assertEquals("phoneme:none word:intended recognition:intended", r.votes)
        assertNull(r.ph); assertNull(r.phOther)
        assertEquals("cot", r.heard)
        val s = score(input(95.0, 70.0, 20.0, 99.0, "affect"), "affect", "effect", "schwa")
        assertEquals("phoneme:none word:intended recognition:intended", s.votes)
        assertNull(s.ph)
    }

    @Test
    fun tieOrNoVoteGivesQuestionMarkAndNoPoint() {
        // phoneme says intended, word says other, recognition matches neither → 1:1
        val tie = score(input(80.0, 95.0, 90.0, 60.0, "thing"), "think", "sink", "th")
        assertEquals("phoneme:intended word:other recognition:none", tie.votes)
        assertEquals("?", tie.heard)
        assertEquals(0, ProductionScorer.points(tie, "think", 0))
        // nothing votes: margins below 15 / 10, no recognition
        val none = score(input(80.0, 75.0, 90.0, 80.0, null), "think", "sink", "th")
        assertEquals("phoneme:none word:none recognition:none", none.votes)
        assertEquals("?", none.heard)
        assertNull(none.recognised)
        // no phonemes returned → the phoneme signal does not vote
        val noPh = score(input(95.0, 70.0, null, null, "sink"), "think", "sink", "th")
        assertEquals("phoneme:none word:intended recognition:other", noPh.votes)
        assertEquals("?", noPh.heard)
        // exact margins vote
        val edge = score(input(80.0, 70.0, 75.0, 60.0, ""), "think", "sink", "th")
        assertEquals("phoneme:intended word:intended recognition:none", edge.votes)
        assertEquals("think", edge.heard)
        val under = score(input(80.0, 70.5, 75.0, 60.5, "  "), "think", "sink", "th")
        assertEquals("phoneme:none word:none recognition:none", under.votes)
    }

    @Test
    fun thresholdGatesThePoint() {
        val r = score(input(55.0, 20.0, 90.0, 10.0, "think"), "think", "sink", "th", threshold = 60)
        assertEquals("think", r.heard)
        assertEquals(0, ProductionScorer.points(r, "think", 60))
        assertEquals(1, ProductionScorer.points(r, "think", 55))
        assertEquals(1, ProductionScorer.points(r, "think", 0))
        assertEquals(2, ProductionScorer.pairPoints(mapOf("think" to r, "sink" to r.copy(heard = "sink", acc = 99)), 55))
        assertEquals(1, ProductionScorer.pairPoints(mapOf("think" to r, "sink" to r.copy(heard = "sink", acc = 99)), 60))
        assertEquals(0, ProductionScorer.pairPoints(mapOf("think" to r, "sink" to r.copy(heard = "?", acc = 99)), 60))
    }

    @Test
    fun normalisesRecognitionAndClampsScores() {
        assertEquals("think", ProductionScorer.normaliseRecognised("Think."))
        assertEquals("i think", ProductionScorer.normaliseRecognised("  I think!  "))
        assertNull(ProductionScorer.normaliseRecognised("..."))
        assertNull(ProductionScorer.normaliseRecognised(null))
        val r = score(input(120.0, -3.0, Double.NaN, 101.0, "THINK"), "think", "sink", "th")
        assertEquals(100, r.acc); assertEquals(0, r.accOther)
        assertEquals(0, r.ph); assertEquals(100, r.phOther)
        assertEquals("think", r.recognised)
    }

    @Test
    fun phonemeOfInterestUsesTheContractMapping() {
        val th = catalog.contrast("th")!!.pairs[0]
        assertEquals(ProductionScorer.PhonemeTarget("θ", 0, 4), ProductionScorer.phonemeOfInterest(th, "think"))
        assertEquals(ProductionScorer.PhonemeTarget("s", 0, 4), ProductionScorer.phonemeOfInterest(th, "sink"))
        val sheep = catalog.contrast("i/ii")!!.pairs[0]
        assertEquals(ProductionScorer.PhonemeTarget("i", 1, 3), ProductionScorer.phonemeOfInterest(sheep, "sheep"))
        assertEquals(ProductionScorer.PhonemeTarget("ɪ", 1, 3), ProductionScorer.phonemeOfInterest(sheep, "ship"))
        val sir = catalog.contrast("er/or")!!.pairs[2]
        assertEquals(ProductionScorer.PhonemeTarget("ɝ", 1, 2), ProductionScorer.phonemeOfInterest(sir, "sir"))
        assertEquals(ProductionScorer.PhonemeTarget("ɔ", 1, 2), ProductionScorer.phonemeOfInterest(sir, "saw"))
        val cot = catalog.contrast("long-back")!!.pairs[0]
        assertEquals(ProductionScorer.PhonemeTarget("ɑ", 1, 3), ProductionScorer.phonemeOfInterest(cot, "cot"))
        val very = catalog.contrast("b/v")!!.pairs[0]
        assertEquals(ProductionScorer.PhonemeTarget("v", 0, 4), ProductionScorer.phonemeOfInterest(very, "very"))
        val ed = catalog.contrast("-ed")!!.pairs[0]
        assertNull(ProductionScorer.phonemeOfInterest(ed, "walk"))                         // absent phoneme
        assertEquals(ProductionScorer.PhonemeTarget("t", 3, 4), ProductionScorer.phonemeOfInterest(ed, "walked"))
        assertNull(ProductionScorer.phonemeOfInterest(th, "thing"))                        // not in the pair
        assertEquals("ɑ", ProductionScorer.azureSymbol("ɑː"))
        assertEquals("tʃ", ProductionScorer.azureSymbol("tʃ"))
    }

    @Test
    fun pickPhonemeScorePrefersSymbolThenTolerantPosition() {
        // Azure merged ɛɹ in "very": 3 entries against Britfone's 4 — the symbol still finds v
        val very = listOf("v" to 88.0, "ɛɹ" to 95.0, "i" to 99.0)
        assertEquals(88.0, ProductionScorer.pickPhonemeScore(very, "v", 0, 4))
        // the symbol is absent and the lists do not align → null, never a wrong sound
        assertNull(ProductionScorer.pickPhonemeScore(very, "b", 0, 4))
        // same length: fall back to the position
        val aligned = listOf("b" to 40.0, "ɛ" to 90.0, "ɹ" to 91.0, "i" to 92.0)
        assertEquals(40.0, ProductionScorer.pickPhonemeScore(aligned, "v", 0, 4))
        assertEquals(40.0, ProductionScorer.pickPhonemeScore(aligned, "v", 0))             // no count: position
        assertNull(ProductionScorer.pickPhonemeScore(aligned, "v", 9))
        assertNull(ProductionScorer.pickPhonemeScore(emptyList(), "v", 0, 4))
        // a repeated symbol: the occurrence nearest the position
        val sense = listOf("s" to 10.0, "ɛ" to 90.0, "n" to 90.0, "s" to 70.0)
        assertEquals(10.0, ProductionScorer.pickPhonemeScore(sense, "s", 0, 4))
        assertEquals(70.0, ProductionScorer.pickPhonemeScore(sense, "s", 3, 4))
        // Azure's ɡ (U+0261) matches the catalog's g
        assertEquals(66.0, ProductionScorer.pickPhonemeScore(listOf("ɡ" to 66.0, "oʊ" to 90.0), "g", 0, 2))
        val target = ProductionScorer.PhonemeTarget("v", 0, 4)
        assertEquals(88.0, ProductionScorer.pickPhonemeScore(very, target))
    }

    @Test
    fun everyRealCatalogPairResolvesItsPhoneme() {
        // The shipped catalog, when the test runs inside the repository (Gradle's working dir is app/).
        val file = listOf("../data/catalog/catalog.json", "data/catalog/catalog.json").map { java.io.File(it) }.firstOrNull { it.exists() } ?: return
        val real = AppJson.json.decodeFromString(Catalog.serializer(), file.readText())
        var checked = 0
        for (c in real.trainableContrasts) for (p in c.trainablePairs) for ((k, side) in listOf(0 to p.a, 1 to p.b)) {
            val diff = p.diff.getOrNull(k) ?: ""
            val t = ProductionScorer.phonemeOfInterest(p, side.word)
            if (diff.isEmpty()) {
                assertNull("${p.id} ${side.word}", t)
            } else {
                assertEquals("${p.id} ${side.word}", ProductionScorer.azureSymbol(diff), t!!.symbol)
                assertEquals("${p.id} ${side.word}", diff, Ipa.phonemes(side.ipa)[t.position])
                assertEquals(Ipa.phonemes(side.ipa).size, t.phonemeCount)
                checked++
            }
        }
        assertEquals(true, checked > 1000)
        // the homophone index covers the catalog: court and caught share their IPA
        assertEquals(true, ProductionScorer.matches("caught", "court", real))
        assertEquals(false, ProductionScorer.matches("cot", "caught", real))
    }

    // ---- insertion contrasts: the differing phoneme exists on one side only -----------------

    @Test
    fun insertionPairsScoreTheOneSidedPhoneme() {
        // walked: the /t/ lives in "walked" only, so it is scored under that reference (ph) and
        // votes for "walked" from 60 up, for "walk" below 40, and not at all in between.
        val walked = pairOf("-ed", "walked")
        assertEquals(ProductionScorer.INTENDED, ProductionScorer.phonemeCarrier(walked, "walked", "walk"))
        assertEquals(ProductionScorer.OTHER, ProductionScorer.phonemeCarrier(walked, "walk", "walked"))
        assertNull(ProductionScorer.phonemeCarrier(pairOf("th", "think"), "think", "sink"))

        val said = scoreWithPair(input(96.0, 70.0, 99.0, null, "walked."), "walked", "walk", "-ed")
        assertEquals("walked", said.heard)
        assertEquals("phoneme:intended word:intended recognition:intended", said.votes)
        assertEquals(99, said.ph); assertNull(said.phOther)

        val dropped = scoreWithPair(input(70.0, 95.0, 12.0, null, "walk."), "walked", "walk", "-ed")
        assertEquals("walk", dropped.heard)
        assertEquals("phoneme:other word:other recognition:other", dropped.votes)
        assertEquals(12, dropped.ph); assertNull(dropped.phOther)

        // the same recording judged as the other word: the score sits under the other reference
        val plain = scoreWithPair(input(95.0, 70.0, null, 12.0, "walk."), "walk", "walked", "-ed")
        assertEquals("walk", plain.heard)
        assertEquals("phoneme:intended word:intended recognition:intended", plain.votes)
        assertNull(plain.ph); assertEquals(12, plain.phOther)

        val added = scoreWithPair(input(70.0, 95.0, null, 88.0, "walked."), "walk", "walked", "-ed")
        assertEquals("walked", added.heard)
        assertEquals("phoneme:other word:other recognition:other", added.votes)
        assertNull(added.ph); assertEquals(88, added.phOther)

        // 40–60: the phoneme signal does not vote, the other two decide
        val unclear = scoreWithPair(input(90.0, 70.0, 50.0, null, "walked."), "walked", "walk", "-ed")
        assertEquals("walked", unclear.heard)
        assertEquals("phoneme:none word:intended recognition:intended", unclear.votes)
        assertEquals(50, unclear.ph)
        // … and neither does a missing score
        val nothing = scoreWithPair(input(90.0, 70.0, null, null, null), "walked", "walk", "-ed")
        assertEquals("phoneme:none word:intended recognition:none", nothing.votes)
        assertNull(nothing.ph); assertNull(nothing.phOther)
    }

    @Test
    fun hPairsScoreTheOneSidedPhonemeToo() {
        // heat/eat: the /h/ exists in "heat" only
        val said = scoreWithPair(input(94.0, 74.0, 82.0, null, "heat."), "heat", "eat", "h")
        assertEquals("heat", said.heard)
        assertEquals("phoneme:intended word:intended recognition:intended", said.votes)
        assertEquals(82, said.ph); assertNull(said.phOther)
        assertEquals(1, ProductionScorer.points(said, "heat", 60))

        // "eat" said for "heat": the /h/ under the "heat" reference collapses
        val dropped = scoreWithPair(input(72.0, 93.0, 8.0, null, "eat."), "heat", "eat", "h")
        assertEquals("eat", dropped.heard)
        assertEquals("phoneme:other word:other recognition:other", dropped.votes)
        assertEquals(0, ProductionScorer.points(dropped, "heat", 60))

        // asked for "eat", the /h/ is measured under the other reference
        val clean = scoreWithPair(input(93.0, 72.0, null, 8.0, "eat."), "eat", "heat", "h")
        assertEquals("eat", clean.heard)
        assertEquals("phoneme:intended word:intended recognition:intended", clean.votes)
        assertNull(clean.ph); assertEquals(8, clean.phOther)
    }

    @Test
    fun withoutThePairAnInsertionContrastSimplyDoesNotVoteOnPhonemes() {
        // the old two-signal behaviour, kept for callers that have no pair to hand
        val r = score(input(96.0, 70.0, 99.0, null, "walked."), "walked", "walk", "-ed")
        assertEquals("phoneme:none word:intended recognition:intended", r.votes)
        assertEquals("walked", r.heard)
    }
}
