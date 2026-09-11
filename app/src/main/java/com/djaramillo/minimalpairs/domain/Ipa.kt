package com.djaramillo.minimalpairs.domain

/**
 * Tokeniser and highlight model for the catalog's IPA strings (Britfone
 * tokens concatenated, stress marks `ˈ` `ˌ` kept before the vowel).
 */
object Ipa {
    /** Multi-character Britfone units; everything else is one character per token. */
    val MULTI_CHAR: List<String> = listOf(
        "tʃ", "dʒ", "iː", "uː", "ɑː", "ɔː", "ɜː",
        "eɪ", "aɪ", "ɔɪ", "əʊ", "aʊ", "ɪə", "ɛə", "ʊə",
    )
    const val PRIMARY_STRESS = 'ˈ'
    const val SECONDARY_STRESS = 'ˌ'

    /**
     * One phoneme of an IPA string. [start]..[end] (exclusive) span the phoneme
     * characters only; [stressStart] is the index of the preceding stress mark
     * when there is one (it belongs to this token visually).
     */
    data class Token(val phoneme: String, val start: Int, val end: Int, val stressStart: Int? = null) {
        val range: IntRange get() = start until end
        /** Range including the stress mark, for callers that highlight the whole unit. */
        val fullRange: IntRange get() = (stressStart ?: start) until end
    }

    fun tokens(ipa: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        var pendingStress: Int? = null
        while (i < ipa.length) {
            val ch = ipa[i]
            if (ch == PRIMARY_STRESS || ch == SECONDARY_STRESS) {
                pendingStress = i
                i++
                continue
            }
            val multi = MULTI_CHAR.firstOrNull { ipa.startsWith(it, i) }
            val len = multi?.length ?: 1
            out.add(Token(ipa.substring(i, i + len), i, i + len, pendingStress))
            pendingStress = null
            i += len
        }
        return out
    }

    /** Phonemes only, stress marks dropped. */
    fun phonemes(ipa: String): List<String> = tokens(ipa).map { it.phoneme }

    /** The IPA of one word with the differing phoneme located, or [range] null when it is absent (`diff` entry `""`). */
    data class Highlight(val ipa: String, val range: IntRange?, val phoneme: String) {
        val before: String get() = if (range == null) ipa else ipa.substring(0, range.first)
        val highlighted: String get() = if (range == null) "" else ipa.substring(range.first, range.last + 1)
        val after: String get() = if (range == null) "" else ipa.substring(range.last + 1)
    }

    data class PairHighlight(val a: Highlight, val b: Highlight)

    /**
     * Locate the differing slot of a minimal pair. [diff] is the catalog's
     * `["ɪ", "iː"]` (or `["", "h"]` for an absent phoneme). Alignment first
     * (same length → first differing slot; length ±1 → the insertion point),
     * falling back to the first occurrence of the diff phoneme.
     */
    fun highlight(ipaA: String, ipaB: String, diff: List<String>): PairHighlight {
        val ta = tokens(ipaA)
        val tb = tokens(ipaB)
        val da = diff.getOrNull(0) ?: ""
        val db = diff.getOrNull(1) ?: ""
        var ia: Int? = null
        var ib: Int? = null

        if (ta.size == tb.size) {
            val k = ta.indices.firstOrNull { ta[it].phoneme != tb[it].phoneme }
            if (k != null && (da.isEmpty() || ta[k].phoneme == da) && (db.isEmpty() || tb[k].phoneme == db)) {
                ia = k; ib = k
            }
        } else if (ta.size == tb.size + 1 || tb.size == ta.size + 1) {
            val longer = if (ta.size > tb.size) ta else tb
            val shorter = if (ta.size > tb.size) tb else ta
            val k = shorter.indices.firstOrNull { shorter[it].phoneme != longer[it].phoneme } ?: shorter.size
            val extra = longer[k].phoneme
            val expected = if (ta.size > tb.size) da else db
            if (expected.isEmpty() || expected == extra) {
                if (ta.size > tb.size) { ia = k; ib = null } else { ia = null; ib = k }
            }
        }

        if (ia == null && da.isNotEmpty()) ia = ta.indexOfFirst { it.phoneme == da }.takeIf { it >= 0 }
        if (ib == null && db.isNotEmpty()) ib = tb.indexOfFirst { it.phoneme == db }.takeIf { it >= 0 }

        return PairHighlight(
            a = Highlight(ipaA, ia?.let { ta[it].range }, da),
            b = Highlight(ipaB, ib?.let { tb[it].range }, db),
        )
    }

