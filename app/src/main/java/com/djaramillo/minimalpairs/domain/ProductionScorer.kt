package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.Catalog
import com.djaramillo.minimalpairs.domain.model.Pair
import com.djaramillo.minimalpairs.domain.model.WordResult
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Say-it scoring rule of docs/CONTRACT.md ("Say it — production rows"):
 * three Azure signals (differing phoneme, word accuracy, recognition) with a
 * majority vote, then the threshold gate for the point. Pure: the Android
 * layer makes the three REST calls and hands the numbers in.
 */
object ProductionScorer {
    /** Contrasts whose phoneme signal is skipped (en-US models have no British ɒ / ɑː–æ split). */
    val PHONEME_SKIP_CONTRASTS: Set<String> = setOf("long-back", "schwa")

    /** The differing phoneme must score this many points higher under one reference than the other to vote. */
    const val PHONEME_MARGIN = 15

    /**
     * Insertion pairs (`-ed`, `h`: walk/walked, eat/heat) have the differing
     * phoneme on one side only, so there is nothing to compare it with. It is
     * then scored under the reference that contains it and votes for that word
     * from [ONE_SIDED_PRESENT] up, for the other word below [ONE_SIDED_ABSENT];
     * in between the signal does not vote (docs/CONTRACT.md "Say it").
     */
    const val ONE_SIDED_PRESENT = 60
    const val ONE_SIDED_ABSENT = 40
    /** Word accuracy must be this many points higher under one reference than the other to vote. */
    const val WORD_MARGIN = 10

    const val INTENDED = "intended"
    const val OTHER = "other"
    const val NONE = "none"
    /** `heard` when the votes tie or nothing voted. */
    const val UNKNOWN = "?"

    /** Britfone → Azure en-US IPA for the symbols that differ; everything else is identical. */
    val AZURE_SYMBOL: Map<String, String> = mapOf(
        "iː" to "i", "ɜː" to "ɝ", "ɔː" to "ɔ", "ɒ" to "ɑ", "ɑː" to "ɑ",
    )

    /** The Azure symbol for a Britfone phoneme (catalog spelling). */
    fun azureSymbol(britfone: String): String = AZURE_SYMBOL[britfone] ?: britfone

    /** Where the differing phoneme of a word sits, in Azure's alphabet. */
    data class PhonemeTarget(
        /** Azure en-US IPA symbol to look for. */
        val symbol: String,
        /** 0-based token index in the Britfone transcription (stress marks are not tokens). */
        val position: Int,
        /** Number of Britfone tokens of the word, to judge whether Azure's list aligns. */
        val phonemeCount: Int,
    )

    /**
     * The differing phoneme of [word] in [pair] (from `pair.diff`) and its
     * token position ([Ipa.tokens] over the word's catalog IPA). `null` when
     * [word] is not in the pair, its diff entry is `""` (absent phoneme, e.g.
     * `walk` in walk/walked) or the phoneme is not in the transcription.
     */
    fun phonemeOfInterest(pair: Pair, word: String): PhonemeTarget? {
        val side = pair.side(word) ?: return null
        val britfone = (if (word == pair.a.word) pair.diff.getOrNull(0) else pair.diff.getOrNull(1)) ?: return null
        if (britfone.isEmpty()) return null
        val tokens = Ipa.phonemes(side.ipa)
        val position = locate(pair, word, tokens, britfone) ?: return null
        return PhonemeTarget(azureSymbol(britfone), position, tokens.size)
    }

    /** Token index of the differing slot: alignment against the other word first, else first occurrence. */
    private fun locate(pair: Pair, word: String, tokens: List<String>, britfone: String): Int? {
        val h = Ipa.highlight(pair.a.ipa, pair.b.ipa, pair.diff)
        val hl = if (word == pair.a.word) h.a else h.b
        val range = hl.range
        if (range != null) {
            val k = Ipa.tokens(hl.ipa).indexOfFirst { it.range == range }
            if (k >= 0 && tokens.getOrNull(k) == britfone) return k
        }
        return tokens.indexOf(britfone).takeIf { it >= 0 }
    }

