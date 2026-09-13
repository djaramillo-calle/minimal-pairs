package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.SayItAttemptScore
import com.djaramillo.minimalpairs.domain.model.SayItResults
import com.djaramillo.minimalpairs.domain.model.SayItWordResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The retention rule of docs/CONTRACT.md ("Housekeeping"): attempt audio the
 * coach has scored and that is more than 30 days old may go, everything else
 * stays. The keeping cases are the ones worth most here — an unscored
 * recording is the one thing that could be lost for good.
 *
 * The join is the sidecar's file name, because that is the key the coach itself
 * uses to remember what it has already handled.
 */
class SayItCleanupTest {

    private val started = Instant.parse("2026-08-01T10:00:00Z")
    private val startedIso = "2026-08-01T10:00:00Z"
    private val ts = SayItNames.stampOf(started)

    private fun audio(id: String, at: Instant = started) = SayItNames.audioName(SayItNames.stampOf(at), id)
    private fun sidecar(id: String, at: Instant = started) = SayItNames.sidecarName(SayItNames.stampOf(at), id)

    /** `id to the sidecar file names the coach says it has scored`. */
    private fun scored(vararg entries: kotlin.Pair<String, List<String>>) = SayItResults(
        version = 1,
        updated = "2026-09-13T19:20:00Z",
        words = entries.associate { (id, files) ->
            id to SayItWordResult(
                attempts = files.map { SayItAttemptScore(at = startedIso, file = it, accuracy = 71.0) },
                best = 71.0,
                last = 71.0,
                status = "active",
            )
        },
    )

    private fun at(days: Long) = started.plus(Duration.ofDays(days))

    // ---- scoredFiles -----------------------------------------------------

    @Test
    fun scoredFilesCollectsTheSidecarNamesVerbatim() {
        val results = scored(
            "imperialist" to listOf(sidecar("imperialist"), sidecar("imperialist", at(1))),
            "other" to listOf(sidecar("other", at(2))),
        )
        val map = SayItCleanup.scoredFiles(results)
        assertEquals(setOf(sidecar("imperialist"), sidecar("imperialist", at(1))), map["imperialist"])
        assertEquals(setOf(sidecar("other", at(2))), map["other"])
        assertTrue(SayItCleanup.scoredFiles(null).isEmpty())
        assertTrue(SayItCleanup.scoredFiles(SayItResults()).isEmpty())
    }

    @Test
    fun aWordWithNoScoredAttemptIsNotListed() {
        val results = SayItResults(
            words = mapOf(
                "none" to SayItWordResult(),
                "blank" to SayItWordResult(attempts = listOf(SayItAttemptScore(file = null))),
                "empty" to SayItWordResult(attempts = listOf(SayItAttemptScore(file = "  "))),
            )
        )
        assertTrue(SayItCleanup.scoredFiles(results).isEmpty())
    }

    // ---- the 30-day boundary --------------------------------------------

    @Test
    fun exactlyThirtyDaysOldIsKept() {
        val results = scored("imperialist" to listOf(sidecar("imperialist")))
        val names = listOf(audio("imperialist"))
        assertEquals(emptyList<String>(), SayItCleanup.deletable(names, results, at(30)))
        // One second later it is more than 30 days old.
        assertEquals(names, SayItCleanup.deletable(names, results, at(30).plusSeconds(1)))
    }

    @Test
    fun thirtyOneDaysOldAndScoredIsDeleted() {
        val results = scored("imperialist" to listOf(sidecar("imperialist")))
        val names = listOf(audio("imperialist"))
        assertEquals(names, SayItCleanup.deletable(names, results, at(31)))
    }

