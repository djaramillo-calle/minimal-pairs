package com.djaramillo.minimalpairs.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `assets/catalog.json` — see docs/DESIGN.md "Catalog". Field names match the
 * file exactly; unknown keys are ignored by [com.djaramillo.minimalpairs.domain.AppJson].
 */
@Serializable
data class Catalog(
    val version: String = "",
    val generated: String = "",
    val sources: Map<String, String> = emptyMap(),
    val voices: List<String> = emptyList(),
    val contrasts: List<Contrast> = emptyList(),
) {
    private val byId: Map<String, Contrast> by lazy { contrasts.associateBy { it.id } }

    /** Contrast lookup by id, or null when the catalog has no such contrast. */
    fun contrast(id: String): Contrast? = byId[id]

    /** Contrasts that can actually be drilled: `trainable` and with at least one trainable pair. */
    val trainableContrasts: List<Contrast>
        get() = contrasts.filter { it.trainable && it.trainablePairs.isNotEmpty() }

    /** Every word that appears in a trainable pair of a trainable contrast (clip list). */
    fun allTrainableWords(): Set<String> =
        trainableContrasts.flatMapTo(LinkedHashSet()) { it.trainableWords() }
}

@Serializable
data class Contrast(
    val id: String,
    val label: String = "",
    val kind: String = "",
    val phonemes: List<String> = emptyList(),
    @SerialName("default_weight") val defaultWeight: Double = 0.0,
    val trainable: Boolean = true,
    @SerialName("production_only") val productionOnly: Boolean = false,
    val description: String = "",
    val pairs: List<Pair> = emptyList(),
) {
    /** Pairs flagged `trainable` in the catalog (both words common, unambiguous, a-z only). */
    val trainablePairs: List<Pair>
        get() = pairs.filter { it.trainable }

    /** Distinct words of the trainable pairs, in catalog order. */
    fun trainableWords(): Set<String> =
        trainablePairs.flatMapTo(LinkedHashSet()) { listOf(it.a.word, it.b.word) }
}

/** One minimal pair. `a` carries the first phoneme of the contrast, `b` the second. */
@Serializable
data class Pair(
    val id: String,
    val a: PairWord,
    val b: PairWord,
    /** Phoneme of `a` and of `b` at the differing slot; `""` for an absent phoneme. */
    val diff: List<String> = emptyList(),
    val variant: String = "",
    /** `initial`, `medial` or `final`. */
    val position: String = "medial",
    val trainable: Boolean = true,
) {
    /** The word opposite to [word] in this pair, or null when [word] is not in the pair. */
    fun other(word: String): String? = when (word) {
        a.word -> b.word
        b.word -> a.word
        else -> null
    }

    fun side(word: String): PairWord? = when (word) {
        a.word -> a
        b.word -> b
        else -> null
    }
}

@Serializable
data class PairWord(
    val word: String,
    val ipa: String = "",
    /** Frequency rank in en_50k; null when the word is not in the list (`band` = `rare`). */
    val rank: Int? = null,
    /** `high`, `mid`, `low` or `rare`. */
    val band: String = "rare",
)
