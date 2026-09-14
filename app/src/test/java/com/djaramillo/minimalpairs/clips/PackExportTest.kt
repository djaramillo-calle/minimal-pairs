package com.djaramillo.minimalpairs.clips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision that keeps the clip pack from being rendered twice: publish it
 * to the data folder, take it back from there, or leave both alone.
 *
 * A full render is 11,646 clips and some 70,000 characters of neural TTS — a
 * sixth of the free Azure tier's month — and `filesDir/clips/` is wiped on
 * uninstall. Every case below is one of the ways that bill could be paid
 * again, or one of the ways a useless archive could end up in the folder
 * looking like the real thing.
 */
class PackExportTest {
    private val voices = listOf("en-GB-SoniaNeural", "en-GB-RyanNeural")
    private val catalogWords = setOf("ship", "sheep", "thin", "tin")

    private fun index(
        words: List<String> = catalogWords.toList(),
        complete: Boolean = true,
        catalog: String = CATALOG,
        voices: List<String> = this.voices,
    ) = ClipIndex(catalogVersion = catalog, voices = voices, words = words, complete = complete)

    /** The bundled subset every build carries: two contrasts, `complete: false`. */
    private val bundled = index(words = listOf("thin", "tin"), complete = false)

    // ---- what may be published ------------------------------------------

    @Test
    fun a_complete_pack_is_worth_publishing() {
        assertNull(PackExport.refuseExport(index(), catalogWords))
    }

    /** A render that was cancelled half way is not published: `complete` is false. */
    @Test
    fun a_partial_render_is_never_published() {
        val half = index(words = listOf("ship", "sheep"), complete = false)
        assertNotNull(PackExport.refuseExport(half, catalogWords))
        assertTrue(PackExport.refuseExport(half, catalogWords)!!.contains("partial"))
        assertEquals(
            PackExport.Action.NOTHING,
            PackExport.decide(half, CATALOG, catalogWords, folderHasZip = false, exportedVersion = null),
        )
    }

    /** Nothing rendered at all: the bundled subset is not ours to publish. */
    @Test
    fun nothing_to_publish_without_a_rendered_pack() {
        assertNotNull(PackExport.refuseExport(null, catalogWords))
        assertEquals(
            PackExport.Action.NOTHING,
            PackExport.decide(null, CATALOG, catalogWords, folderHasZip = false, exportedVersion = null),
        )
    }

    /**
     * A pack that says `complete` about the catalog it was rendered for, but
     * does not cover this one. Publishing it would put an archive in the folder
     * that the next install refuses — the worst outcome of all, because it
     * looks like the pack is safe when it is not.
     */
    @Test
    fun a_pack_that_does_not_cover_this_catalog_is_not_published() {
        val older = index(words = listOf("ship", "sheep"), catalog = "2026-01-01.1")
        val reason = PackExport.refuseExport(older, catalogWords)
        assertNotNull(reason)
        assertTrue(reason!!.contains("missing 2 of the catalog's 4 words"))
    }

    /** A pack rendered for another catalog that does cover this one is still the pack. */
    @Test
    fun an_older_catalogs_pack_that_covers_this_one_is_published() {
        assertNull(PackExport.refuseExport(index(catalog = "2026-01-01.1"), catalogWords))
    }

    @Test
    fun a_pack_with_no_voices_is_not_published() {
        assertNotNull(PackExport.refuseExport(index(voices = emptyList()), catalogWords))
    }

    /** With no catalog loaded nothing is published and nothing is installed. */
    @Test
    fun without_a_catalog_nothing_happens() {
        assertNotNull(PackExport.refuseExport(index(), emptySet()))
        assertEquals(
            PackExport.Action.NOTHING,
            PackExport.decide(index(), CATALOG, emptySet(), folderHasZip = true, exportedVersion = null),
        )
        assertEquals(
            PackExport.Action.NOTHING,
            PackExport.decide(null, CATALOG, emptySet(), folderHasZip = true, exportedVersion = null),
        )
        assertEquals(
            PackExport.Action.NOTHING,
            PackExport.decide(index(), "", catalogWords, folderHasZip = false, exportedVersion = null),
        )
    }

    // ---- the launch decision --------------------------------------------

    /** The case this whole change exists for: he has the pack, the folder has not. */
    @Test
    fun a_complete_pack_the_folder_has_not_got_is_exported() {
        assertEquals(
            PackExport.Action.EXPORT,
            PackExport.decide(index(), CATALOG, catalogWords, folderHasZip = false, exportedVersion = null),
        )
    }

