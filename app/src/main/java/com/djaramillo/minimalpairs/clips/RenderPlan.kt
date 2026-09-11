package com.djaramillo.minimalpairs.clips

import com.djaramillo.minimalpairs.domain.model.Catalog

/**
 * Pure helpers for rendering the clip pack on the phone with the user's own
 * Azure Speech key (Settings → Azure Speech). No Android types, unit tested.
 * Mirrors `scripts/render-clips.py`: same SSML, same output format, same
 * validation, same `index.json` semantics.
 */
object RenderPlan {
    /** Azure output format: Opus in WebM, 24 kHz, 24 kbps, mono (docs/DESIGN.md "Clips"). */
    const val FORMAT = "webm-24khz-16bit-24kbps-mono-opus"
    const val FORMAT_LABEL = "webm/opus 24 kHz 24 kbps mono"

    /** A rendered word is never shorter than this in that format. */
    const val MIN_CLIP_BYTES = 1500
    private val EBML_MAGIC = byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte())

    /** The six voices of docs/DESIGN.md, used when the catalog lists none. */
    val DEFAULT_VOICES = listOf(
        "en-GB-SoniaNeural", "en-GB-LibbyNeural", "en-GB-HollieNeural",
        "en-GB-RyanNeural", "en-GB-ThomasNeural", "en-GB-AlfieNeural",
    )

    fun voicesFor(catalog: Catalog): List<String> = catalog.voices.ifEmpty { DEFAULT_VOICES }

    /** Same SSML as the script: one word, en-GB, the voice by name. */
    fun ssml(voice: String, word: String): String =
        "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"en-GB\">" +
            "<voice name=\"${escapeXml(voice)}\">${escapeXml(word)}</voice></speak>"

    fun escapeXml(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }

    /** Null when the bytes look like a rendered clip, else the reason to reject them. */
    fun validateClip(data: ByteArray): String? {
        if (data.isEmpty()) return "empty response"
        if (data.size < 4 || !data.copyOfRange(0, 4).contentEquals(EBML_MAGIC)) return "not a WebM file"
        if (data.size < MIN_CLIP_BYTES) return "too short (${data.size} bytes)"
        return null
    }

    /**
     * Words to render, in the order that makes a partial pack useful soonest:
     * contrasts by default weight (highest first), within a contrast the pairs
     * in catalog order (commonest first), each word once.
     */
    fun renderOrder(catalog: Catalog): List<String> {
        val seen = LinkedHashSet<String>()
        catalog.contrasts
            .filter { it.trainable && !it.productionOnly }
            .sortedByDescending { it.defaultWeight }
            .forEach { c ->
                c.pairs.filter { it.trainable }.forEach { p ->
                    seen.add(p.a.word); seen.add(p.b.word)
                }
            }
        return seen.toList()
    }

    /**
     * `index.json` for whatever clips are present, exactly as the script
     * computes it: a word counts only when every voice has it; `complete`
     * when every trainable word of every trainable contrast is present.
     * [sizeOf] returns the clip's byte size or null when it is missing.
     */
    fun buildIndex(
        catalog: Catalog,
        voices: List<String>,
        sizeOf: (voice: String, word: String) -> Long?,
    ): ClipIndex {
        val byContrast = catalog.contrasts
            .filter { it.trainable && !it.productionOnly }
            .associate { c -> c.id to c.trainableWords() }
        val allWords = byContrast.values.flatten().toSet()
        // Only the installed catalog's words are counted (clips of an older
        // catalog left on disk are ignored, unlike the script's scan).
        val sizes = HashMap<String, Long>()
        val present = LinkedHashSet<String>()
        for (w in allWords.sorted()) {
            var everyVoice = voices.isNotEmpty()
            for (v in voices) {
                val n = sizeOf(v, w)
                if (n == null || n <= 0) { everyVoice = false } else sizes["$v/$w"] = n
            }
            if (everyVoice) present.add(w)
        }
        val covered = byContrast.filter { (_, ws) -> ws.isNotEmpty() && present.containsAll(ws) }.keys.toList()
        val complete = voices.isNotEmpty() && allWords.isNotEmpty() && present.containsAll(allWords)
        return ClipIndex(
            version = 1,
            catalogVersion = catalog.version,
            format = FORMAT_LABEL,
            voices = voices,
            words = present.toList(),
            complete = complete,
            contrasts = catalog.contrasts.map { it.id }.filter { it in covered },
            files = sizes.size,
            bytes = sizes.values.sum(),
        )
    }

    /** Backoff for attempt 1, 2, 3… honouring a Retry-After header (seconds) when given. */
    fun retryDelayMs(attempt: Int, retryAfter: String?): Long {
        retryAfter?.trim()?.toLongOrNull()?.let { if (it in 0..120) return it * 1000 }
        return (1500L shl (attempt - 1).coerceIn(0, 4))
    }

    /** Azure regions are short lowercase identifiers (`uksouth`, `westeurope`). */
    fun normalizeRegion(s: String): String? =
        s.trim().lowercase().takeIf { it.matches(Regex("[a-z0-9]{3,32}")) }

    fun normalizeKey(s: String): String? =
        s.trim().takeIf { it.length in 16..256 && it.none { ch -> ch.isWhitespace() } }
}
