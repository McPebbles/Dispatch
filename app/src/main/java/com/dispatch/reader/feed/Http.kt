package com.dispatch.reader.feed

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetching a document, with no networking library.
 *
 * `HttpURLConnection` is used rather than OkHttp because the whole of this
 * suite is built on not shipping code it does not need, and what a feed reader
 * needs from HTTP is small: a GET, a conditional GET, a redirect chain and a
 * size cap.
 *
 * Three behaviours are worth calling out because they are decisions, not
 * defaults:
 *
 *  - **Redirects are followed by hand.** `HttpURLConnection` silently refuses
 *    to follow a redirect that changes protocol, and several of the bundled
 *    feeds redirect (`feeds.ign.com` → `www.ign.com`, `fortune.com/feed/` →
 *    a query-string feed, `www.ft.com/rss/home` → `/rss/home/international`).
 *    Left to the platform those feeds would simply appear empty.
 *  - **https only.** The app's network security config forbids cleartext, so a
 *    plain-http hop is refused here with a clear error rather than failing
 *    deeper down with a confusing one.
 *  - **No cookies and no caches.** Nothing this app fetches needs a session,
 *    and a reader that accumulates publisher cookies is carrying a tracking
 *    surface it never asked for.
 */
object Http {

    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    /**
     * The User-Agent.
     *
     * Identifying the app honestly is the right default: it tells a publisher
     * that a feed reader, not a scraper, is asking. The cost is that a few
     * bot-defence products treat an unfamiliar agent as suspicious — if a feed
     * that works in a browser fails here with 403, that is the first thing to
     * suspect.
     */
    const val USER_AGENT = "Dispatch/1.0 (Android; feed reader; +no-tracking)"

    private const val ACCEPT =
        "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.8"

    data class Result(
        val status: Int,
        val bytes: ByteArray? = null,
        val contentType: String? = null,
        val etag: String? = null,
        val lastModified: String? = null,
        /** Where the bytes actually came from, after redirects. */
        val finalUrl: String? = null,
        val error: FeedError = FeedError.NONE,
        val errorDetail: String? = null,
    ) {
        val notModified: Boolean get() = status == 304
        val ok: Boolean get() = status in 200..299 && bytes != null

        // ByteArray in a data class: equality is identity, which is fine here
        // (results are never compared) but the linter asks for these.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    fun get(
        url: String,
        etag: String? = null,
        lastModified: String? = null,
        maxBytes: Int = MAX_BYTES,
    ): Result {
        var current = Sanitize.https(url)
            ?: return Result(0, error = FeedError.NETWORK, errorDetail = "Not an https URL")
        var redirects = 0

        while (true) {
            var connection: HttpURLConnection? = null
            try {
                val parsed = URL(current)
                if (!parsed.protocol.equals("https", ignoreCase = true)) {
                    return Result(0, error = FeedError.NETWORK, errorDetail = "Refused a cleartext hop")
                }
                // Held in a non-null local as well as in `connection`: the
                // latter is captured by the finally block's lambda, and a
                // captured `var` is not smart-cast.
                val open = (parsed.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    useCaches = false
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", ACCEPT)
                    setRequestProperty("Accept-Language", "en")
                    // Conditional GET. Roughly half the bundled feeds honour one
                    // of these, which turns a routine refresh into a 304 and a
                    // few hundred bytes instead of a hundred kilobytes.
                    if (!etag.isNullOrBlank()) setRequestProperty("If-None-Match", etag)
                    if (!lastModified.isNullOrBlank()) setRequestProperty("If-Modified-Since", lastModified)
                }
                connection = open

                val status = open.responseCode
                if (status == 304) {
                    return Result(304, finalUrl = current)
                }
                if (status in 300..399) {
                    val location = open.getHeaderField("Location")
                        ?: return Result(status, error = FeedError.HTTP_STATUS, errorDetail = "Redirect without a target")
                    if (++redirects > MAX_REDIRECTS) {
                        return Result(status, error = FeedError.HTTP_STATUS, errorDetail = "Too many redirects")
                    }
                    val next = resolve(current, location)
                        ?: return Result(status, error = FeedError.NETWORK, errorDetail = "Redirect to a non-https URL")
                    current = next
                    continue
                }
                if (status !in 200..299) {
                    return Result(
                        status,
                        finalUrl = current,
                        error = FeedError.HTTP_STATUS,
                        errorDetail = "HTTP $status",
                    )
                }

                val body = read(open.inputStream, maxBytes)
                    ?: return Result(status, finalUrl = current, error = FeedError.TOO_LARGE, errorDetail = "Over ${maxBytes / 1024} KB")

                return Result(
                    status = status,
                    bytes = body,
                    contentType = open.getHeaderField("Content-Type"),
                    etag = open.getHeaderField("ETag"),
                    lastModified = open.getHeaderField("Last-Modified"),
                    finalUrl = current,
                )
            } catch (t: Throwable) {
                return Result(
                    0,
                    finalUrl = current,
                    error = FeedError.NETWORK,
                    errorDetail = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: ""),
                )
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
    }

    /** Resolve a `Location` header, which may be relative, and force https. */
    fun resolve(base: String, location: String): String? = runCatching {
        Sanitize.https(URL(URL(base), location).toString())
    }.getOrNull()

    private fun read(stream: InputStream, maxBytes: Int): ByteArray? {
        stream.use { input ->
            val out = ByteArrayOutputStream(16 * 1024)
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > maxBytes) return null
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}
