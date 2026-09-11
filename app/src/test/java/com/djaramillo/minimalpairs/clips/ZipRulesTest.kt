package com.djaramillo.minimalpairs.clips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipRulesTest {
    private fun accept(name: String) = ZipRules.judge(name, false) as ZipRules.Verdict.Accept
    private fun reject(name: String) = ZipRules.judge(name, false) as ZipRules.Verdict.Reject

    @Test
    fun accepts_the_pack_layout() {
        assertEquals("index.json", accept("index.json").relativePath)
        assertEquals("sha256.txt", accept("sha256.txt").relativePath)
        assertEquals("en-GB-SoniaNeural/ship.webm", accept("en-GB-SoniaNeural/ship.webm").relativePath)
    }

    @Test
    fun strips_dot_slash_and_clips_prefix() {
        assertEquals("index.json", accept("./index.json").relativePath)
        assertEquals("en-GB-RyanNeural/sheep.webm", accept("clips/en-GB-RyanNeural/sheep.webm").relativePath)
        assertEquals("en-GB-RyanNeural/sheep.webm", accept("./clips/en-GB-RyanNeural/sheep.webm").relativePath)
    }

    @Test
    fun skips_directories() {
        assertEquals(ZipRules.Verdict.Skip, ZipRules.judge("en-GB-SoniaNeural/", true))
        assertEquals(ZipRules.Verdict.Skip, ZipRules.judge("clips/", true))
    }

    @Test
    fun rejects_traversal_and_absolute_paths() {
        assertTrue(reject("../evil.webm").reason.contains("traversal"))
        assertTrue(reject("en-GB-SoniaNeural/../../x.webm").reason.contains("traversal"))
        assertTrue(reject("/etc/passwd").reason.contains("absolute"))
        assertTrue(reject("a/./b.webm").reason.contains("traversal"))
        assertTrue(reject("C:/x.webm").reason.isNotEmpty())
        assertTrue(reject("a//b.webm").reason.contains("empty"))
    }

    @Test
    fun rejects_unexpected_files() {
        assertTrue(reject("README.md").reason.contains("top-level"))
        assertTrue(reject("en-GB-SoniaNeural/ship.mp3").reason.contains("webm"))
        assertTrue(reject("en-GB-SoniaNeural/Ship.webm").reason.contains("clip name"))
        assertTrue(reject("en-GB-SoniaNeural/ship's.webm").reason.contains("clip name"))
        assertTrue(reject("a/b/c.webm").reason.contains("depth"))
        assertTrue(reject("").reason.contains("empty"))
        assertTrue(reject("bad\u0000name.webm").reason.contains("control"))
        assertTrue(reject("bad voice/ship.webm").reason.contains("voice"))
    }

    @Test
    fun backslashes_are_normalised() {
        assertEquals("en-GB-SoniaNeural/ship.webm", accept("en-GB-SoniaNeural\\ship.webm").relativePath)
        assertTrue(reject("..\\x.webm").reason.contains("traversal"))
    }

    @Test
    fun caps_are_sane() {
        assertEquals(200L * 1024 * 1024, ZipRules.MAX_TOTAL_BYTES)
        assertTrue(ZipRules.MAX_ENTRY_BYTES < ZipRules.MAX_TOTAL_BYTES)
    }

    @Test
    fun parses_sha256_manifest() {
        val hex = "a".repeat(64)
        val text = """
            $hex  en-GB-SoniaNeural/ship.webm
            ${"B".repeat(64)} *en-GB-RyanNeural/sheep.webm
            not a line
            ${"c".repeat(63)}  short.webm
            # comment
        """.trimIndent()
        val m = ZipRules.parseSha256(text)
        assertEquals(2, m.size)
        assertEquals(hex, m["en-GB-SoniaNeural/ship.webm"])
        assertEquals("b".repeat(64), m["en-GB-RyanNeural/sheep.webm"])
    }
}