    @Test
    fun thirtyOneDaysOldAndUnscoredIsKept() {
        val names = listOf(audio("imperialist"))
        assertEquals(emptyList<String>(), SayItCleanup.deletable(names, null, at(31)))
        assertEquals(emptyList<String>(), SayItCleanup.deletable(names, SayItResults(), at(31)))
        assertEquals(
            emptyList<String>(),
            SayItCleanup.deletable(names, scored("other" to listOf(sidecar("other"))), at(31)),
        )
        // However old: an unscored recording is never deleted.
        assertEquals(emptyList<String>(), SayItCleanup.deletable(names, null, at(3650)))
    }

    @Test
    fun thirtyOneDaysOldScoredUnderADifferentSidecarIsKept() {
        // The same word, two other attempts scored: this recording is not one of them.
        val results = scored(
            "imperialist" to listOf(sidecar("imperialist", started.plusSeconds(1)), sidecar("imperialist", at(-2))),
        )
        val names = listOf(audio("imperialist"))
        assertEquals(emptyList<String>(), SayItCleanup.deletable(names, results, at(31)))
    }

    @Test
    fun aResultWithOnlyAnAtAndNoFileNameDeletesNothing() {
        // The coach writes `file`; a results.json that carries only `at` is not
        // evidence that THIS recording was handled, so the audio stays.
        val results = SayItResults(
            words = mapOf(
                "imperialist" to SayItWordResult(
                    attempts = listOf(SayItAttemptScore(at = startedIso, accuracy = 71.0)),
                ),
            ),
        )
        assertEquals(emptyList<String>(), SayItCleanup.deletable(listOf(audio("imperialist")), results, at(99)))
    }

    @Test
    fun aFutureTimestampIsKept() {
        val results = scored("imperialist" to listOf(sidecar("imperialist")))
        val names = listOf(audio("imperialist"))
        assertEquals(emptyList<String>(), SayItCleanup.deletable(names, results, started.minusSeconds(1)))
    }

    // ---- what is never touched ------------------------------------------

    @Test
    fun onlyAttemptAudioIsEverDeletable() {
        val results = scored("imperialist" to listOf(sidecar("imperialist")))
        val names = listOf(
            SayItNames.sidecarName(ts, "imperialist"),
            "words.json",
            "results.json",
            "sayit.zip",
            "clips",
            "imperialist.m4a",
            ts + "_imperialist.ogg",
            "20260931T100000Z_imperialist.m4a",
            "2026-08-01T10:00:00Z_imperialist.m4a",
        )
        assertEquals(emptyList<String>(), SayItCleanup.deletable(names, results, at(400)))
    }

    @Test
    fun theSidecarStaysWhenTheAudioGoes() {
        val results = scored("imperialist" to listOf(sidecar("imperialist")))
        val names = listOf(SayItNames.sidecarName(ts, "imperialist"), SayItNames.audioName(ts, "imperialist"))
        assertEquals(listOf(SayItNames.audioName(ts, "imperialist")), SayItCleanup.deletable(names, results, at(31)))
    }

    // ---- a whole folder --------------------------------------------------

    @Test
    fun aMixedFolderKeepsEverythingTheRuleDoesNotAllow() {
        val oldScored = audio("imperialist", started)
        val oldUnscored = audio("catastrophe", started)
        val freshScored = audio("imperialist", at(25))
        val otherOldScored = audio("um_brella", started)
        val results = scored(
            "imperialist" to listOf(sidecar("imperialist", started), sidecar("imperialist", at(25))),
            "um_brella" to listOf(sidecar("um_brella", started)),
        )
        val names = listOf(oldScored, oldUnscored, freshScored, otherOldScored, "sayit.zip")
        assertEquals(listOf(oldScored, otherOldScored), SayItCleanup.deletable(names, results, at(31)))
    }

    @Test
    fun anEmptyListingIsEmpty() {
        assertEquals(
            emptyList<String>(),
            SayItCleanup.deletable(emptyList(), scored("a" to listOf(sidecar("a"))), at(99)),
        )
    }

    @Test
    fun retentionIsThirtyDays() {
        assertEquals(30L, SayItCleanup.RETAIN_DAYS)
    }
}
