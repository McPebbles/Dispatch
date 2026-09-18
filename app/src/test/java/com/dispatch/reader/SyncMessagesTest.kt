package com.dispatch.reader

import com.dispatch.reader.feed.FeedError
import com.dispatch.reader.feed.Http
import com.dispatch.reader.feed.Sync
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a broken feed says in Manage feeds.
 *
 * These strings are the only way a reader finds out that a section has stopped
 * updating because the publisher moved the feed rather than because the news is
 * quiet — so "403" and "404" must not read the same, and none of them may be a
 * stack trace.
 *
 * Every status here was seen while the bundled set was being assembled: CBC and
 * the Washington Post answered 403, Medical News Today answered 404, and four
 * Penske titles redirected a feed to a paywall that is not a feed at all.
 */
class SyncMessagesTest {

    private fun status(code: Int) = Sync.describe(
        Http.Result(code, error = FeedError.HTTP_STATUS, errorDetail = "HTTP $code")
    )

    @Test
    fun `a refusal and a disappearance read differently`() {
        assertTrue(status(403).contains("refused"))
        assertTrue(status(404).contains("Not found"))
        assertTrue(status(410).contains("Not found"))
        assertTrue(status(429).contains("Rate limited"))
        assertTrue(status(503).contains("server"))
    }

    @Test
    fun `network faults are named in words, not exception classes`() {
        val unknownHost = Sync.describe(
            Http.Result(0, error = FeedError.NETWORK, errorDetail = "UnknownHostException: feeds.example")
        )
        assertTrue(unknownHost, unknownHost.contains("Could not find"))
        assertTrue(!unknownHost.contains("Exception"))

        val timeout = Sync.describe(
            Http.Result(0, error = FeedError.NETWORK, errorDetail = "SocketTimeoutException: read timed out")
        )
        assertTrue(timeout, timeout.contains("did not answer"))

        val tls = Sync.describe(
            Http.Result(0, error = FeedError.NETWORK, errorDetail = "SSLHandshakeException: bad cert")
        )
        assertTrue(tls, tls.contains("certificate"))
    }

    @Test
    fun `an oversized feed says so rather than failing silently`() {
        val message = Sync.describe(Http.Result(200, error = FeedError.TOO_LARGE, errorDetail = "Over 4096 KB"))
        assertTrue(message, message.contains("too large"))
    }
}