    /** Once published, never again for that catalog version: it is a 60 MB upload. */
    @Test
    fun the_export_does_not_repeat_once_it_has_succeeded() {
        assertEquals(
            PackExport.Action.NOTHING,
            PackExport.decide(index(), CATALOG, catalogWords, folderHasZip = true, exportedVersion = CATALOG),
        )
    }

    /** A new catalog means a new pack: the marker from the old one does not count. */
    @Test
    fun a_new_catalog_version_is_exported_again() {
        assertEquals(
            PackExport.Action.EXPORT,
            PackExport.decide(index(), CATALOG, catalogWords, folderHasZip = true, exportedVersion = "2026-01-01.1"),
        )
    }

    /**
     * The marker says published but the zip is gone — the folder is a two-way
     * sync and things do disappear from it. Put it back.
     */
    @Test
    fun a_zip_that_vanished_from_the_folder_is_exported_again() {
        assertEquals(
            PackExport.Action.EXPORT,
            PackExport.decide(index(), CATALOG, catalogWords, folderHasZip = false, exportedVersion = CATALOG),
        )
    }

    /** Fresh install: no pack, a zip in the folder. Install it; do not render. */
    @Test
    fun a_fresh_install_takes_the_pack_from_the_folder() {
        assertEquals(
            PackExport.Action.IMPORT,
            PackExport.decide(null, CATALOG, catalogWords, folderHasZip = true, exportedVersion = null),
        )
    }

    /** Only the bundled placeholder installed, and a zip in the folder: same. */
    @Test
    fun a_placeholder_pack_is_replaced_from_the_folder() {
        val placeholder = index(words = listOf("thin"), complete = false)
        assertEquals(
            PackExport.Action.IMPORT,
            PackExport.decide(placeholder, CATALOG, catalogWords, folderHasZip = true, exportedVersion = null),
        )
    }

    @Test
    fun no_pack_and_no_zip_is_nothing_at_all() {
        assertEquals(
            PackExport.Action.NOTHING,
            PackExport.decide(null, CATALOG, catalogWords, folderHasZip = false, exportedVersion = null),
        )
    }

    // ---- judging the folder's archive ------------------------------------

    private fun refuse(candidate: ClipIndex?, current: MergedIndex = MergedIndex(bundled, null)) =
        PackExport.refuseFolderZip(candidate, bundled, current, CATALOG, catalogWords)

    /** A matching pack installs — and [DownloadCheck] is what says so, not a second rule. */
    @Test
    fun a_matching_folder_zip_is_accepted() {
        assertNull(refuse(index()))
    }

    @Test
    fun a_folder_zip_for_a_stale_catalog_is_refused() {
        // Rendered for another catalog, and short of this one's words even with
        // the bundled subset beside it. Installing it would drop coverage.
        val stale = index(words = listOf("ship"), catalog = "2026-01-01.1")
        val reason = refuse(stale)
        assertNotNull("a pack for another catalog that lacks this one's words must not install", reason)
        assertTrue(reason!!.contains("rendered for catalog 2026-01-01.1"))
    }

    /**
     * The other side of the same rule, and the reason it is [DownloadCheck]'s
     * and not a stricter one of our own: a pack whose `catalog_version` string
     * is old but which still covers every word this catalog drills is usable,
     * and refusing it would send him back to Azure for nothing.
     */
    @Test
    fun a_folder_zip_that_still_covers_this_catalog_is_accepted() {
        assertNull(refuse(index(catalog = "2026-01-01.1")))
    }

    /** A pack that would cover fewer words than what is installed is refused. */
    @Test
    fun a_folder_zip_that_would_lose_coverage_is_refused() {
        val complete = index()
        val current = MergedIndex(bundled, complete)
        val reason = PackExport.refuseFolderZip(
            index(words = listOf("ship"), catalog = CATALOG), bundled, current, CATALOG, catalogWords,
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("fewer than the installed"))
    }

    @Test
    fun a_placeholder_in_the_folder_never_replaces_a_pack() {
        assertNotNull(refuse(index(words = listOf("thin"), complete = false)))
        assertNotNull(refuse(null))
    }

    /** Same rule as a download, asked the same way. */
    @Test
    fun it_is_the_download_check_and_not_a_second_one() {
        val candidate = index()
        val current = MergedIndex(bundled, null)
        assertEquals(
            DownloadCheck.refuse(candidate, bundled, current, CATALOG, catalogWords) { _, _ -> true },
            PackExport.refuseFolderZip(candidate, bundled, current, CATALOG, catalogWords),
        )
    }

    private companion object {
        const val CATALOG = "2026-09-11.2"
    }
}
