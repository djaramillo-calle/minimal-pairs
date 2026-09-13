package com.djaramillo.minimalpairs.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The naming rules of docs/CONTRACT.md, "`sayit/`". These names become files in
 * the learner's synced folder, so the rejection cases matter as much as the
 * happy path.
 */
class SayItNamesTest {

    private val ts = "20260913T180402Z"
    private val iso = "2026-09-13T18:04:02Z"

    // ---- ids ------------------------------------------------------------

    @Test
    fun plainIdsAreUsable() {
        assertTrue(SayItNames.isUsableId("imperialist"))
        assertTrue(SayItNames.isUsableId("A"))
        assertTrue(SayItNames.isUsableId("0"))
        assertTrue(SayItNames.isUsableId("a.b-c_d"))
        assertTrue(SayItNames.isUsableId("word.2"))
        assertTrue(SayItNames.isUsableId("-leading-dash"))
        assertTrue(SayItNames.isUsableId("_leading_underscore"))
        assertTrue(SayItNames.isUsableId("a".repeat(64)))
    }

    @Test
    fun idsThatCouldNotBeFileNamesAreRejected() {
        assertFalse("empty", SayItNames.isUsableId(""))
        assertFalse("path separator", SayItNames.isUsableId("a/b"))
        assertFalse("backslash", SayItNames.isUsableId("a\\b"))
        assertFalse("parent", SayItNames.isUsableId(".."))
        assertFalse("current", SayItNames.isUsableId("."))
        assertFalse("hidden", SayItNames.isUsableId(".hidden"))
        assertFalse("leading dot", SayItNames.isUsableId("..imperialist"))
        assertFalse("too long", SayItNames.isUsableId("a".repeat(65)))
        assertFalse("space", SayItNames.isUsableId("two words"))
        assertFalse("colon", SayItNames.isUsableId("a:b"))
        assertFalse("non ascii", SayItNames.isUsableId("café"))
        assertFalse("newline", SayItNames.isUsableId("a\nb"))
        assertFalse("wildcard", SayItNames.isUsableId("*"))
    }

    // ---- attempt names --------------------------------------------------

    @Test
    fun stemAndNamesAreBuiltFromTimestampAndId() {
        assertEquals("20260913T180402Z_imperialist", SayItNames.stem(ts, "imperialist"))
        assertEquals("20260913T180402Z_imperialist.m4a", SayItNames.audioName(ts, "imperialist"))
        assertEquals("20260913T180402Z_imperialist.json", SayItNames.sidecarName(ts, "imperialist"))
    }

    @Test
    fun nameBuildAndParseRoundTrip() {
        for (id in listOf("imperialist", "a", "a.b-c_d", "a".repeat(64))) {
            val audio = SayItNames.audioName(ts, id)
            val sidecar = SayItNames.sidecarName(ts, id)
            assertEquals(ts, SayItNames.timestampOf(audio))
            assertEquals(id, SayItNames.idOf(audio))
            assertEquals(ts, SayItNames.timestampOf(sidecar))
            assertEquals(id, SayItNames.idOf(sidecar))
            assertTrue(SayItNames.isAttemptAudio(audio))
            assertTrue(SayItNames.isAttemptSidecar(sidecar))
            assertFalse(SayItNames.isAttemptSidecar(audio))
            assertFalse(SayItNames.isAttemptAudio(sidecar))
        }
    }

    @Test
    fun anIdMayContainUnderscoresBecauseTheTimestampDoesNot() {
        val name = SayItNames.audioName(ts, "im_per_ialist")
        assertEquals(ts, SayItNames.timestampOf(name))
        assertEquals("im_per_ialist", SayItNames.idOf(name))
    }

    @Test
    fun foreignNamesAreNotOurs() {
        val strangers = listOf(
            "",
            "imperialist.m4a",
            "20260913T180402Z.m4a",
            "_imperialist.m4a",
            "20260913T180402Z_.m4a",
            "2026-09-13T18:04:02Z_imperialist.m4a",
            "20260913T1804Z_imperialist.m4a",
            "20260913T180402_imperialist.m4a",
            "20260913T180402Z_imperialist.ogg",
            "20260913T180402Z_imperialist.txt",
            "20260913T180402Z_../secret.m4a",
            "20260913T180402Z_.hidden.m4a",
            "20260913T180402Z_" + "a".repeat(65) + ".m4a",
        )
        for (name in strangers) {
            assertNull(name, SayItNames.timestampOf(name))
            assertNull(name, SayItNames.idOf(name))
            assertFalse(name, SayItNames.isAttemptAudio(name))
            assertFalse(name, SayItNames.isAttemptSidecar(name))
        }
    }

    @Test
    fun aTimestampMustNameARealInstant() {
        assertNull(SayItNames.timestampOf("20260931T180402Z_imperialist.m4a"))
        assertNull(SayItNames.timestampOf("20261301T180402Z_imperialist.m4a"))
        assertNull(SayItNames.timestampOf("20260913T256102Z_imperialist.m4a"))
        assertNull("2026 is not a leap year", SayItNames.timestampOf("20260229T000000Z_x.m4a"))
        assertEquals("20240229T000000Z", SayItNames.timestampOf("20240229T000000Z_x.m4a"))
    }

    // ---- timestamp conversion -------------------------------------------

