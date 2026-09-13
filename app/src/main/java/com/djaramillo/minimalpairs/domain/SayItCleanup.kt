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
 * old, because it is the one thing that could be lost for good.
 *
 * [orphans] is the one exception, and for the same reason: audio with no
 * sidecar cannot be scored by anyone, so keeping it loses nothing and costs
 * the owner's Drive space and an error in the coach's checker for ever.
 *
 * These two are the only deletions the app ever makes in the folder for this
 * mode: the whole of `sayit.zip` belongs to the coach and is opened read-only.
 *
 * No Android types, unit tested on the JVM.
 */
object SayItCleanup {

    /** Scored audio older than this many days may go. */
    const val RETAIN_DAYS = 30L

    /**
     * Audio with no sidecar may go once it is older than this many hours.
     *
     * The wait is there because sync delivers the two halves separately: a
     * recording written minutes ago may simply be waiting for its sidecar to
     * come down from Drive. A day is far longer than that and far shorter than
     * the retention rule, which can never reach these files at all.
     */
    const val ORPHAN_HOURS = 24L

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
            // Keyed by the file-name spelling of the id, because that is what
            // an attempt's name carries; `results.json` keys are the coach's
            // raw ids (docs/CONTRACT.md, "Say it").
            if (files.isNotEmpty()) out[SayItNames.fileId(id)] = files
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

    /**
     * Of [names] (one folder listing of `sayit/attempts/`), the recordings with
     * no sidecar beside them that are older than [ORPHAN_HOURS] at [now], in
     * the order they were given.
     *
     * These are the leftovers of a write whose sidecar never landed — the
     * delete that was meant to undo it failed, or the process died between the
     * two files. The coach cannot score audio it has no sidecar for and flags
     * it as an error on every run, and [deletable] can never reach it, because
     * that rule asks whether the sidecar has been scored and an attempt with no
     * sidecar never is. So this is the only rule that can ever collect them.
     */
    fun orphans(names: List<String>, now: Instant): List<String> {
        val wait = Duration.ofHours(ORPHAN_HOURS)
        val sidecars = names.filterTo(HashSet()) { SayItNames.isAttemptSidecar(it) }
        return names.filter { name ->
            if (!SayItNames.isAttemptAudio(name)) return@filter false
            val ts = SayItNames.timestampOf(name) ?: return@filter false
            val id = SayItNames.idOf(name) ?: return@filter false
            if (SayItNames.sidecarName(ts, id) in sidecars) return@filter false
            val started = SayItNames.instantOf(ts) ?: return@filter false
            Duration.between(started, now) > wait
        }
    }
}
