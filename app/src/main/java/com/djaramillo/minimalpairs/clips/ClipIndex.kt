package com.djaramillo.minimalpairs.clips

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `clips/index.json` (docs/DESIGN.md "Clip pack index.json"). */
@Serializable
data class ClipIndex(
    val version: Int = 1,
    @SerialName("catalog_version") val catalogVersion: String = "",
    val format: String = "",
    val voices: List<String> = emptyList(),
    val words: List<String> = emptyList(),
    val complete: Boolean = false,
    val contrasts: List<String> = emptyList(),
    val files: Int = 0,
    val bytes: Long = 0,
) {
    /** A source pack holds every listed word in every listed voice. */
    fun has(word: String, voice: String): Boolean = voice in voiceSet && word in wordSet

    val voiceSet: Set<String> by lazy { voices.toSet() }
    val wordSet: Set<String> by lazy { words.toSet() }
}

enum class ClipSourceKind { BUNDLED, DOWNLOADED }

/**
 * The merged view of the bundled and the downloaded pack. Pure so it is unit
 * tested; [ClipPack] builds it from the two `index.json` files.
 */
data class MergedIndex(
    val bundled: ClipIndex?,
    val downloaded: ClipIndex?,
) {
    /** Union of the voices of both sources, bundled order first. */
    val voices: List<String> = LinkedHashSet<String>().apply {
        bundled?.voices?.let { addAll(it) }
        downloaded?.voices?.let { addAll(it) }
    }.toList()

    /**
     * Words that have a clip in every merged voice, from either source. This is
     * the `availableWords` the scheduler needs ("clips for all its words").
     */
    val words: Set<String> = run {
        val all = LinkedHashSet<String>()
        bundled?.words?.let { all.addAll(it) }
        downloaded?.words?.let { all.addAll(it) }
        if (voices.isEmpty()) emptySet()
        else all.filterTo(LinkedHashSet()) { w -> voices.all { v -> has(w, v) } }
    }

    val isEmpty: Boolean get() = bundled == null && downloaded == null

    /** Some source says it holds the whole catalog. */
    val declaredComplete: Boolean get() = (bundled?.complete == true) || (downloaded?.complete == true)

    fun has(word: String, voice: String): Boolean =
        (bundled?.has(word, voice) == true) || (downloaded?.has(word, voice) == true)

    /** Where to read `word` in `voice` from first: bundled assets are always present, prefer them. */
    fun sourceFor(word: String, voice: String): ClipSourceKind? = when {
        bundled?.has(word, voice) == true -> ClipSourceKind.BUNDLED
        downloaded?.has(word, voice) == true -> ClipSourceKind.DOWNLOADED
        else -> null
    }

    /** Catalog words that are not available in every voice. */
    fun missingWords(catalogWords: Set<String>): Set<String> = catalogWords.filterTo(LinkedHashSet()) { it !in words }
}
