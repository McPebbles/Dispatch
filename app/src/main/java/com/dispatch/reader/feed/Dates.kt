package com.dispatch.reader.feed

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Turning whatever a feed calls a date into epoch milliseconds.
 *
 * ## Why this is hand-rolled rather than `DateTimeFormatter.RFC_1123_DATE_TIME`
 *
 * The obvious implementation is Java's RFC-1123 formatter, and it was the first
 * one here. Run against the shapes these feeds actually emit, it rejects three
 * of them:
 *
 * ```
 * Wed, 16 Sep 2026 20:46:30 GMT     accepted
 * Wed, 16 Sep 2026 16:08:27 -0400   accepted
 * Mon, 01 Sep 2026 09:00:00 EST     REJECTED  — obsolete zone names other than GMT
 * Mon, 1 Sep 2026 09:00:00 GMT      REJECTED  — a day without a leading zero
 * Fri, 16 Sep 2026 20:46:30 GMT     REJECTED  — day-of-week disagrees with the date
 * ```
 *
 * All three are legal RFC-822 and all three appear in the wild; the last one is
 * a publisher bug that a reader should not punish the reader for. A rejected
 * date is not a visible error either — the item simply gets "now" instead, so
 * it sorts wrongly and nobody can see why. So the RFC-822 path is a small
 * tokeniser: optional day name, day, month, year, time, zone.
 *
 * **Measured, not assumed.** The tokeniser was prototyped in Java and run
 * against all fifteen cases in `DatesTest` — including every shape read off the
 * live feeds — before this file was written.
 *
 * ISO-8601 (Atom's format) is left to `java.time`, which handles it correctly.
 *
 * `java.time` needs no desugaring at minSdk 30, and unlike `SimpleDateFormat`
 * it is thread safe — which matters because a sync on four threads and the
 * widget's factory on a binder thread can be parsing at the same moment.
 *
 * Free of `android.*`: the whole table is exercised on the JVM.
 */
object Dates {

    private val MONTHS = listOf(
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"
    )

    /**
     * The obsolete zone abbreviations RFC-822 allows.
     *
     * Anything not listed is treated as UTC rather than as a failure: a date
     * with an unreadable zone is wrong by at most a few hours, and discarding
     * it makes the item undated, which is wrong by however long the feed has
     * existed.
     */
    private val ZONES = mapOf(
        "GMT" to 0, "UT" to 0, "UTC" to 0, "Z" to 0,
        "EST" to -5 * 3600, "EDT" to -4 * 3600,
        "CST" to -6 * 3600, "CDT" to -5 * 3600,
        "MST" to -7 * 3600, "MDT" to -6 * 3600,
        "PST" to -8 * 3600, "PDT" to -7 * 3600,
    )

    /**
     * Parse, or null.
     *
     * Null means "the feed did not tell us when this was published", which is a
     * real answer and not an error: the caller dates the item by when it was
     * first seen instead, so an undated item sorts by arrival rather than
     * disappearing to the bottom of the list forever.
     */
    fun parse(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        // ISO-8601 first: Atom's format is unambiguous, and its dates start with
        // four digits and a hyphen, which no RFC-822 date does.
        if (s.length >= 10 && s[4] == '-' && s[7] == '-') {
            iso(s)?.let { return it }
        }
        return rfc822(s)
    }

    private fun iso(s: String): Long? {
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            // not offset-qualified
        }
        try {
            return Instant.parse(s).toEpochMilli()
        } catch (_: DateTimeParseException) {
            // not instant-shaped
        }
        // A local date-time with no zone. A feed that does this is stating a
        // publication time, and UTC is the only defensible guess.
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeParseException) {
            // not a local date-time
        }
        return try {
            LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** `[Day,] D Mon YYYY HH:MM[:SS] [zone]` — tolerantly. */
    private fun rfc822(raw: String): Long? {
        var s = raw
        val comma = s.indexOf(',')
        // "Wed," — dropped without checking that it agrees with the date, which
        // is what lets a publisher's wrong day name through.
        if (comma in 1..4) s = s.substring(comma + 1).trim()

        val parts = s.split(WHITESPACE).filter { it.isNotEmpty() }
        if (parts.size < 4) return null

        return try {
            val day = parts[0].toInt()
            val month = MONTHS.indexOf(parts[1].lowercase(Locale.US).take(3)) + 1
            if (month == 0) return null
            var year = parts[2].toInt()
            // RFC-822 allowed two digits; RFC-2822's rule is the one below.
            if (year < 100) year += if (year < 70) 2000 else 1900

            val time = parts[3].split(':')
            if (time.size < 2) return null
            val hour = time[0].toInt()
            val minute = time[1].toInt()
            val second = if (time.size > 2) time[2].toInt() else 0

            val offset = if (parts.size > 4) zoneSeconds(parts[4]) else 0
            LocalDateTime.of(year, month, day, hour, minute, second)
                .toInstant(ZoneOffset.ofTotalSeconds(offset))
                .toEpochMilli()
        } catch (_: Throwable) {
            // NumberFormatException for junk, DateTimeException for 32 September.
            null
        }
    }

    /** `+hhmm`, `-hh:mm`, or a name from [ZONES]. Anything else is UTC. */
    private fun zoneSeconds(raw: String): Int {
        val z = raw.trim()
        if (z.isEmpty()) return 0
        val sign = z[0]
        if (sign == '+' || sign == '-') {
            val digits = z.substring(1).replace(":", "")
            if (digits.length < 4) return 0
            val hours = digits.substring(0, 2).toIntOrNull() ?: return 0
            val minutes = digits.substring(2, 4).toIntOrNull() ?: return 0
            val total = hours * 3600 + minutes * 60
            return if (sign == '-') -total else total
        }
        return ZONES[z.uppercase(Locale.US)] ?: 0
    }

    private val WHITESPACE = Regex("\\s+")
}
