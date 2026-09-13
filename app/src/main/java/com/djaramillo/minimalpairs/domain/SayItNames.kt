package com.djaramillo.minimalpairs.domain

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Every name the Say-it drill builds or reads (docs/CONTRACT.md, "`sayit/`").
 *
 * Names are contract, not convenience: `sayit/attempts/<ts>_<id>.m4a` and the
 * sidecar of the same stem are how the cloud coach pairs a recording with what
 * it is a recording of, and `<ts>` is exactly the sidecar's `started`, so the
 * two can never disagree. The basic UTC form `20260913T180402Z` carries no
 * colons because Android external storage and Drive reject them.
 *
 * The rules here are also the app's only defence against a broken or hostile
 * `words.json`: an id becomes a file name and a clip path is walked inside the
 * learner's folder, so both are validated here rather than at each call site.
 * Nothing throws — an unusable name reads as null or false, and its word is
 * dropped or simply shown without its clip.
 *
 * No Android types, unit tested on the JVM.
 */
object SayItNames {

    /** Attempt audio: AAC in an MP4 container. */
    const val AUDIO_EXTENSION = ".m4a"

    /** The sidecar of the same stem, written second. */
    const val SIDECAR_EXTENSION = ".json"

    /** An id is at most this long because it has to survive as a file name. */
    const val MAX_ID_LENGTH = 64


    private val ID = Regex("^[A-Za-z0-9._-]{1,$MAX_ID_LENGTH}$")
    private val STAMP = Regex("^\\d{8}T\\d{6}Z$")

    /** Same ceiling as [TimeUtil.firstFreeSessionStart]: the bump loop must end even if [freeStart] is lied to. */
    private const val BUMP_GUARD = 100_000

    private data class Parts(val ts: String, val id: String)

    /**
     * Whether a `<ts>_<id>` file name's id half is one of ours: `[A-Za-z0-9._-]`,
     * 1 to 64 characters, no leading dot. This is about **names the app wrote**,
     * not about which words it will practise — see [isPracticableId].
     */
    fun isUsableId(id: String): Boolean = ID.matches(id) && !id.startsWith(".")

    /**
     * Whether the coach's `words[].id` can be practised at all. Only a blank id
     * cannot: everything else becomes a file name through [fileId].
     *
     * The ids come from the coach's ledger, which holds the words Azure flagged
     * in his reads — so `don't` and `people's` are ordinary, and they are
     * exactly the connected-speech failures this drill exists for. Refusing an
     * id that is not already a safe file name would drop those words silently
     * and for ever.
     */
    fun isPracticableId(id: String): Boolean = id.isNotBlank()

    /**
     * The id as it appears in a file name: every character outside
     * `[A-Za-z0-9._-]` becomes `_`, the result is cut to [MAX_ID_LENGTH] and a
     * leading dot is replaced so no attempt is ever written hidden. Blank
     * becomes `word`.
     *
     * Only the two attempt file names are spelt this way, and they are both
     * built from this one function, so they always agree — which is all the
     * coach needs, because it pairs them by identical stem and reads the real
     * id out of the sidecar's JSON. The clip name and the `results.json` key
     * belong to the coach and are never rewritten by the app.
     */
    fun fileId(id: String): String {
        val mapped = buildString(id.length) {
            for (c in id) append(if (c.isAsciiIdChar()) c else '_')
        }
        val cut = mapped.take(MAX_ID_LENGTH)
        if (cut.isEmpty()) return "word"
        return if (cut.startsWith(".")) "_" + cut.substring(1) else cut
    }

