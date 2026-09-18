package com.dispatch.reader.util

import android.content.Context
import android.text.format.DateUtils
import com.dispatch.reader.R

/**
 * How old a story is, in as few characters as possible.
 *
 * The widget's meta line has room for about six characters next to a headline,
 * so the compact forms ("3h", "2d") are used there and the platform's own
 * relative-time formatter, which is localised and handles "yesterday", is used
 * in the app.
 */
object Time {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** "now", "14m", "3h", "2d", "5w". */
    fun shortAgo(context: Context, at: Long, now: Long = System.currentTimeMillis()): String {
        if (at <= 0) return ""
        val delta = now - at
        return when {
            // A future timestamp is a publisher's clock being wrong, not a
            // story from tomorrow. Showing "in 4 hours" on a news item reads as
            // a bug in the reader, so it is clamped.
            delta < MINUTE -> context.getString(R.string.time_now)
            delta < HOUR -> context.getString(R.string.time_minutes, delta / MINUTE)
            delta < DAY -> context.getString(R.string.time_hours, delta / HOUR)
            delta < 7 * DAY -> context.getString(R.string.time_days, delta / DAY)
            else -> context.getString(R.string.time_weeks, delta / (7 * DAY))
        }
    }

    /** The app's own list rows: the platform's localised phrasing. */
    fun relative(at: Long, now: Long = System.currentTimeMillis()): CharSequence {
        if (at <= 0) return ""
        val clamped = minOf(at, now)
        return DateUtils.getRelativeTimeSpanString(
            clamped, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
        )
    }

    /**
     * A full date for the article screen.
     *
     * The context is not optional: `DateUtils` reads the locale and the
     * 12/24-hour setting from it.
     */
    fun full(context: Context, at: Long): CharSequence {
        if (at <= 0) return ""
        return DateUtils.formatDateTime(
            context,
            at,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME,
        )
    }
}
