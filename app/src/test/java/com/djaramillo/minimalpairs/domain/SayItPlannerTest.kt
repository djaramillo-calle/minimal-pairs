package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.SayItAttemptScore
import com.djaramillo.minimalpairs.domain.model.SayItResults
import com.djaramillo.minimalpairs.domain.model.SayItWord
import com.djaramillo.minimalpairs.domain.model.SayItWordResult
import com.djaramillo.minimalpairs.domain.model.SayItWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The run-selection rules of docs/CONTRACT.md, "`sayit.zip`". */
class SayItPlannerTest {

    private fun word(
        id: String,
        added: String? = "2026-09-01",
        flaggedOn: Int = 1,
        word: String = id,
        sentence: String = "He was the agent of $id expansion overseas.",
        clip: String? = "clips/$id.ogg",
        missRate: Double? = null,
    ) = SayItWord(
        id = id, word = word, sentence = sentence, clip = clip,
        flaggedOn = flaggedOn, missRate = missRate, added = added,
    )

    private fun words(vararg w: SayItWord, perSession: Int = 5) =
        SayItWords(version = 1, written = "2026-09-13T18:00:00Z", perSession = perSession, words = w.toList())

    private fun results(vararg statuses: kotlin.Pair<String, String>) = SayItResults(
        version = 1,
        updated = "2026-09-13T19:20:00Z",
        words = statuses.associate { (id, status) -> id to SayItWordResult(status = status) },
    )

    private fun ids(list: List<SayItWord>) = list.map { it.id }

    // ---- status ---------------------------------------------------------

    @Test
    fun aWordWithoutAResultIsActive() {
        assertEquals(SayItPlanner.Status.ACTIVE, SayItPlanner.statusOf("imperialist", null))
        assertEquals(SayItPlanner.Status.ACTIVE, SayItPlanner.statusOf("imperialist", SayItResults()))
        assertEquals(SayItPlanner.Status.ACTIVE, SayItPlanner.statusOf("other", results("imperialist" to "retired")))
    }

    @Test
    fun knownStatusesAreRead() {
        val r = results("a" to "active", "b" to "retired", "c" to "tutor")
        assertEquals(SayItPlanner.Status.ACTIVE, SayItPlanner.statusOf("a", r))
        assertEquals(SayItPlanner.Status.RETIRED, SayItPlanner.statusOf("b", r))
        assertEquals(SayItPlanner.Status.TUTOR, SayItPlanner.statusOf("c", r))
    }

    @Test
    fun anUnknownStatusIsActive() {
        val r = results("a" to "archived", "b" to "", "c" to "RETIRED", "d" to " tutor ")
        assertEquals(SayItPlanner.Status.ACTIVE, SayItPlanner.statusOf("a", r))
        assertEquals(SayItPlanner.Status.ACTIVE, SayItPlanner.statusOf("b", r))
        // Case and stray whitespace are still the value the coach meant.
        assertEquals(SayItPlanner.Status.RETIRED, SayItPlanner.statusOf("c", r))
        assertEquals(SayItPlanner.Status.TUTOR, SayItPlanner.statusOf("d", r))
    }

    // ---- per_session ----------------------------------------------------

    @Test
    fun perSessionDefaultsAndClamps() {
        assertEquals(5, SayItPlanner.perSession(null))
        assertEquals(5, SayItPlanner.perSession(SayItWords()))
        assertEquals(1, SayItPlanner.perSession(words(perSession = 1)))
        assertEquals(7, SayItPlanner.perSession(words(perSession = 7)))
        assertEquals(50, SayItPlanner.perSession(words(perSession = 50)))
        assertEquals(1, SayItPlanner.perSession(words(perSession = 0)))
        assertEquals(1, SayItPlanner.perSession(words(perSession = -4)))
        assertEquals(50, SayItPlanner.perSession(words(perSession = 51)))
        assertEquals(50, SayItPlanner.perSession(words(perSession = 10_000)))
    }

    // ---- usable ---------------------------------------------------------

    @Test
    fun unusableEntriesAreDropped() {
        val list = words(
            word("imperialist"),
            word(".."),
            word("a/b"),
            word(""),
            word(".hidden"),
            word("a".repeat(65)),
            word("blankword", word = "   "),
            word("blanksentence", sentence = ""),
            word("ok"),
        )
        assertEquals(listOf("imperialist", "ok"), ids(SayItPlanner.usable(list)))
    }

    @Test
    fun aMissingClipDoesNotDropTheWord() {
        val list = words(
            word("noclip", clip = null),
            word("badclip", clip = "../../secret.ogg"),
            word("outside", clip = "/etc/passwd"),
        )
        assertEquals(listOf("noclip", "badclip", "outside"), ids(SayItPlanner.usable(list)))
    }

    @Test
    fun aRepeatedIdIsKeptOnlyOnce() {
        val list = words(word("dup", flaggedOn = 9), word("dup", flaggedOn = 1), word("other"))
        val usable = SayItPlanner.usable(list)
        assertEquals(listOf("dup", "other"), ids(usable))
        assertEquals(9, usable.first().flaggedOn)
    }

