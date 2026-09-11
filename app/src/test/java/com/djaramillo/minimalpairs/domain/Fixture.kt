package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.Catalog
import com.djaramillo.minimalpairs.domain.model.Contrast
import com.djaramillo.minimalpairs.domain.model.LearnerState
import com.djaramillo.minimalpairs.domain.model.Pair
import com.djaramillo.minimalpairs.domain.model.PairWord
import com.djaramillo.minimalpairs.domain.model.WordState

/** A small in-code catalog: 4 trainable contrasts with 7–8 pairs each, some rare / low-band. */
object Fixture {
    val voices = listOf("en-GB-SoniaNeural", "en-GB-RyanNeural", "en-GB-LibbyNeural")

    private fun w(word: String, ipa: String, band: String): PairWord = PairWord(
        word = word, ipa = ipa,
        rank = when (band) { "high" -> 1000; "mid" -> 5000; "low" -> 20000; else -> null },
        band = band,
    )

    private fun pair(
        contrast: String, a: PairWord, b: PairWord, diff: List<String>, position: String,
        trainable: Boolean = true,
    ) = Pair(
        id = "$contrast:${a.word}-${b.word}", a = a, b = b, diff = diff,
        variant = diff.joinToString("/"), position = position, trainable = trainable,
    )

    val th = Contrast(
        id = "th", label = "think / sink", kind = "consonant", phonemes = listOf("θ", "s"),
        defaultWeight = 1.0, trainable = true, description = "θ versus s",
        pairs = listOf(
            pair("th", w("think", "θˈɪŋk", "high"), w("sink", "sˈɪŋk", "high"), listOf("θ", "s"), "initial"),
            pair("th", w("thick", "θˈɪk", "mid"), w("sick", "sˈɪk", "high"), listOf("θ", "s"), "initial"),
            pair("th", w("mouth", "mˈaʊθ", "high"), w("mouse", "mˈaʊs", "high"), listOf("θ", "s"), "final"),
            pair("th", w("thumb", "θˈʌm", "mid"), w("sum", "sˈʌm", "high"), listOf("θ", "s"), "initial"),
            pair("th", w("thing", "θˈɪŋ", "high"), w("sing", "sˈɪŋ", "high"), listOf("θ", "s"), "initial"),
            pair("th", w("faith", "fˈeɪθ", "mid"), w("face", "fˈeɪs", "high"), listOf("θ", "s"), "final"),
            pair("th", w("path", "pˈɑːθ", "high"), w("pass", "pˈɑːs", "high"), listOf("θ", "s"), "final"),
            pair("th", w("thaw", "θˈɔː", "low"), w("saw", "sˈɔː", "high"), listOf("θ", "s"), "initial"),
            pair("th", w("thane", "θˈeɪn", "rare"), w("sane", "sˈeɪn", "low"), listOf("θ", "s"), "initial", trainable = false),
        ),
    )

    val sz = Contrast(
        id = "s/z", label = "sip / zip", kind = "consonant", phonemes = listOf("s", "z"),
        defaultWeight = 0.8, trainable = true,
        pairs = listOf(
            pair("s/z", w("sip", "sˈɪp", "mid"), w("zip", "zˈɪp", "mid"), listOf("s", "z"), "initial"),
            pair("s/z", w("bus", "bˈʌs", "high"), w("buzz", "bˈʌz", "mid"), listOf("s", "z"), "final"),
            pair("s/z", w("price", "pɹˈaɪs", "high"), w("prize", "pɹˈaɪz", "mid"), listOf("s", "z"), "final"),
            pair("s/z", w("ice", "ˈaɪs", "high"), w("eyes", "ˈaɪz", "high"), listOf("s", "z"), "final"),
            pair("s/z", w("race", "ɹˈeɪs", "high"), w("raise", "ɹˈeɪz", "high"), listOf("s", "z"), "final"),
            pair("s/z", w("loose", "lˈuːs", "high"), w("lose", "lˈuːz", "high"), listOf("s", "z"), "final"),
            pair("s/z", w("sue", "sˈuː", "low"), w("zoo", "zˈuː", "mid"), listOf("s", "z"), "initial"),
            pair("s/z", w("fuss", "fˈʌs", "low"), w("fuzz", "fˈʌz", "rare"), listOf("s", "z"), "final", trainable = false),
        ),
    )