    /** Britfone vowel tokens (catalog spelling, ʌ for STRUT). */
    val VOWELS: Set<String> = setOf(
        "ə", "ɪ", "ɛ", "æ", "i", "ɒ", "ʌ", "ɐ", "ʊ", "iː", "uː", "ɑː", "ɔː", "ɜː",
        "eɪ", "aɪ", "ɔɪ", "əʊ", "aʊ", "ɪə", "ɛə", "ʊə",
    )

    /**
     * Consonant clusters English allows at the start of a syllable (Britfone
     * spelling: ɹ, g). Single consonants other than ŋ are always allowed.
     */
    val ONSET_CLUSTERS: Set<String> = setOf(
        "pl", "bl", "kl", "gl", "fl", "sl",
        "pɹ", "bɹ", "tɹ", "dɹ", "kɹ", "gɹ", "fɹ", "θɹ", "ʃɹ",
        "tw", "dw", "kw", "gw", "θw", "sw",
        "pj", "bj", "tj", "dj", "kj", "gj", "fj", "vj", "mj", "nj", "lj", "sj", "zj", "hj", "θj",
        "sp", "st", "sk", "sm", "sn", "sf",
        "spl", "spɹ", "spj", "stɹ", "stj", "skɹ", "skw", "skj", "skl",
    )

    private fun isVowel(p: String) = p in VOWELS

    private fun isOnset(cluster: List<String>): Boolean =
        cluster.size == 1 && cluster[0] != "ŋ" || cluster.joinToString("") in ONSET_CLUSTERS

    /**
     * Display form of a catalog IPA string. The catalog keeps Britfone's
     * convention, the stress mark directly before the vowel (`pɹˈaɪs`);
     * standard IPA puts it before the syllable (`ˈpɹaɪs`). Each mark is moved
     * left over the longest run of preceding consonants that forms a legal
     * English onset, never past a vowel. Returns the new string and, for
     * every token index, the token's new phoneme range.
     */
    private fun displayLayout(ipa: String): Pair<String, List<IntRange>> {
        val toks = tokens(ipa)
        val markAt = HashMap<Int, Char>() // token index → stress mark emitted before it
        for ((k, t) in toks.withIndex()) {
            val s = t.stressStart ?: continue
            var j = k
            while (j > 0 && !isVowel(toks[j - 1].phoneme) && isOnset(toks.subList(j - 1, k).map { it.phoneme })) j--
            markAt[j] = ipa[s]
        }
        val sb = StringBuilder()
        val ranges = ArrayList<IntRange>(toks.size)
        for ((k, t) in toks.withIndex()) {
            markAt[k]?.let { sb.append(it) }
            val start = sb.length
            sb.append(t.phoneme)
            ranges.add(start until sb.length)
        }
        return sb.toString() to ranges
    }

    /** [displayLayout] for a plain string. */
    fun display(ipa: String): String = displayLayout(ipa).first

    /** The same highlight in display form (stress mark at the syllable onset), range remapped. */
    fun display(h: Highlight): Highlight {
        val (text, ranges) = displayLayout(h.ipa)
        val range = h.range ?: return Highlight(text, null, h.phoneme)
        val toks = tokens(h.ipa)
        val k = toks.indexOfFirst { it.range == range }
        if (k < 0) return h // not a token range: leave the catalog form untouched
        return Highlight(text, ranges[k], h.phoneme)
    }
}
