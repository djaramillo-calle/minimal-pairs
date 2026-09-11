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
}
