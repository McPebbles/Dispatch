package com.dispatch.reader.feed

import java.net.URL

/**
 * Turning what a person pasted into a feed URL.
 *
 * Almost nobody knows their newspaper's feed address; they know the paper. So
 * "Add feed" accepts a site address and finds the feed, in three steps, each
 * cheaper than the next one:
 *
 *  1. if the document fetched is already a feed, use it;
 *  2. if it is HTML, read its `<link rel="alternate" type="application/rss+xml">`
 *     tags — the standard, published way for a site to say where its feed is;
 *  3. failing that, try the handful of paths that the common publishing
 *     systems use (`/feed/`, `/rss`, `/atom.xml` …).
 *
 * Step 3 is guessing, and it is last for that reason: it costs requests to a
 * site that has already said nothing, so it is bounded to [GUESSES].
 *
 * Free of `android.*`.
 */
object Discovery {

    /** Paths worth trying when a page advertises no feed at all. */
    val GUESSES: List<String> = listOf(
        "/feed/", "/rss", "/rss.xml", "/feed.xml", "/atom.xml", "/index.xml",
        "/feeds/posts/default", "/rss/index.xml", "/?feed=rss2"
    )

    /** True when a document looks like a feed rather than a web page. */
    fun looksLikeFeed(text: String): Boolean {
        val head = text.take(2000)
        val lower = head.lowercase()
        return lower.contains("<rss") || lower.contains("<feed") ||
            lower.contains("<rdf:rdf") || lower.contains("<channel")
    }

    /**
     * The feed links advertised by an HTML page, in document order, made
     * absolute against [baseUrl].
     *
     * Deliberately a scan for `<link>` tags rather than an HTML parse: the app
     * has no HTML parser, this runs on a document from a stranger, and the
     * attribute reader ([Sanitize.attr]) is the same one the feed parser uses
     * for `<img>`.
     */
    fun discover(html: String, baseUrl: String): List<String> {
        val out = LinkedHashSet<String>()
        var i = 0
        val limit = html.length.coerceAtMost(500_000)
        while (i < limit) {
            val open = indexOfIgnoreCase(html, "<link", i, limit)
            if (open < 0) break
            val close = html.indexOf('>', open)
            if (close < 0) break
            val tag = html.substring(open, close)
            i = close + 1

            val rel = Sanitize.attr(tag, "rel")?.lowercase() ?: continue
            if ("alternate" !in rel) continue
            val type = Sanitize.attr(tag, "type")?.lowercase() ?: continue
            if (!isFeedType(type)) continue
            val href = Sanitize.attr(tag, "href")?.trim() ?: continue
            absolute(baseUrl, Sanitize.decodeEntities(href))?.let { out.add(it) }
        }
        return out.toList()
    }

    private fun isFeedType(type: String): Boolean =
        type.contains("rss+xml") || type.contains("atom+xml") ||
            type.contains("rdf+xml") || type == "text/xml" || type == "application/xml"

    fun absolute(base: String, href: String): String? = runCatching {
        Sanitize.https(URL(URL(base), href).toString())
    }.getOrNull()

    /** The candidate URLs to try for a site address, in order. */
    fun guessesFor(siteUrl: String): List<String> {
        val root = runCatching {
            val u = URL(siteUrl)
            "https://" + u.host + (if (u.port > 0 && u.port != 443) ":" + u.port else "")
        }.getOrNull() ?: return emptyList()
        return GUESSES.map { root + it }
    }

    private fun indexOfIgnoreCase(s: String, needle: String, from: Int, limit: Int): Int {
        var i = from
        while (i <= limit - needle.length) {
            if (s.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) return i
            i++
        }
        return -1
    }
}
