package com.djaramillo.minimalpairs.domain

/**
 * What may come out of `sayit.zip`, and what may not (docs/CONTRACT.md,
 * "`sayit.zip`").
 *
 * The zip is the whole coach → app payload, in one file because the Drive
 * service account has no storage quota and can only PATCH a file that already
 * exists. It arrives through a two-way sync from a cloud service, so the app
 * treats it as untrusted input rather than as something it wrote itself: an
 * entry is unpacked only if this object says its name is one of the three
 * shapes the contract allows, and the caller stops at the size caps below.
 *
 * The rule is an allow-list, not a list of things to strip. Sanitising a bad
 * name invents a file the coach never meant to send; refusing it cannot.
 *
 * No Android types, unit tested on the JVM.
 */
object SayItZip {

    /** The two JSON entries, at the zip root. */
    const val WORDS = "words.json"
    const val RESULTS = "results.json"

    /** Model clips live directly under this prefix and nowhere else. */
    const val CLIPS = "clips/"

    /** Model clips are Ogg/Opus. */
    const val CLIP_EXTENSION = ".ogg"

    /**
     * Caps. A zip is a few hundred entries of about 60 KB, so these are far
     * above anything real and only stop a corrupt or hostile file from filling
     * the phone: a zip bomb declares little and expands without end, and the
     * caller must count what it has actually written rather than trust the
     * header.
     */
    const val MAX_ENTRIES = 2_000
    const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 96L * 1024 * 1024

    /** The whole zip the app is willing to copy out of the folder at all. */
    const val MAX_ZIP_BYTES = 64L * 1024 * 1024

    /**
     * Whether [name], exactly as the zip declares it, is an entry the app will
     * unpack: `words.json`, `results.json`, or `clips/<something>.ogg` with
     * nothing below it.
     *
     * Everything else is refused, and deliberately in that order: an absolute
     * path, a `..` or `.` segment, a backslash (a separator on the writer's
     * platform and a legal name character here, so a name carrying one is
     * ambiguous and not worth resolving), a colon (Android external storage and
     * Drive reject it), a control character, a directory entry, a clip nested
     * deeper than one level, and a clip whose name is empty or hidden.
     */
    fun accepts(name: String): Boolean {
        if (name.isEmpty() || name.length > 255) return false
        if (name.startsWith("/") || name.endsWith("/")) return false
        if (name.contains('\\') || name.contains(':')) return false
        if (name.any { it.isISOControl() }) return false
        if (name.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
        if (name == WORDS || name == RESULTS) return true
        if (!name.startsWith(CLIPS)) return false
        val child = name.substring(CLIPS.length)
        if (child.contains('/')) return false
        if (child.startsWith(".") || child.length <= CLIP_EXTENSION.length) return false
        return child.endsWith(CLIP_EXTENSION)
    }

    /** The clip file name of an accepted `clips/<name>.ogg` entry, else null. */
    fun clipName(name: String): String? =
        if (accepts(name) && name.startsWith(CLIPS)) name.substring(CLIPS.length) else null
}