    @Test
    fun basicAndIsoFormsRoundTrip() {
        assertEquals(iso, SayItNames.isoOf(ts))
        assertEquals(Instant.parse(iso), SayItNames.instantOf(ts))
        assertEquals(ts, SayItNames.stampOf(Instant.parse(iso)))
        assertEquals(ts, SayItNames.stampOf(SayItNames.instantOf(ts)!!))
        assertEquals(iso, TimeUtil.formatIso(SayItNames.instantOf(ts)!!))
        // Sub-second precision is dropped, as the contract's seconds precision requires.
        assertEquals(ts, SayItNames.stampOf(Instant.parse("2026-09-13T18:04:02.987Z")))
    }

    @Test
    fun malformedTimestampsConvertToNull() {
        for (bad in listOf("", "20260913T180402", "2026-09-13T18:04:02Z", "20260913180402Z", "20260931T180402Z")) {
            assertNull(bad, SayItNames.isoOf(bad))
            assertNull(bad, SayItNames.instantOf(bad))
        }
    }

    // ---- clip paths -----------------------------------------------------


    // ---- free stems -----------------------------------------------------

    @Test
    fun freeStartReturnsTheStartWhenTheStemIsFree() {
        val started = Instant.parse(iso)
        assertEquals(started, SayItNames.freeStart(started, "imperialist") { false })
    }

    @Test
    fun freeStartTruncatesToTheSecond() {
        val started = Instant.parse("2026-09-13T18:04:02.750Z")
        assertEquals(Instant.parse(iso), SayItNames.freeStart(started, "imperialist") { false })
    }

    @Test
    fun freeStartBumpsPastTakenStems() {
        val started = Instant.parse(iso)
        val taken = setOf(
            SayItNames.stem("20260913T180402Z", "imperialist"),
            SayItNames.stem("20260913T180403Z", "imperialist"),
            SayItNames.stem("20260913T180404Z", "imperialist"),
        )
        val free = SayItNames.freeStart(started, "imperialist") { it in taken }
        assertEquals(Instant.parse("2026-09-13T18:04:05Z"), free)
        // The name and the timestamp move together, which is the point of bumping.
        assertEquals("20260913T180405Z", SayItNames.stampOf(free))
        assertEquals("2026-09-13T18:04:05Z", TimeUtil.formatIso(free))
    }

    @Test
    fun freeStartOnlyCaresAboutTheSameWord() {
        val started = Instant.parse(iso)
        val taken = setOf(SayItNames.stem(ts, "other"))
        assertEquals(started, SayItNames.freeStart(started, "imperialist") { it in taken })
    }

    @Test
    fun freeStartGivesUpRatherThanLoopingForever() {
        val started = Instant.parse(iso)
        val free = SayItNames.freeStart(started, "imperialist") { true }
        assertTrue("the guard must stop the loop", free.isAfter(started))
    }

    // ---- fileId: the coach's id, spelt so it can be a file name ----------

    @Test
    fun fileIdSpellsAnIdSafelyWithoutLosingTheWord() {
        assertEquals("don_t", SayItNames.fileId("don't"))
        assertEquals("people_s", SayItNames.fileId("people's"))
        assertEquals("caf_", SayItNames.fileId("café"))
        assertEquals("over_all", SayItNames.fileId("over all"))
        assertEquals("a_b", SayItNames.fileId("a/b"))
        assertEquals("a_b", SayItNames.fileId("a\\b"))
        assertEquals("a_b", SayItNames.fileId("a:b"))
        assertEquals("___", SayItNames.fileId("!!!"))
        // never hidden, never empty, never longer than a file name can hold
        assertEquals("_hidden", SayItNames.fileId(".hidden"))
        assertEquals("_.", SayItNames.fileId(".."))
        assertEquals("word", SayItNames.fileId(""))
        assertEquals("x".repeat(64), SayItNames.fileId("x".repeat(65)))
        // an id that is already a file name is left exactly as it is
        assertEquals("imperialist", SayItNames.fileId("imperialist"))
        assertEquals("a.b-c_d", SayItNames.fileId("a.b-c_d"))
    }

    @Test
    fun fileIdAlwaysProducesAUsableFileNameToken() {
        for (id in listOf("don't", "café", "a/b", "..", "", "!!!", "x".repeat(200), " ")) {
            assertTrue(id, SayItNames.isUsableId(SayItNames.fileId(id)))
        }
    }

    @Test
    fun bothAttemptNamesShareTheSanitisedStem() {
        val ts = "20260913T180402Z"
        assertEquals("20260913T180402Z_don_t.m4a", SayItNames.audioName(ts, "don't"))
        assertEquals("20260913T180402Z_don_t.json", SayItNames.sidecarName(ts, "don't"))
        // the coach pairs the two by identical stem, so they must never diverge
        assertEquals(
            SayItNames.audioName(ts, "don't").removeSuffix(".m4a"),
            SayItNames.sidecarName(ts, "don't").removeSuffix(".json"),
        )
        // and the name round-trips back out of the file name
        assertEquals("don_t", SayItNames.idOf(SayItNames.audioName(ts, "don't")))
        assertEquals(ts, SayItNames.timestampOf(SayItNames.sidecarName(ts, "don't")))
    }

    @Test
    fun onlyABlankIdIsUnpracticable() {
        assertTrue(SayItNames.isPracticableId("don't"))
        assertTrue(SayItNames.isPracticableId(".."))
        assertTrue(SayItNames.isPracticableId("a".repeat(200)))
        assertFalse(SayItNames.isPracticableId(""))
        assertFalse(SayItNames.isPracticableId("   "))
    }
}
