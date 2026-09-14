package com.djaramillo.minimalpairs.storage

import com.djaramillo.minimalpairs.domain.SayItNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rule the whole Say-it contract rests on: **the app never writes a coach
 * file.** `sayit.zip` — and the `words.json` and `results.json` inside it —
 * belongs to the coach; the app creates only its own `sayit/attempts/` pair and
 * one immutable `sayit/scores/<ts>_<id>.json` per scored attempt
 * (docs/CONTRACT.md).
 *
 * Nothing in a JVM unit test can open a SAF document, so this reads
 * [DataFolder]'s own source instead and checks the two things that would break
 * the rule: a name being created that is not one of the app's own, and a write
 * pointed at the coach's zip. It is a coarse check by design — it is the one
 * mistake that would be invisible until the coach's ledger was overwritten.
 */
class SayItWritesTest {

    /** Gradle runs unit tests with the module directory as the working directory. */
    private fun source(path: String): String {
        val file = listOf(File(path), File("app", path)).firstOrNull { it.isFile }
            ?: throw AssertionError("cannot find $path from ${File(".").absolutePath}")
        return file.readText()
    }

    private val dataFolder by lazy { source("src/main/java/com/djaramillo/minimalpairs/storage/DataFolder.kt") }

    /** Every name [DataFolder] is allowed to create, as it is spelled in the source. */
    private val allowedNames = setOf(
        "subfolder", "sub",                       // the data folder itself
        "FolderLayout.STATE", "FolderLayout.STATE_TMP",
        "FolderLayout.SESSIONS", "FolderLayout.CATALOG_VERSION",
        "FolderLayout.SAYIT",                     // the parent of the two folders the app owns
        "name",                                   // sayItChild's parameter: attempts or scores
        "audioName", "sidecarName",               // one attempt
        "fileName",                               // one score file, checked to be <ts>_<id>.json first
        "f.name",                                 // republished session copies
        "FolderLayout.CLIPS_ZIP_TMP",             // the clip pack, renamed to clips.zip once written
    )

    @Test
    fun theFolderCreatesNothingButItsOwnNames() {
        val calls = Regex("""create(?:File|Directory)\(([^)]*)\)""").findAll(dataFolder)
            .map { it.groupValues[1] }
            .map { args ->
                // createFile takes (mime, name); createDirectory takes (name).
                args.split(",").last().trim()
            }
            .toList()
        assertTrue("no create calls found: the scan is looking at the wrong file", calls.isNotEmpty())
        for (name in calls) {
            assertTrue("DataFolder creates an unexpected name: $name", name in allowedNames)
        }
    }

    @Test
    fun nothingIsEverWrittenToTheCoachsZip() {
        val writeLine = Regex("""createFile\(|createDirectory\(|openOutputStream\(|writeText\(|copyInto\(""")
        for ((i, line) in dataFolder.lines().withIndex()) {
            if (!writeLine.containsMatchIn(line)) continue
            for (forbidden in listOf("SAYIT_ZIP", "sayit.zip", "results.json", "words.json")) {
                assertFalse(
                    "DataFolder.kt:${i + 1} writes to a coach file: ${line.trim()}",
                    line.contains(forbidden),
                )
            }
        }
    }

    @Test
    fun theScoreWriterOnlyAcceptsAnAttemptStemName() {
        // writeSayItScore's first act is this check, so nothing but
        // <ts>_<id>.json can be created in sayit/scores/ however it is called.
        assertTrue(dataFolder.contains("if (!SayItNames.isAttemptSidecar(fileName)) return@withContext false"))
        for (bad in listOf("results.json", "words.json", "sayit.zip", "plan.json", "state.json", "../escape.json")) {
            assertFalse(bad, SayItNames.isAttemptSidecar(bad))
        }
        assertTrue(SayItNames.isAttemptSidecar("20260914T071002Z_imperialist.json"))
    }

    @Test
    fun theScoreFolderIsAChildOfSayitNotASiblingOfTheZip() {
        assertEquals("scores", FolderLayout.SAYIT_SCORES)
        assertEquals("attempts", FolderLayout.SAYIT_ATTEMPTS)
        assertEquals("sayit", FolderLayout.SAYIT)
        assertEquals("sayit.zip", FolderLayout.SAYIT_ZIP)
    }
}