    @Test
    fun anAbsentOrEmptyListIsEmpty() {
        assertEquals(emptyList<String>(), ids(SayItPlanner.usable(null)))
        assertEquals(emptyList<String>(), ids(SayItPlanner.ordered(null, null)))
        assertEquals(emptyList<String>(), ids(SayItPlanner.select(null, null)))
        assertEquals(emptyList<String>(), ids(SayItPlanner.tutor(null, null)))
        assertEquals(emptyList<String>(), ids(SayItPlanner.ordered(SayItWords(), SayItResults())))
    }

    // ---- ordering -------------------------------------------------------

    @Test
    fun newestAddedFirst() {
        val list = words(
            word("old", added = "2026-08-01"),
            word("newest", added = "2026-09-13"),
            word("middle", added = "2026-09-01"),
        )
        assertEquals(listOf("newest", "middle", "old"), ids(SayItPlanner.ordered(list, null)))
    }

    @Test
    fun sameDayBreaksOnFlaggedOnDescending() {
        val list = words(
            word("few", added = "2026-09-13", flaggedOn = 1),
            word("many", added = "2026-09-13", flaggedOn = 7),
            word("some", added = "2026-09-13", flaggedOn = 3),
        )
        assertEquals(listOf("many", "some", "few"), ids(SayItPlanner.ordered(list, null)))
    }

    @Test
    fun sameDayAndCountBreakOnIdAscending() {
        val list = words(
            word("charlie", added = "2026-09-13", flaggedOn = 2),
            word("alpha", added = "2026-09-13", flaggedOn = 2),
            word("bravo", added = "2026-09-13", flaggedOn = 2),
        )
        assertEquals(listOf("alpha", "bravo", "charlie"), ids(SayItPlanner.ordered(list, null)))
    }

    @Test
    fun allThreeKeysTogether() {
        val list = words(
            word("z-older", added = "2026-09-01", flaggedOn = 9),
            word("b-new-few", added = "2026-09-13", flaggedOn = 1),
            word("a-new-many", added = "2026-09-13", flaggedOn = 5),
            word("c-new-many", added = "2026-09-13", flaggedOn = 5),
            word("a-older", added = "2026-09-01", flaggedOn = 9),
        )
        assertEquals(
            listOf("a-new-many", "c-new-many", "b-new-few", "a-older", "z-older"),
            ids(SayItPlanner.ordered(list, null)),
        )
    }

    @Test
    fun aWordWithoutAUsableAddedSortsLast() {
        val list = words(
            word("nulladded", added = null, flaggedOn = 99),
            word("oldest", added = "2020-01-01", flaggedOn = 0),
            word("unparsable", added = "13/09/2026", flaggedOn = 50),
            word("blank", added = "   ", flaggedOn = 50),
            word("newest", added = "2026-09-13", flaggedOn = 0),
        )
        // Every dated word first, then the undated ones by flagged_on then id.
        assertEquals(
            listOf("newest", "oldest", "nulladded", "blank", "unparsable"),
            ids(SayItPlanner.ordered(list, null)),
        )
    }

    @Test
    fun theOrderIsTotalWhateverOrderTheFileArrivesIn() {
        val all = listOf(
            word("a", added = "2026-09-13", flaggedOn = 2),
            word("b", added = "2026-09-13", flaggedOn = 2),
            word("c", added = "2026-09-12", flaggedOn = 9),
            word("d", added = null, flaggedOn = 4),
            word("e", added = "2026-09-13", flaggedOn = 5),
        )
        val expected = ids(SayItPlanner.ordered(words(*all.toTypedArray()), null))
        assertEquals(listOf("e", "a", "b", "c", "d"), expected)
        assertEquals(expected, ids(SayItPlanner.ordered(words(*all.reversed().toTypedArray()), null)))
        assertEquals(expected, ids(SayItPlanner.ordered(words(*all.shuffled().toTypedArray()), null)))
    }

    // ---- miss_rate, the primary key --------------------------------------

    @Test
    fun theWorstMissRateComesFirst() {
        // A raw count would put "the" first; the share of readings it was
        // flagged in is what separates noise from a word he cannot say.
        val list = words(
            word("the", flaggedOn = 3, missRate = 0.015),
            word("imperialist", flaggedOn = 3, missRate = 0.75),
            word("thoroughly", flaggedOn = 9, missRate = 0.4),
        )
        assertEquals(listOf("imperialist", "thoroughly", "the"), ids(SayItPlanner.ordered(list, null)))
    }

    @Test
    fun aWordWithoutAUsableMissRateSortsAfterEveryWordWithOne() {
        val list = words(
            word("norate", added = "2026-09-13", flaggedOn = 99, missRate = null),
            word("tiny", added = "2026-09-01", flaggedOn = 0, missRate = 0.01),
            word("nan", added = "2026-09-13", flaggedOn = 99, missRate = Double.NaN),
            word("infinite", added = "2026-09-13", flaggedOn = 99, missRate = Double.POSITIVE_INFINITY),
        )
        // Only `tiny` has a usable rate; the rest fall through to added, then
        // flagged_on, then id — and a NaN never wins by comparing oddly.
        assertEquals(listOf("tiny", "infinite", "nan", "norate"), ids(SayItPlanner.ordered(list, null)))
    }

