package com.djaramillo.minimalpairs.clips

/**
 * What to do about the clip pack and the data folder's `clips.zip`, decided
 * before anything is read or written. Pure (no Android, no I/O) so the whole
 * table is unit tested; [PackSync] carries the decision out.
 *
 * The point of all of it: a full render is 11,646 clips and some 70,000
 * characters of neural TTS, a sixth of the free Azure tier's month, and
 * `filesDir/clips/` is wiped when the app is uninstalled. Rendering must
 * happen once. So a complete pack is published to the folder the app already
 * shares with the coach, and a fresh install takes it back from there instead
 * of calling Azure.
 */
object PackExport {
    enum class Action {
        /** Nothing to do: either the pack and the folder already agree, or neither can help the other. */
        NOTHING,

        /** The app holds a complete pack the folder has not got: publish it. */
        EXPORT,

        /** The app has no usable pack and the folder has a `clips.zip`: install that. */
        IMPORT,
    }

    /**
     * Null when the pack in `filesDir/clips/` is worth publishing, else why not.
     *
     * Only the downloaded/rendered pack counts, never the bundled subset: the
     * archive has to stand on its own on the far side of a reinstall. It must
     * say `complete` **and** actually cover every trainable word of the
     * installed catalog — `complete` is a claim about the catalog it was
     * rendered for, which may not be this one.
     */
    fun refuseExport(downloaded: ClipIndex?, catalogWords: Set<String>): String? {
        if (catalogWords.isEmpty()) return "the catalog has no trainable words"
        if (downloaded == null) return "there is no rendered or downloaded pack"
        if (!downloaded.complete) return "the pack is partial (${downloaded.words.size} words, complete = false)"
        if (downloaded.voices.isEmpty()) return "the pack has no voices"
        val missing = catalogWords.count { it !in downloaded.wordSet }
        if (missing > 0) return "the pack is missing $missing of the catalog's ${catalogWords.size} words"
        return null
    }

    /**
     * Whether the folder's `clips.zip` is one this app could install, judged by
     * its `index.json` alone.
     *
     * This is [DownloadCheck.refuse] — the same rule that stops the CI
     * placeholder replacing a real pack — asked without the archive in hand, so
     * the files it would check are taken on trust here and checked for real
     * when [ClipDownloader] unpacks it. Null means "worth the copy", and it is
     * also what says a folder copy is current enough that a complete pack need
     * not be written over it.
     */
    fun refuseFolderZip(
        candidate: ClipIndex?,
        bundled: ClipIndex?,
        current: MergedIndex,
        catalogVersion: String,
        catalogWords: Set<String>,
    ): String? = DownloadCheck.refuse(candidate, bundled, current, catalogVersion, catalogWords) { _, _ -> true }

    /**
     * The one decision made on launch.
     *
     * [exportedVersion] is the catalog version the app last published a
     * `clips.zip` for (null when it never has). [folderHasZip] is whether the
     * folder holds a `clips.zip` at all, by name only — its contents are not
     * read unless this says [Action.EXPORT] or [Action.IMPORT], because reading
     * them means scanning tens of megabytes.
     *
     * With no catalog to judge against, nothing happens at all.
     *
     * A pack that is already published stays published: the export is not
     * repeated for the same catalog version. The `clips.zip` going missing from
     * the folder (the coach's two-way sync can take it away) is the one thing
     * that puts the export back on, which is why presence is checked even when
     * the marker matches.
     */
    fun decide(
        downloaded: ClipIndex?,
        catalogVersion: String,
        catalogWords: Set<String>,
        folderHasZip: Boolean,
        exportedVersion: String?,
    ): Action {
        // No catalog, no decision: with nothing to judge a pack against, an
        // install could accept anything and an export could publish anything.
        if (catalogWords.isEmpty() || catalogVersion.isEmpty()) return Action.NOTHING
        val holdsCompletePack = refuseExport(downloaded, catalogWords) == null
        return when {
            !holdsCompletePack -> if (folderHasZip) Action.IMPORT else Action.NOTHING
            folderHasZip && exportedVersion == catalogVersion -> Action.NOTHING
            else -> Action.EXPORT
        }
    }
}