    /**
     * The accuracy of the differing phoneme in Azure's phoneme list
     * (`Words[0].Phonemes` as symbol → AccuracyScore, in order). The symbol
     * wins when present (the occurrence nearest [position] if it repeats);
     * otherwise the entry at [position], but only when Azure's list has the
     * same length as Britfone's ([expectedCount]; Azure merges tokens such as
     * `ɛɹ` in "very", and a shifted list would point at the wrong sound).
     * `null` when nothing fits.
     */
    fun pickPhonemeScore(
        azurePhonemes: List<kotlin.Pair<String, Double>>,
        symbol: String,
        position: Int,
        expectedCount: Int? = null,
    ): Double? {
        if (azurePhonemes.isEmpty()) return null
        val wanted = fold(symbol)
        val hits = azurePhonemes.indices.filter { fold(azurePhonemes[it].first) == wanted }
        if (hits.isNotEmpty()) {
            val k = hits.minByOrNull { abs(it - position) }!!
            return azurePhonemes[k].second
        }
        if (expectedCount != null && azurePhonemes.size != expectedCount) return null
        return azurePhonemes.getOrNull(position)?.second
    }

    fun pickPhonemeScore(azurePhonemes: List<kotlin.Pair<String, Double>>, target: PhonemeTarget): Double? =
        pickPhonemeScore(azurePhonemes, target.symbol, target.position, target.phonemeCount)

    /** Symbol comparison tolerant to Unicode look-alikes Azure and Britfone spell differently. */
    private fun fold(symbol: String): String = symbol.trim().replace('ɡ', 'g').replace("ɹ", "r").replace("ː", "")

    /**
     * Which word of [pair] carries the differing phoneme when only one of them
     * does (an insertion pair: `walk`/`walked`, `eat`/`heat`): [INTENDED],
     * [OTHER], or `null` when both carry one (the ordinary substitution pair)
     * or neither does (the word is not in the pair, or its phoneme is not in
     * the transcription).
     */
    fun phonemeCarrier(pair: Pair, intended: String, other: String): String? {
        val hasIntended = phonemeOfInterest(pair, intended) != null
        val hasOther = phonemeOfInterest(pair, other) != null
        return when {
            hasIntended && !hasOther -> INTENDED
            hasOther && !hasIntended -> OTHER
            else -> null
        }
    }

    /** The one-sided phoneme vote: [present] from 60 up, [absent] below 40, no vote in between or without a score. */
    private fun oneSidedVote(score: Int?, present: String, absent: String): String = when {
        score == null -> NONE
        score >= ONE_SIDED_PRESENT -> present
        score < ONE_SIDED_ABSENT -> absent
        else -> NONE
    }

    /** The measurements for one recorded word, as the three Azure calls returned them. */
    data class WordInput(
        /** Word-level AccuracyScore with the intended word as reference. */
        val acc: Double,
        /** Word-level AccuracyScore with the other word as reference. */
        val accOther: Double,
        /** Differing-phoneme score under the intended reference ([pickPhonemeScore]); `null` when unavailable. */
        val ph: Double?,
        /** Differing-phoneme score under the other reference. */
        val phOther: Double?,
        /** en-GB recognition text (`DisplayText` or `NBest[0].Lexical`); `null` when nothing was recognised. */
        val recognised: String?,
        /** Recording length in ms before padding. */
        val ms: Int,
        val attempts: Int = 1,
    )

