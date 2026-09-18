package com.dispatch.reader

import com.dispatch.reader.feed.Dates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every case here was run against a real JVM before the parser was written, and
 * the four marked "live" are the exact strings the bundled feeds emit.
 *
 * The three marked "RFC_1123 rejects this" are why the RFC-822 path is
 * hand-rolled: Java's own RFC-1123 formatter refuses all three, and each one
 * would have silently become "now" in the article list.
 */
class DatesTest {

    @Test
    fun `live rfc822 shapes`() {
        // BBC
        assertEquals(1789591590000L, Dates.parse("Wed, 16 Sep 2026 20:46:30 GMT"))
        // CBS Sports
        assertEquals(1789611787000L, Dates.parse("Thu, 17 Sep 2026 02:23:07 +0000"))
        // NPR
        assertEquals(1789589307000L, Dates.parse("Wed, 16 Sep 2026 16:08:27 -0400"))
    }

    @Test
    fun `live iso shapes`() {
        // The Verge (Atom)
        assertEquals(1789614000000L, Dates.parse("2026-09-16T23:00:00-04:00"))
        // The Guardian's dc:date
        assertEquals(1789599600000L, Dates.parse("2026-09-16T23:00:00Z"))
        // Nature's dc:date is a bare date
        assertEquals(1789516800000L, Dates.parse("2026-09-16"))
    }

    @Test
    fun `an obsolete zone name is understood`() {
        // RFC_1123 rejects this.
        assertEquals(1788271200000L, Dates.parse("Mon, 01 Sep 2026 09:00:00 EST"))
    }

    @Test
    fun `a day without a leading zero is understood`() {
        // RFC_1123 rejects this.
        assertEquals(1788253200000L, Dates.parse("Mon, 1 Sep 2026 09:00:00 GMT"))
    }

    @Test
    fun `a day name that disagrees with the date is ignored rather than fatal`() {
        // RFC_1123 rejects this. 16 September 2026 is a Wednesday; the feed says
        // Friday. The date is what matters, and a publisher's calendar bug is
        // not a reason to leave the story undated.
        assertEquals(1789591590000L, Dates.parse("Fri, 16 Sep 2026 20:46:30 GMT"))
    }

    @Test
    fun `optional pieces`() {
        // No day name at all.
        assertEquals(1789591590000L, Dates.parse("16 Sep 2026 20:46:30 GMT"))
        // No seconds.
        assertEquals(1789591560000L, Dates.parse("Wed, 16 Sep 2026 20:46 GMT"))
        // A colon in the offset.
        assertEquals(1789605990000L, Dates.parse("Wed, 16 Sep 2026 20:46:30 -04:00"))
        // No zone at all: UTC, which is the only defensible guess.
        assertEquals(1789591590000L, Dates.parse("Wed, 16 Sep 2026 20:46:30"))
        // Two-digit year.
        assertEquals(1789591590000L, Dates.parse("Tue, 16 Sep 26 20:46:30 GMT"))
        // Fractional seconds on an ISO date.
        assertEquals(1789599600123L, Dates.parse("2026-09-16T23:00:00.123Z"))
    }

    @Test
    fun `junk is null, not an exception and not the epoch`() {
        assertNull(Dates.parse(null))
        assertNull(Dates.parse(""))
        assertNull(Dates.parse("   "))
        assertNull(Dates.parse("garbage"))
        assertNull(Dates.parse("Wed, 32 Sep 2026 20:46:30 GMT"))
        assertNull(Dates.parse("Wed, 16 Xxx 2026 20:46:30 GMT"))
        assertNull(Dates.parse("2026-13-45"))
    }

    @Test
    fun `an unreadable zone falls back to UTC rather than to nothing`() {
        // Wrong by at most a few hours, where discarding it would make the item
        // undated forever.
        assertEquals(1789591590000L, Dates.parse("Wed, 16 Sep 2026 20:46:30 XYZ"))
    }
}