    val iii = Contrast(
        id = "i/ii", label = "ship / sheep", kind = "vowel", phonemes = listOf("ɪ", "iː"),
        defaultWeight = 0.6, trainable = true,
        pairs = listOf(
            pair("i/ii", w("ship", "ʃˈɪp", "high"), w("sheep", "ʃˈiːp", "mid"), listOf("ɪ", "iː"), "medial"),
            pair("i/ii", w("bit", "bˈɪt", "high"), w("beat", "bˈiːt", "high"), listOf("ɪ", "iː"), "medial"),
            pair("i/ii", w("sit", "sˈɪt", "high"), w("seat", "sˈiːt", "high"), listOf("ɪ", "iː"), "medial"),
            pair("i/ii", w("fill", "fˈɪl", "high"), w("feel", "fˈiːl", "high"), listOf("ɪ", "iː"), "medial"),
            pair("i/ii", w("hit", "hˈɪt", "high"), w("heat", "hˈiːt", "high"), listOf("ɪ", "iː"), "medial"),
            pair("i/ii", w("live", "lˈɪv", "high"), w("leave", "lˈiːv", "high"), listOf("ɪ", "iː"), "medial"),
            pair("i/ii", w("chip", "tʃˈɪp", "mid"), w("cheap", "tʃˈiːp", "mid"), listOf("ɪ", "iː"), "medial"),
            pair("i/ii", w("lick", "lˈɪk", "low"), w("leak", "lˈiːk", "low"), listOf("ɪ", "iː"), "medial"),
        ),
    )

    val bv = Contrast(
        id = "b/v", label = "berry / very", kind = "consonant", phonemes = listOf("b", "v"),
        defaultWeight = 0.5, trainable = true,
        pairs = listOf(
            pair("b/v", w("berry", "bˈɛɹi", "mid"), w("very", "vˈɛɹi", "high"), listOf("b", "v"), "initial"),
            pair("b/v", w("boat", "bˈəʊt", "high"), w("vote", "vˈəʊt", "high"), listOf("b", "v"), "initial"),
            pair("b/v", w("ban", "bˈæn", "mid"), w("van", "vˈæn", "high"), listOf("b", "v"), "initial"),
            pair("b/v", w("best", "bˈɛst", "high"), w("vest", "vˈɛst", "mid"), listOf("b", "v"), "initial"),
            pair("b/v", w("bowl", "bˈəʊl", "mid"), w("vole", "vˈəʊl", "low"), listOf("b", "v"), "initial"),
            pair("b/v", w("bat", "bˈæt", "mid"), w("vat", "vˈæt", "low"), listOf("b", "v"), "initial"),
            pair("b/v", w("bet", "bˈɛt", "high"), w("vet", "vˈɛt", "mid"), listOf("b", "v"), "initial"),
            pair("b/v", w("bail", "bˈeɪl", "low"), w("veil", "vˈeɪl", "low"), listOf("b", "v"), "initial"),
        ),
    )

    val schwa = Contrast(
        id = "schwa", label = "schwa", kind = "vowel", phonemes = listOf("ə", "ɒ"),
        defaultWeight = 0.15, trainable = false,
        pairs = listOf(
            pair("schwa", w("affect", "əfˈɛkt", "mid"), w("effect", "ɪfˈɛkt", "high"), listOf("ə", "ɪ"), "initial"),
        ),
    )

    val sCluster = Contrast(
        id = "s-cluster", label = "s-cluster", kind = "consonant", phonemes = listOf("s", ""),
        defaultWeight = 0.0, trainable = false, productionOnly = true, pairs = emptyList(),
    )

    val catalog = Catalog(
        version = "2026-09-11.1",
        generated = "2026-09-11T10:00:00Z",
        sources = mapOf("pronunciation" to "Britfone 3.0.1 (MIT)", "frequency" to "en_50k (MIT)"),
        voices = voices,
        contrasts = listOf(th, sz, iii, bv, schwa, sCluster),
    )

    val allWords: Set<String> = catalog.allTrainableWords()

    /** A state where every trainable word has been heard [exposures] times, except [untrained]. */
    fun trainedState(exposures: Int = 2, untrained: Set<String> = emptySet()): LearnerState = LearnerState(
        words = allWords.filter { it !in untrained }.associateWith { WordState(exposures = exposures, correct = exposures, last = "2026-09-10T07:00:00Z") },
    )
}
