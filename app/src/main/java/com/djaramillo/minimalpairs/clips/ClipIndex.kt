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

/**
 * Whether a freshly unpacked `clips.zip` may replace the downloaded pack. Pure
 * so it is unit tested; [ClipDownloader] asks after the checksum step and
 * before the swap. Without this, the placeholder `clips.zip` that CI publishes
 * when no Azure secrets are set (12 words, `complete: false`) would replace a
 * full pack that was downloaded earlier.
 */
object DownloadCheck {
    /**
     * Null when [candidate] is acceptable, else the reason to refuse it (shown
     * as the download failure). Refused: an unparsable index, a pack that says
     * `complete: false`, one that covers fewer catalog words than what is
     * installed now ([current] = bundled + downloaded), one rendered for another
     * catalog version that does not cover this catalog, and one whose listed
     * clips are not all in the archive.
     */
    fun refuse(
        candidate: ClipIndex?,
        bundled: ClipIndex?,
        current: MergedIndex,
        catalogVersion: String,
        catalogWords: Set<String>,
        fileExists: (voice: String, word: String) -> Boolean,
    ): String? {
        if (candidate == null) return "the archive's index.json does not parse; keeping the current pack"
        if (!candidate.complete) {
            return "the release carries a placeholder or partial pack (${candidate.words.size} words, complete = false); keeping the current pack"
        }
        val merged = MergedIndex(bundled, candidate)
        val now = current.words.count { it in catalogWords }
        val then = merged.words.count { it in catalogWords }
        if (then < now) {
            return "the pack covers $then of ${catalogWords.size} catalog words, fewer than the installed $now; keeping the current pack"
        }
        if (candidate.catalogVersion != catalogVersion && then < catalogWords.size) {
            return "the pack was rendered for catalog ${candidate.catalogVersion}, this app has $catalogVersion, " +
                "and it lacks ${catalogWords.size - then} of its words; keeping the current pack"
        }
        for (v in candidate.voices) {
            for (w in candidate.words) {
                if (!fileExists(v, w)) return "index.json lists $v/$w.webm but the archive has no such file; keeping the current pack"
            }
        }
        return null
    }
}
