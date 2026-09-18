package com.dispatch.reader

import com.dispatch.reader.feed.Discovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Add feed" has to accept what people actually have: the newspaper's address,
 * not its feed's.
 */
class DiscoveryTest {

    @Test
    fun `a page's advertised feeds are found, in order, made absolute`() {
        val html = """
            <html><head>
              <link rel="stylesheet" href="/style.css">
              <link rel="alternate" type="application/rss+xml" title="News" href="/feed/rss.xml">
              <link rel="alternate" type="application/atom+xml" title="Atom" href="https://cdn.example.org/atom.xml">
              <link rel="alternate" type="application/json" href="/feed.json">
            </head><body>…</body></html>
        """.trimIndent()
        assertEquals(
            listOf("https://example.org/feed/rss.xml", "https://cdn.example.org/atom.xml"),
            Discovery.discover(html, "https://example.org/news/"),
        )
    }

    @Test
    fun `a stylesheet or an icon is never mistaken for a feed`() {
        val html = """
            <link rel="icon" href="/favicon.ico">
            <link rel="alternate" hreflang="fr" href="/fr/">
            <link rel="canonical" href="https://example.org/">
        """.trimIndent()
        assertTrue(Discovery.discover(html, "https://example.org/").isEmpty())
    }

    @Test
    fun `entities in an advertised href are decoded`() {
        val html = """<link rel="alternate" type="application/rss+xml" href="/feed?a=1&amp;b=2">"""
        assertEquals(
            listOf("https://example.org/feed?a=1&b=2"),
            Discovery.discover(html, "https://example.org/"),
        )
    }

    @Test
    fun `the same feed advertised twice is listed once`() {
        val html = """
            <link rel="alternate" type="application/rss+xml" href="/feed">
            <link rel="alternate" type="application/rss+xml" href="/feed">
        """.trimIndent()
        assertEquals(1, Discovery.discover(html, "https://example.org/").size)
    }

    @Test
    fun `a feed document is recognised without being parsed`() {
        assertTrue(Discovery.looksLikeFeed("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel>"))
        assertTrue(Discovery.looksLikeFeed("<feed xmlns=\"http://www.w3.org/2005/Atom\">"))
        assertTrue(Discovery.looksLikeFeed("<rdf:RDF xmlns=\"http://purl.org/rss/1.0/\">"))
        assertFalse(Discovery.looksLikeFeed("<!DOCTYPE html><html><head><title>News</title>"))
        assertFalse(Discovery.looksLikeFeed("{\"statusCode\":404}"))
    }

    @Test
    fun `guesses are rooted at the host and are bounded`() {
        val guesses = Discovery.guessesFor("https://example.org/some/deep/page?x=1")
        assertTrue(guesses.contains("https://example.org/feed/"))
        assertTrue(guesses.contains("https://example.org/rss.xml"))
        assertEquals(Discovery.GUESSES.size, guesses.size)
        for (guess in guesses) assertTrue(guess.startsWith("https://example.org/"))
    }

    @Test
    fun `an unparseable address produces no guesses rather than an exception`() {
        assertTrue(Discovery.guessesFor("not a url").isEmpty())
    }
}
