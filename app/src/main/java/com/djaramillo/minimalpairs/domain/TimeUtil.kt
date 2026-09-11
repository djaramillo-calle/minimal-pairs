package com.djaramillo.minimalpairs.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** UTC ISO 8601 helpers for the contract (`2026-09-11T07:02:11Z`) and file names (`20260911T070211Z`). */
object TimeUtil {
    private val ISO_SECONDS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    private val BASIC_SECONDS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /** `2026-09-11T07:02:11Z`, seconds precision, always UTC. */
    fun formatIso(instant: Instant): String = ISO_SECONDS.format(instant.truncatedTo(ChronoUnit.SECONDS))

    /**
     * Parse an ISO 8601 timestamp (with or without fraction, `Z` or an offset).
     * Returns null for null, blank or malformed input — callers treat that as "absent".
     */
    fun parseIso(text: String?): Instant? {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return null
        return try {
            Instant.parse(s)
        } catch (e: java.time.format.DateTimeParseException) {
            try {
                java.time.OffsetDateTime.parse(s).toInstant()
            } catch (e2: java.time.format.DateTimeParseException) {
                null
            }
        }
    }

    /** Session id / file name stem: `20260911T070211Z`. */
    fun sessionIdFrom(instant: Instant): String = BASIC_SECONDS.format(instant.truncatedTo(ChronoUnit.SECONDS))

    /**
     * The first second at or after [started] whose session id is not [taken].
     * Two sessions can only share a `started` second when the wall clock
     * stepped backwards; bumping keeps `id` and `started` consistent and the
     * second session's file distinct instead of silently dropped.
     */
    fun firstFreeSessionStart(started: Instant, taken: (String) -> Boolean): Instant {
        var t = started.truncatedTo(ChronoUnit.SECONDS)
        var guard = 0
        while (taken(sessionIdFrom(t)) && guard++ < 100_000) t = t.plusSeconds(1)
        return t
    }


    /** The UTC calendar day of [instant], used for the streak. */
    fun utcDay(instant: Instant): LocalDate = instant.atOffset(ZoneOffset.UTC).toLocalDate()

    /** UTC day of an ISO timestamp string, or null when it does not parse. */
    fun utcDay(iso: String?): LocalDate? = parseIso(iso)?.let { utcDay(it) }
}
