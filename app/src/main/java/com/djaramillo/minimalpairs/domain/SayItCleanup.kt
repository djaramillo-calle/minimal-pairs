package com.djaramillo.minimalpairs.domain

import com.djaramillo.minimalpairs.domain.model.SayItResults
import java.time.Duration
import java.time.Instant

/**
 * The retention rule for attempt audio (docs/CONTRACT.md, "Housekeeping").
 *
 * Recordings must not pile up on the phone and in Drive forever, so the app
 * sweeps them itself. It may delete a `sayit/attempts/<ts>_<id>.m4a` only when
 * both halves hold: `<ts>` is more than 30 days old **and** the matching
 * sidecar's file name already appears as a `file` under
 * `results.words[<id>].attempts`, which is the coach saying it has scored that
 * attempt. That `file` key is exactly how the coach itself remembers what it
 * has already handled, so the app and the coach agree by construction rather
 * than by a second, parallel rule.
 *
 * Only the audio goes. The sidecar is a few hundred bytes and stays as the
 * record of the attempt, and an unscored recording is never deleted however
 * old, because it is the one thing that could be lost for good. This is also
 * the only deletion the app ever makes in the folder for this mode: the whole
 * of `sayit.zip` belongs to the coach and is opened read-only.
 *
 * No Android types, unit tested on the JVM.
 */
object SayItCleanup {

    /** Scored audio older than this many days may go. */
    const val RETAIN_DAYS = 30L

    /**
     * Word id to the set of sidecar file names the coach has scored, taken
     * verbatim. Ids with nothing scored are left out, so a lookup that misses
     * is already the answer "keep it".
     */
    fun scoredFiles(results: SayItResults?): Map<String, Set<String>> {
        val out = LinkedHashMap<String, Set<String>>()
        for ((id, word) in results?.words.orEmpty()) {
            val files = LinkedHashSet<String>()
            for (attempt in word.attempts) {
                val file = attempt.file?.trim().orEmpty()
                if (file.isNotEmpty()) files.add(file)
            }
            if (files.isNotEmpty()) out[id] = files
        }
        return out
    }

    /**
     * Of [names] (one folder listing of `sayit/attempts/`), the recordings that
     * may be deleted at [now], in the order they were given.
     *
     * Everything the rule does not positively allow is kept: a name that is not
     * one of ours, a timestamp that names no real instant, audio at exactly 30
     * days (the rule is strictly more), audio the coach has not scored, and
     * audio whose id is scored but under some other sidecar name — that is a
     * different attempt of the same word.
     */
    fun deletable(names: List<String>, results: SayItResults?, now: Instant): List<String> {
        val scored = scoredFiles(results)
        val retain = Duration.ofDays(RETAIN_DAYS)
        return names.filter { name ->
            if (!SayItNames.isAttemptAudio(name)) return@filter false
            val ts = SayItNames.timestampOf(name) ?: return@filter false
            val id = SayItNames.idOf(name) ?: return@filter false
            val started = SayItNames.instantOf(ts) ?: return@filter false
            if (Duration.between(started, now) <= retain) return@filter false
            val files = scored[id] ?: return@filter false
            SayItNames.sidecarName(ts, id) in files
        }
    }
}
