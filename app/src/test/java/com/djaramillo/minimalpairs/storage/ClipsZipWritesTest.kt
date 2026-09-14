package com.djaramillo.minimalpairs.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `clips.zip`: the one file the app writes to the data folder that is measured
 * in tens of megabytes, and the one whose loss costs money — a full render is
 * some 11,600 clips of neural TTS, and `filesDir/clips/` goes when the app is
 * uninstalled.
 *
 * Two things have to hold, and neither can be shown by calling the code: a
 * JVM unit test cannot open a SAF document (this project has no Robolectric).
 * So the rule itself is a pure function, tested directly, and this reads
 * [DataFolder]'s own source to check that the write path still obeys it — the
 * same approach, and for the same reason, as [SayItWritesTest].
 *
 * 1. A provider asked for a name that is taken makes `clips (1).zip` instead.
 *    That file is not the pack, nothing will ever read it, and reporting it as
 *    saved would cost him the whole 60 MB upload for nothing.
 * 2. The bytes go to a temporary name and are moved into place only once they
 *    are all there, so a crash or a sync mid-write never leaves a truncated
 *    `clips.zip` that fails validation on the one day it is needed.
 */
class ClipsZipWritesTest {
    private fun source(path: String): String {
        val file = listOf(File(path), File("app", path)).firstOrNull { it.isFile }
            ?: throw AssertionError("cannot find $path from ${File(".").absolutePath}")
        return file.readText()
    }

    private val dataFolder by lazy { source("src/main/java/com/djaramillo/minimalpairs/storage/DataFolder.kt") }
    private val packSync by lazy { source("src/main/java/com/djaramillo/minimalpairs/clips/PackSync.kt") }

    // ---- the collision rule, as a function -------------------------------

    @Test
    fun a_document_the_provider_renamed_is_not_the_one_we_asked_for() {
        assertTrue(FolderLayout.isNamed("clips.zip", "clips.zip"))
        assertFalse(FolderLayout.isNamed("clips.zip", "clips (1).zip"))
        assertFalse(FolderLayout.isNamed("clips.zip", "clips.zip (1)"))
        assertFalse(FolderLayout.isNamed("clips.zip", "clips.zip.tmp"))
        assertFalse(FolderLayout.isNamed("clips.zip.tmp", "clips.zip.tmp (1)"))
        assertTrue(FolderLayout.isNamed("clips.zip.tmp", "clips.zip.tmp"))
    }

    /** A provider that will not say what it made is not evidence of a collision. */
    @Test
    fun a_provider_that_gives_no_name_is_taken_at_its_word() {
        assertTrue(FolderLayout.isNamed("clips.zip", null))
    }

    @Test
    fun the_temporary_name_is_not_the_live_name() {
        assertEquals("clips.zip", FolderLayout.CLIPS_ZIP)
        assertEquals("clips.zip.tmp", FolderLayout.CLIPS_ZIP_TMP)
        assertFalse(FolderLayout.CLIPS_ZIP == FolderLayout.CLIPS_ZIP_TMP)
    }

    // ---- the write path still obeys it -----------------------------------

    @Test
    fun the_only_document_created_for_the_pack_is_the_temporary_one() {
        assertTrue(
            "clips.zip must be created under its temporary name",
            dataFolder.contains("createFile(MIME_BINARY, FolderLayout.CLIPS_ZIP_TMP)"),
        )
        assertFalse(
            "clips.zip must never be created directly: it is renamed into place",
            dataFolder.contains("createFile(MIME_BINARY, FolderLayout.CLIPS_ZIP)"),
        )
    }

    @Test
    fun both_the_temporary_document_and_the_moved_one_are_checked_for_a_collision() {
        assertTrue(dataFolder.contains("FolderLayout.isNamed(FolderLayout.CLIPS_ZIP_TMP, doc.name)"))
        assertTrue(dataFolder.contains("FolderLayout.isNamed(FolderLayout.CLIPS_ZIP, landed.name)"))
    }

    /**
     * The order that makes the temporary name worth having: fill it, then move
     * it. If these ever swapped, a killed export would leave a short
     * `clips.zip` behind.
     */
    @Test
    fun the_move_happens_after_the_write_and_not_before() {
        val body = dataFolder.substringAfter("suspend fun writeClipsZip").substringBefore("\n    /** `clips.zip` in the folder root")
        val made = body.indexOf("makeClipsTemp(root)")
        val filled = body.indexOf("openWrite(tmp.uri)")
        val moved = body.indexOf("swapClipsZipIn(root, tmp)")
        assertTrue("makeClipsTemp not found", made >= 0)
        assertTrue("the write is missing", filled > made)
        assertTrue("the move must come after the write", moved > filled)
        // And a write that did not finish drops the temporary document instead.
        assertTrue(body.contains("if (!filled) {"))
        assertTrue(body.contains("drop(tmp)"))
    }

    /** Both short steps are under the one-writer lock; the long copy is not. */
    @Test
    fun the_name_steps_are_locked() {
        assertTrue(dataFolder.contains("folderLock.withLock { makeClipsTemp(root) }"))
        assertTrue(dataFolder.contains("folderLock.withLock { swapClipsZipIn(root, tmp) }"))
    }

    /** A failure is reported, not swallowed: an unwritable pack must be visible. */
    @Test
    fun a_failure_in_the_writer_is_raised_not_hidden() {
        assertTrue(dataFolder.contains("failure?.let { throw it }"))
    }

    // ---- what the sync may and may not do --------------------------------

    /**
     * Rendering stays a deliberate Settings action. Nothing in the automatic
     * path may reach Azure — that is the entire bill this change is avoiding.
     */
    @Test
    fun the_automatic_sync_never_renders_and_never_calls_azure() {
        for (forbidden in listOf("PackRenderer", "renderer", "AzureTts", "azureKey", "AzureAssessor")) {
            assertFalse(
                "PackSync must not mention $forbidden: rendering is never automatic",
                packSync.contains(forbidden),
            )
        }
    }

    /** Installing goes through the downloader's unpack, not a second one. */
    @Test
    fun installing_reuses_the_downloaders_path() {
        assertTrue(packSync.contains("downloader.startFromLocalCopy("))
        for (forbidden in listOf("ZipInputStream", "ZipOutputStream", "deleteRecursively", "renameTo")) {
            assertFalse(
                "PackSync must not unpack or swap anything itself ($forbidden)",
                packSync.contains(forbidden),
            )
        }
    }

    /** And the decision to accept a folder archive is DownloadCheck's, once. */
    @Test
    fun the_validator_is_not_duplicated() {
        val packExport = source("src/main/java/com/djaramillo/minimalpairs/clips/PackExport.kt")
        assertTrue(packExport.contains("DownloadCheck.refuse("))
        assertEquals(1, Regex("DownloadCheck\\.refuse\\(").findAll(packExport).count())
        // PackSync names it in its documentation and never calls it itself.
        assertFalse(packSync.contains("DownloadCheck.refuse("))
    }

    /** The key never goes near the folder. The repository is public; so is the folder's Drive copy. */
    @Test
    fun nothing_about_the_azure_key_reaches_the_data_folder() {
        for (forbidden in listOf("azureKey", "azureRegion", "AzureTts", "AZURE")) {
            assertFalse("DataFolder must not touch $forbidden", dataFolder.contains(forbidden))
        }
    }
}