    /**
     * Score one word. [intended] is the word the learner was asked to say,
     * [other] the pair's other word; [contrastId] decides whether the phoneme
     * signal is skipped; [catalog] resolves homophones for the recognition
     * vote; [threshold] is `plan.production_threshold`. [pair] is the pair the
     * two words come from; given, an insertion pair (the differing phoneme on
     * one side only, `-ed` and `h`) is scored by the one-sided rule and the
     * score is kept under the reference that contains the phoneme, the other
     * left `null`.
     */
    fun score(
        input: WordInput,
        intended: String,
        other: String,
        contrastId: String,
        catalog: Catalog,
        threshold: Int,
        pair: Pair? = null,
    ): WordResult {
        val acc = clampScore(input.acc)
        val accOther = clampScore(input.accOther)
        val skipPhoneme = contrastId in PHONEME_SKIP_CONTRASTS
        val carrier = if (skipPhoneme || pair == null) null else phonemeCarrier(pair, intended, other)
        val ph = if (skipPhoneme || carrier == OTHER) null else input.ph?.let { clampScore(it) }
        val phOther = if (skipPhoneme || carrier == INTENDED) null else input.phOther?.let { clampScore(it) }

        val phonemeVote = when {
            carrier == INTENDED -> oneSidedVote(ph, INTENDED, OTHER)
            carrier == OTHER -> oneSidedVote(phOther, OTHER, INTENDED)
            ph == null || phOther == null -> NONE
            ph - phOther >= PHONEME_MARGIN -> INTENDED
            phOther - ph >= PHONEME_MARGIN -> OTHER
            else -> NONE
        }
        val wordVote = when {
            acc - accOther >= WORD_MARGIN -> INTENDED
            accOther - acc >= WORD_MARGIN -> OTHER
            else -> NONE
        }
        val recognised = normaliseRecognised(input.recognised)
        val ipa = wordIpa(catalog)
        val recognitionVote = if (recognised == null) NONE else {
            val isIntended = matches(recognised, intended, ipa)
            val isOther = matches(recognised, other, ipa)
            when {
                isIntended && !isOther -> INTENDED
                isOther && !isIntended -> OTHER
                else -> NONE
            }
        }

        val votes = listOf(phonemeVote, wordVote, recognitionVote)
        val forIntended = votes.count { it == INTENDED }
        val forOther = votes.count { it == OTHER }
        val heard = when {
            forIntended > forOther -> intended
            forOther > forIntended -> other
            else -> UNKNOWN
        }
        return WordResult(
            heard = heard,
            acc = acc,
            accOther = accOther,
            ph = ph,
            phOther = phOther,
            votes = "phoneme:$phonemeVote word:$wordVote recognition:$recognitionVote",
            recognised = recognised,
            ms = input.ms.coerceAtLeast(0),
            attempts = input.attempts.coerceAtLeast(1),
        )
    }

    /** 1 when [result] was heard as [intended] and its accuracy reaches [threshold], else 0. */
    fun points(result: WordResult, intended: String, threshold: Int): Int =
        if (result.heard == intended && result.acc >= threshold) 1 else 0

    /** Points of a pair from its two word results, keyed by word: 0, 1 or 2. */
    fun pairPoints(words: Map<String, WordResult>, threshold: Int): Int =
        words.entries.sumOf { (word, r) -> points(r, word, threshold) }

    private fun clampScore(v: Double): Int = if (v.isNaN()) 0 else v.roundToInt().coerceIn(0, 100)

    /** Lower-cased, punctuation stripped, whitespace collapsed; `null` when nothing is left. */
    fun normaliseRecognised(text: String?): String? {
        if (text == null) return null
        val sb = StringBuilder()
        for (ch in text.lowercase()) {
            if (ch.isLetterOrDigit() || ch == '\'') sb.append(ch)
            else if (ch.isWhitespace() || ch == '-') sb.append(' ')
        }
        val out = sb.toString().trim().replace(Regex("\\s+"), " ")
        return out.ifEmpty { null }
    }

    /** IPA with stress marks stripped: the key for the homophone check. */
    fun ipaKey(ipa: String): String = ipa.replace(Ipa.PRIMARY_STRESS.toString(), "").replace(Ipa.SECONDARY_STRESS.toString(), "")

    /** Every catalog word → its stress-stripped IPA (first spelling wins). */
    fun wordIpa(catalog: Catalog): Map<String, String> {
        val out = HashMap<String, String>()
        for (c in catalog.contrasts) for (p in c.pairs) {
            for (w in listOf(p.a, p.b)) if (w.ipa.isNotEmpty() && w.word !in out) out[w.word] = ipaKey(w.ipa)
        }
        return out
    }

    /**
     * Whether the recognised text names [word]: the same spelling, or a
     * catalog word with the same IPA (court/caught, sir/sore).
     */
    fun matches(recognised: String, word: String, wordIpa: Map<String, String>): Boolean {
        if (recognised == word) return true
        val a = wordIpa[recognised] ?: return false
        val b = wordIpa[word] ?: return false
        return a == b
    }

    fun matches(recognised: String, word: String, catalog: Catalog): Boolean = matches(recognised, word, wordIpa(catalog))
}
