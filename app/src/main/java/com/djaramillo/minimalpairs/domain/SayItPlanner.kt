package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.SayItResults
import com.djaramillo.minimalpairs.domain.model.SayItWord
import com.djaramillo.minimalpairs.domain.model.SayItWords
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Which words one Say-it run shows, and in what order (docs/CONTRACT.md,
 * "`sayit.zip`").
 *
 * The coach hands over `words.json` in any order and the app sorts it, so that
 * the run is the same whatever order the file happened to be written in: only
 * words whose `results.json` status is active, worst `miss_rate` first, then
 * `added` descending, `flagged_on` descending and `id` ascending. Those four
 * keys make the order total and deterministic, which matters because the first
 * `per_session` of them is the whole run.
 *
 * Nothing here scores anything: the app never scores a Say-it attempt and the
 * numbers it shows are the ones the cloud coach wrote. Nothing here throws
 * either — a missing or unparsable file is simply an empty list, and the mode
 * says so on its own page rather than inventing a word.
 *
 * No Android types, unit tested on the JVM.
 */
object SayItPlanner {

    /** `per_session` when `words.json` does not say. */
    const val DEFAULT_PER_SESSION = 5

    /** One word is the smallest run worth opening. */
    const val MIN_PER_SESSION = 1

    /** The contract's ceiling; a larger number is clamped, not obeyed. */
    const val MAX_PER_SESSION = 50

    /** What `results.json` says should happen to a word next. */
    enum class Status {
        /** Keep showing it. A word absent from `results.json` is active. */
        ACTIVE,

        /** Two attempts at accuracy 80 or better; the app stops showing it. */
        RETIRED,

        /** Still failing after three weeks; shown greyed, outside the `per_session` count, and not recordable. */
        TUTOR,
    }

    private const val RETIRED = "retired"
    private const val TUTOR = "tutor"

    /**
     * The status of one word. A word with no entry in `results.json`, and a
     * word whose status is a string this version does not know, are both
     * active: the drill keeps working when the coach adds a fourth value, and
     * the failure mode is showing a word too often rather than losing it.
     */
    fun statusOf(id: String, results: SayItResults?): Status =
        when (results?.words?.get(id)?.status?.trim()?.lowercase()) {
            RETIRED -> Status.RETIRED
            TUTOR -> Status.TUTOR
            else -> Status.ACTIVE
        }

    /**
     * How many words this run shows: `per_session` clamped to 1 to 50, and
     * [DEFAULT_PER_SESSION] when the file is absent. Clamping rather than
     * refusing keeps a coach typo from emptying the run or turning it into a
     * hundred-word slog.
     */
    fun perSession(words: SayItWords?): Int =
        (words?.perSession ?: DEFAULT_PER_SESSION).coerceIn(MIN_PER_SESSION, MAX_PER_SESSION)

    /**
     * The entries the app can actually practise, in file order.
     *
     * Dropped: an id that could not become a file name
     * ([SayItNames.isPracticableId]), a blank `word` (nothing to highlight) and a
     * blank `sentence` (nothing to speak or score). A repeated id is kept only
     * the first time, because the id is the key everything else joins on.
     *
     * A missing or unusable clip is **not** a reason to drop a word: the
     * sentence, the IPA and the recording all work without it, and only Play
     * model is disabled.
     */
    fun usable(words: SayItWords?): List<SayItWord> {
        val out = ArrayList<SayItWord>()
        val seen = HashSet<String>()
        for (w in words?.words.orEmpty()) {
            if (!SayItNames.isPracticableId(w.id)) continue
            if (w.word.isBlank() || w.sentence.isBlank()) continue
            if (!seen.add(w.id)) continue
            out.add(w)
        }
        return out
    }

    /**
     * Every active word, worst first: `miss_rate` descending, then `added`
     * descending, then `flagged_on` descending, then `id` ascending.
     *
     * `miss_rate` is the coach's own ordering key (docs/CONTRACT.md), and it is
     * the right one: a raw count promotes function words, while the share of
     * readings a word was flagged in separates "said wrong sometimes" from
     * "cannot say it". A word whose `miss_rate` or `added` the coach did not
     * write sorts after every word that has one — it cannot claim to be the
     * worst, or the newest, on the strength of a value that is missing — and
     * the remaining keys still decide, so the order is total and the same list
     * always gives the same run.
     */
    fun ordered(words: SayItWords?, results: SayItResults?): List<SayItWord> =
        inOrder(usable(words).filter { statusOf(it.id, results) == Status.ACTIVE })

    /** The first [perSession] of [ordered]: the run itself. */
    fun select(words: SayItWords?, results: SayItResults?): List<SayItWord> =
        ordered(words, results).take(perSession(words))

    /**
     * The words a human should hear, in the same order as [ordered]. They are
     * shown greyed under the run, are not part of the `per_session` count and
     * cannot be recorded against.
     */
    fun tutor(words: SayItWords?, results: SayItResults?): List<SayItWord> =
        inOrder(usable(words).filter { statusOf(it.id, results) == Status.TUTOR })

    /** The most recent accuracy the coach recorded for this word, or null when it has none yet. */
    fun lastScore(id: String, results: SayItResults?): Double? = results?.words?.get(id)?.last

    /** The best accuracy the coach recorded for this word, or null when it has none yet. */
    fun bestScore(id: String, results: SayItResults?): Double? = results?.words?.get(id)?.best

    /**
     * Sort by the contract's four keys. The dates are parsed once into a map
     * keyed by id — [usable] has already made ids unique — rather than on every
     * comparison.
     */
    private fun inOrder(list: List<SayItWord>): List<SayItWord> {
        val added = HashMap<String, LocalDate?>(list.size)
        for (w in list) added[w.id] = addedDay(w)
        return list.sortedWith(
            Comparator<SayItWord> { a, b ->
                val ra = missRate(a)
                val rb = missRate(b)
                // A word without a usable rate sorts after every word with one.
                var c = (if (ra == null) 1 else 0).compareTo(if (rb == null) 1 else 0)
                if (c == 0 && ra != null && rb != null) c = rb.compareTo(ra)
                if (c == 0) {
                    val da = added[a.id]
                    val db = added[b.id]
                    c = (if (da == null) 1 else 0).compareTo(if (db == null) 1 else 0)
                    if (c == 0 && da != null && db != null) c = db.compareTo(da)
                }
                if (c == 0) c = b.flaggedOn.compareTo(a.flaggedOn)
                if (c == 0) c = a.id.compareTo(b.id)
                c
            }
        )
    }

    /** `miss_rate` when the coach wrote a usable number, else null. NaN and infinities do not order. */
    private fun missRate(word: SayItWord): Double? =
        word.missRate?.takeIf { it.isFinite() }

    /** `added` as a date, or null when it is absent or not `YYYY-MM-DD`. */
    private fun addedDay(word: SayItWord): LocalDate? {
        val s = word.added?.trim().orEmpty()
        if (s.isEmpty()) return null
        return try {
            LocalDate.parse(s)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