    private fun Char.isAsciiIdChar(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '.' || this == '_' || this == '-'

    /**
     * The shared stem of an attempt's two files: `20260913T180402Z_imperialist`.
     * [id] is the coach's raw id; it is put through [fileId] here so every
     * caller gets the same spelling.
     */
    fun stem(ts: String, id: String): String = ts + "_" + fileId(id)

    /** `sayit/attempts/<ts>_<id>.m4a`. */
    fun audioName(ts: String, id: String): String = stem(ts, id) + AUDIO_EXTENSION

    /** `sayit/attempts/<ts>_<id>.json`. */
    fun sidecarName(ts: String, id: String): String = stem(ts, id) + SIDECAR_EXTENSION

    /**
     * The `<ts>` of an attempt file name, or null when the name is not one of
     * ours. The timestamp must name a real instant and not merely have the
     * right shape, so `20260931T180402Z` (no 31st of September) reads as null.
     */
    fun timestampOf(fileName: String): String? = parts(fileName)?.ts

    /** The `<id>` of an attempt file name, or null when the name is not one of ours. */
    fun idOf(fileName: String): String? = parts(fileName)?.id

    /** Whether [fileName] is an attempt recording this app wrote (`<ts>_<id>.m4a`). */
    fun isAttemptAudio(fileName: String): Boolean =
        fileName.endsWith(AUDIO_EXTENSION) && parts(fileName) != null

    /** Whether [fileName] is an attempt sidecar this app wrote (`<ts>_<id>.json`). */
    fun isAttemptSidecar(fileName: String): Boolean =
        fileName.endsWith(SIDECAR_EXTENSION) && parts(fileName) != null

    /**
     * The ISO form of a basic timestamp: `20260913T180402Z` becomes
     * `2026-09-13T18:04:02Z`. Null when [ts] names no real instant.
     *
     * This is the join between the two halves of the retention rule: the file
     * name carries the basic form and `results.json` carries the same second
     * in ISO form, as `attempts[].at`.
     */
    fun isoOf(ts: String): String? {
        if (!STAMP.matches(ts)) return null
        val iso = ts.substring(0, 4) + "-" + ts.substring(4, 6) + "-" + ts.substring(6, 8) + "T" +
            ts.substring(9, 11) + ":" + ts.substring(11, 13) + ":" + ts.substring(13, 15) + "Z"
        return if (TimeUtil.parseIso(iso) != null) iso else null
    }

    /** The instant a basic timestamp names, or null when it names none. */
    fun instantOf(ts: String): Instant? = isoOf(ts)?.let { TimeUtil.parseIso(it) }

    /** The basic timestamp of [instant], truncated to the second. */
    fun stampOf(instant: Instant): String = TimeUtil.sessionIdFrom(instant)

    /**
     * The first second at or after [started] whose attempt stem is free.
     *
     * Two attempts can land in one second and the wall clock can step back;
     * either way the stem must be new, because the coach treats an attempt as
     * one audio file and one sidecar sharing a stem. Moving the second on
     * rather than the name keeps `started` and `<ts>` equal, which is what the
     * contract requires. [taken] is asked about the stem, so one question
     * covers both files.
     */
    fun freeStart(started: Instant, id: String, taken: (String) -> Boolean): Instant {
        var t = started.truncatedTo(ChronoUnit.SECONDS)
        var guard = 0
        while (taken(stem(stampOf(t), id)) && guard++ < BUMP_GUARD) t = t.plusSeconds(1)
        return t
    }

    /**
     * Split an attempt file name into its timestamp and its id. Null unless
     * both halves are ones this app could have written. The separator is the
     * first underscore, because the timestamp never contains one and an id may.
     */
    private fun parts(fileName: String): Parts? {
        // Only the two extensions this app writes. Anything else in the folder
        // is somebody's file, not an attempt of ours: a `.ogg` would otherwise
        // parse with the extension swallowed into the id, which is allowed to
        // contain a dot, and the sweep would think it had found an attempt.
        val stem = when {
            fileName.endsWith(AUDIO_EXTENSION) -> fileName.dropLast(AUDIO_EXTENSION.length)
            fileName.endsWith(SIDECAR_EXTENSION) -> fileName.dropLast(SIDECAR_EXTENSION.length)
            else -> return null
        }
        val cut = stem.indexOf('_')
        if (cut <= 0) return null
        val ts = stem.substring(0, cut)
        val id = stem.substring(cut + 1)
        if (isoOf(ts) == null || !isUsableId(id)) return null
        return Parts(ts, id)
    }
}
