package com.djaramillo.minimalpairs.clips

import com.djaramillo.minimalpairs.domain.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Packing `filesDir/clips/` back into a `clips.zip`: the archive the app writes
 * to the data folder so the pack is rendered from Azure once and not once per
 * reinstall.
 *
 * The rule under nearly every test here: a `clips.zip` that cannot be
 * installed again is worse than none at all, because it sits in the folder
 * looking exactly like the pack he already paid for. So anything short of a
 * complete, in-bounds archive throws instead of being written.
 */
class ClipArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private val voices = listOf("en-GB-SoniaNeural", "en-GB-RyanNeural")

    private fun pack(words: List<String>, voices: List<String> = this.voices, complete: Boolean = true): File {
        val dir = temp.newFolder()
        for (v in voices) {
            File(dir, v).mkdirs()
            for (w in words) File(File(dir, v), "$w.webm").writeBytes(ByteArray(1600) { it.toByte() })
        }
        writeIndex(dir, ClipIndex(catalogVersion = CATALOG, voices = voices, words = words, complete = complete))
        return dir
    }

    private fun writeIndex(dir: File, index: ClipIndex) {
        File(dir, ZipRules.INDEX).writeText(AppJson.json.encodeToString(ClipIndex.serializer(), index))
    }

    private fun indexOf(dir: File): Pair<ClipIndex, ByteArray> {
        val bytes = File(dir, ZipRules.INDEX).readBytes()
        return AppJson.json.decodeFromString(ClipIndex.serializer(), bytes.toString(Charsets.UTF_8)) to bytes
    }

    private fun zip(dir: File, out: ByteArrayOutputStream = ByteArrayOutputStream()): ByteArrayOutputStream {
        val (index, bytes) = indexOf(dir)
        ClipArchive.write(dir, index, bytes, out)
        return out
    }

    private fun namesIn(zipBytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                out.add(e.name)
                zin.closeEntry()
            }
        }
        return out
    }

    @Test
    fun packs_the_index_first_and_every_clip_the_index_lists() {
        val dir = pack(listOf("ship", "sheep"))
        val (index, bytes) = indexOf(dir)
        val out = ByteArrayOutputStream()
        val result = ClipArchive.write(dir, index, bytes, out)

        val names = namesIn(out.toByteArray())
        assertEquals(ZipRules.INDEX, names.first())
        assertEquals(
            listOf(
                ZipRules.INDEX,
                "en-GB-SoniaNeural/ship.webm", "en-GB-SoniaNeural/sheep.webm",
                "en-GB-RyanNeural/ship.webm", "en-GB-RyanNeural/sheep.webm",
            ),
            names,
        )
        assertEquals(5, result.entries)
        assertEquals(bytes.size + 4 * 1600L, result.bytes)
    }

    /** Every entry of what we write must be one the unpacker will let back in. */
    @Test
    fun every_entry_is_one_the_unpacker_accepts() {
        val dir = pack(listOf("ship", "sheep"))
        for (name in namesIn(zip(dir).toByteArray())) {
            assertTrue("ZipRules refuses $name", ZipRules.judge(name, false) is ZipRules.Verdict.Accept)
        }
    }

    /** The index the archive carries is the one it was judged by, byte for byte. */
    @Test
    fun the_archive_carries_the_index_it_was_given() {
        val dir = pack(listOf("ship"))
        val (index, bytes) = indexOf(dir)
        val out = ByteArrayOutputStream()
        // A render rewriting index.json under us must not change what is packed.
        writeIndex(dir, ClipIndex(catalogVersion = "9999-01-01.1", voices = voices, words = listOf("ship", "later")))
        ClipArchive.write(dir, index, bytes, out)
        val inside = ClipArchive.readIndexJson(ByteArrayInputStream(out.toByteArray()))
        assertEquals(bytes.toString(Charsets.UTF_8), inside)
    }

    @Test
    fun sha256_is_carried_when_the_pack_has_one_and_not_invented_when_it_has_not() {
        val dir = pack(listOf("ship"))
        assertFalse(ZipRules.SHA256 in namesIn(zip(dir).toByteArray()))
        File(dir, ZipRules.SHA256).writeText("%064x  en-GB-SoniaNeural/ship.webm\n".format(1))
        assertTrue(ZipRules.SHA256 in namesIn(zip(dir).toByteArray()))
    }

    /** The leftovers of a killed render are not clips and are not packed. */
    @Test
    fun strays_in_the_pack_directory_are_left_out() {
        val dir = pack(listOf("ship"))
        File(File(dir, "en-GB-SoniaNeural"), "half.webm.part").writeBytes(ByteArray(99))
        File(dir, "index.json.tmp").writeText("{}")
        val names = namesIn(zip(dir).toByteArray())
        assertEquals(listOf(ZipRules.INDEX, "en-GB-SoniaNeural/ship.webm", "en-GB-RyanNeural/ship.webm"), names)
    }

    @Test
    fun a_missing_clip_fails_loudly_rather_than_shipping_a_short_pack() {
        val dir = pack(listOf("ship", "sheep"))
        assertTrue(File(File(dir, "en-GB-RyanNeural"), "sheep.webm").delete())
        val (index, bytes) = indexOf(dir)
        val e = try {
            ClipArchive.write(dir, index, bytes, ByteArrayOutputStream())
            null
        } catch (e: IOException) {
            e
        }
        assertNotNull("a pack missing a clip must not be written", e)
        assertTrue(e is ClipArchive.Incomplete)
        assertTrue(e!!.message!!.contains("en-GB-RyanNeural/sheep.webm"))
    }

    @Test
    fun an_empty_clip_counts_as_missing() {
        val dir = pack(listOf("ship"))
        File(File(dir, "en-GB-SoniaNeural"), "ship.webm").writeBytes(ByteArray(0))
        val (index, bytes) = indexOf(dir)
        try {
            ClipArchive.write(dir, index, bytes, ByteArrayOutputStream())
            throw AssertionError("an empty clip must not be packed as if it were there")
        } catch (e: ClipArchive.Incomplete) {
            assertTrue(e.message!!.contains("ship.webm"))
        }
    }

    /**
     * Nothing is written that the unpacker's own caps would then refuse, and
     * the count is taken from the index before a single file is opened — a
     * pack that could never be installed again must not be half written first.
     */
    @Test
    fun the_caps_the_unpacker_enforces_are_checked_before_writing() {
        val dir = pack(listOf("ship"))
        val (index, _) = indexOf(dir)
        val tooMany = index.copy(words = (1..ZipRules.MAX_ENTRIES).map { "word" })
        try {
            ClipArchive.entries(dir, tooMany)
            throw AssertionError("an archive over the entry cap must not be written")
        } catch (e: ClipArchive.TooBig) {
            assertTrue(e.message!!.contains("over the ${ZipRules.MAX_ENTRIES}"))
        }
        // Today's real packs are comfortably inside both caps: 1941 words in six
        // voices is 11,647 entries and 2521 (the release pack, production-only
        // words included) is 15,127, about 64 MB — against 30,000 and 200 MB.
        assertTrue(2521L * 6 + 2 < ZipRules.MAX_ENTRIES)
        assertTrue(70L * 1000 * 1000 < ZipRules.MAX_TOTAL_BYTES)
        assertEquals(2, ClipArchive.entries(dir, index).size)
    }

    @Test
    fun a_clip_over_the_entry_cap_is_refused() {
        val dir = pack(listOf("ship"))
        File(File(dir, "en-GB-SoniaNeural"), "ship.webm")
            .writeBytes(ByteArray((ZipRules.MAX_ENTRY_BYTES + 1).toInt()))
        val (index, _) = indexOf(dir)
        try {
            ClipArchive.entries(dir, index)
            throw AssertionError("a clip over the entry cap must not be written")
        } catch (e: ClipArchive.TooBig) {
            assertTrue(e.message!!.contains("ship.webm"))
        }
    }

    /** A name the unpacker would refuse must not be written in the first place. */
    @Test
    fun an_index_naming_something_unpackable_is_refused() {
        val dir = pack(listOf("ship"))
        val (index, _) = indexOf(dir)
        for (bad in listOf("../escape", "CON:x", "Word")) {
            try {
                ClipArchive.entries(dir, index.copy(words = listOf(bad)))
                throw AssertionError("'$bad' should never be packed")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("refuse") || e is ClipArchive.Incomplete)
            }
        }
    }

    /**
     * The whole point of the temp-then-move dance one level up: a write that
     * fails part way through leaves bytes behind, and they must never be
     * mistaken for an archive. Here the failure is a clip that goes missing
     * mid-write; the result is not a readable pack.
     */
    @Test
    fun a_write_that_fails_part_way_is_not_a_usable_archive() {
        val dir = pack(listOf("aa", "bb", "cc", "dd"))
        val (index, bytes) = indexOf(dir)
        val out = ByteArrayOutputStream()
        var packed = 0
        try {
            ClipArchive.write(dir, index, bytes, out) { done, _ ->
                packed = done
                // Something eats the pack half way through (a delete, a wipe).
                if (done == 3) File(dir, "en-GB-RyanNeural").deleteRecursively()
            }
            throw AssertionError("the write should have failed")
        } catch (e: IOException) {
            assertTrue(packed >= 3)
        }
        // Whatever reached the stream is a truncated zip, not a pack: reading it
        // back either breaks or comes up short. The caller drops it, still under
        // its temporary name, and the live clips.zip is untouched.
        val names = try {
            namesIn(out.toByteArray())
        } catch (e: Exception) {
            emptyList()
        }
        assertTrue(names.size < index.voices.size * index.words.size + 1)
    }

    @Test
    fun progress_counts_every_entry_including_the_index() {
        val dir = pack(listOf("ship", "sheep"))
        val (index, bytes) = indexOf(dir)
        val seen = ArrayList<Int>()
        var totalSeen = 0
        ClipArchive.write(dir, index, bytes, ByteArrayOutputStream()) { done, total ->
            seen.add(done); totalSeen = total
        }
        assertEquals(listOf(1, 2, 3, 4, 5), seen)
        assertEquals(5, totalSeen)
    }

    @Test
    fun progress_can_stop_the_write() {
        val dir = pack(listOf("ship", "sheep"))
        val (index, bytes) = indexOf(dir)
        try {
            ClipArchive.write(dir, index, bytes, ByteArrayOutputStream()) { done, _ ->
                if (done == 2) throw IOException("stop")
            }
            throw AssertionError("the callback should have stopped the write")
        } catch (e: IOException) {
            assertEquals("stop", e.message)
        }
    }

    // ---- reading an index back out --------------------------------------

    @Test
    fun reads_the_index_out_of_an_archive_whatever_its_position() {
        val text = """{"version":1,"catalog_version":"$CATALOG","complete":true}"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("en-GB-SoniaNeural/ship.webm")); z.write(ByteArray(2000)); z.closeEntry()
            z.putNextEntry(ZipEntry(ZipRules.INDEX)); z.write(text.toByteArray()); z.closeEntry()
        }
        assertEquals(text, ClipArchive.readIndexJson(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun reads_the_index_through_a_clips_prefix() {
        val text = """{"version":1,"catalog_version":"$CATALOG"}"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("clips/index.json")); z.write(text.toByteArray()); z.closeEntry()
        }
        assertEquals(text, ClipArchive.readIndexJson(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun no_index_no_answer() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("en-GB-SoniaNeural/ship.webm")); z.write(ByteArray(10)); z.closeEntry()
        }
        assertNull(ClipArchive.readIndexJson(ByteArrayInputStream(out.toByteArray())))
        assertNull(ClipArchive.readIndexJson(ByteArrayInputStream("not a zip at all".toByteArray())))
        assertNull(ClipArchive.readIndexJson(ByteArrayInputStream(ByteArray(0))))
    }

    /** A long archive with no index must not hold the launch open for ever. */
    @Test
    fun the_scan_gives_up_at_the_cap() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("en-GB-SoniaNeural/ship.webm")); z.write(ByteArray(4096)); z.closeEntry()
            z.putNextEntry(ZipEntry(ZipRules.INDEX)); z.write("{}".toByteArray()); z.closeEntry()
        }
        assertNull(ClipArchive.readIndexJson(ByteArrayInputStream(out.toByteArray()), maxScanBytes = 1024))
        assertEquals("{}", ClipArchive.readIndexJson(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun what_is_written_reads_back_as_the_same_pack() {
        val dir = pack(listOf("ship", "sheep", "bit"))
        val (index, _) = indexOf(dir)
        val bytes = zip(dir).toByteArray()
        val back = AppJson.json.decodeFromString(
            ClipIndex.serializer(),
            ClipArchive.readIndexJson(ByteArrayInputStream(bytes))!!,
        )
        assertEquals(index, back)
        val names = namesIn(bytes).toSet()
        for (v in index.voices) for (w in index.words) assertTrue("$v/$w.webm" in names)
    }

    private companion object {
        const val CATALOG = "2026-09-11.2"
    }
}