    @Test
    fun missRateTiesFallThroughToTheRemainingKeys() {
        val list = words(
            word("b", added = "2026-09-12", flaggedOn = 9, missRate = 0.5),
            word("a", added = "2026-09-13", flaggedOn = 1, missRate = 0.5),
            word("c", added = "2026-09-13", flaggedOn = 1, missRate = 0.5),
        )
        assertEquals(listOf("a", "c", "b"), ids(SayItPlanner.ordered(list, null)))
    }

    @Test
    fun theOrderIsTotalWithMissRatesTooWhateverOrderTheFileArrivesIn() {
        val all = listOf(
            word("a", added = "2026-09-13", flaggedOn = 2, missRate = 0.5),
            word("b", added = "2026-09-13", flaggedOn = 2, missRate = 0.5),
            word("c", added = "2026-09-12", flaggedOn = 9, missRate = 1.5),
            word("d", added = null, flaggedOn = 4, missRate = null),
            word("e", added = "2026-09-13", flaggedOn = 5, missRate = 0.25),
        )
        val expected = ids(SayItPlanner.ordered(words(*all.toTypedArray()), null))
        assertEquals(listOf("c", "a", "b", "e", "d"), expected)
        assertEquals(expected, ids(SayItPlanner.ordered(words(*all.reversed().toTypedArray()), null)))
        assertEquals(expected, ids(SayItPlanner.ordered(words(*all.shuffled().toTypedArray()), null)))
    }

    // ---- status filtering -----------------------------------------------

    @Test
    fun onlyActiveWordsAreOrdered() {
        val list = words(
            word("active1", added = "2026-09-13"),
            word("retired1", added = "2026-09-12"),
            word("tutor1", added = "2026-09-11"),
            word("active2", added = "2026-09-10"),
            word("unknownstatus", added = "2026-09-09"),
        )
        val r = results(
            "retired1" to "retired",
            "tutor1" to "tutor",
            "active2" to "active",
            "unknownstatus" to "sabbatical",
        )
        assertEquals(listOf("active1", "active2", "unknownstatus"), ids(SayItPlanner.ordered(list, r)))
        assertEquals(listOf("tutor1"), ids(SayItPlanner.tutor(list, r)))
    }

    @Test
    fun tutorWordsAreNotPartOfTheRun() {
        val list = words(
            word("tutor1", added = "2026-09-13"),
            word("tutor2", added = "2026-09-12"),
            word("active1", added = "2026-09-11"),
            perSession = 2,
        )
        val r = results("tutor1" to "tutor", "tutor2" to "tutor")
        assertEquals(listOf("active1"), ids(SayItPlanner.select(list, r)))
        assertEquals(listOf("tutor1", "tutor2"), ids(SayItPlanner.tutor(list, r)))
    }

    @Test
    fun unparsableResultsMeanEveryWordIsActive() {
        val list = words(word("a", added = "2026-09-13"), word("b", added = "2026-09-12"))
        assertEquals(listOf("a", "b"), ids(SayItPlanner.ordered(list, null)))
        assertTrue(SayItPlanner.tutor(list, null).isEmpty())
    }

    // ---- select ---------------------------------------------------------

    @Test
    fun selectTakesThePerSessionFirst() {
        val list = words(
            word("a", added = "2026-09-13"),
            word("b", added = "2026-09-12"),
            word("c", added = "2026-09-11"),
            word("d", added = "2026-09-10"),
            perSession = 2,
        )
        assertEquals(listOf("a", "b"), ids(SayItPlanner.select(list, null)))
    }

    @Test
    fun selectTakesWhatThereIsWhenTheListIsShort() {
        val list = words(word("a"), word("b"), perSession = 50)
        assertEquals(listOf("a", "b"), ids(SayItPlanner.select(list, null)))
    }

    @Test
    fun selectClampsAnOutOfRangePerSession() {
        val list = words(word("a", added = "2026-09-13"), word("b", added = "2026-09-12"), perSession = 0)
        assertEquals(listOf("a"), ids(SayItPlanner.select(list, null)))
    }

    // ---- scores ---------------------------------------------------------

    @Test
    fun scoresComeFromTheCoachOrAreAbsent() {
        val r = SayItResults(
            words = mapOf(
                "imperialist" to SayItWordResult(
                    attempts = listOf(SayItAttemptScore(at = "2026-09-13T18:04:02Z", accuracy = 71.0)),
                    best = 88.0,
                    last = 71.0,
                    status = "active",
                ),
                "nothing" to SayItWordResult(),
            )
        )
        assertEquals(71.0, SayItPlanner.lastScore("imperialist", r)!!, 1e-9)
        assertEquals(88.0, SayItPlanner.bestScore("imperialist", r)!!, 1e-9)
        assertNull(SayItPlanner.lastScore("nothing", r))
        assertNull(SayItPlanner.bestScore("nothing", r))
        assertNull(SayItPlanner.lastScore("unknown", r))
        assertNull(SayItPlanner.bestScore("imperialist", null))
    }
}
