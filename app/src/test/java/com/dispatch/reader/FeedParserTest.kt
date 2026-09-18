package com.dispatch.reader

import com.dispatch.reader.feed.FeedParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser, against markup read off live servers on 17 September 2026.
 *
 * Every fixture in `src/test/resources/fixtures/` is real: the shapes are the
 * publishers' own, trimmed to two or three items. That matters more than it
 * sounds — this project's Threadbare app spent five releases fixing a bug
 * against fixtures derived from secondary sources, and the standing lesson is
 * that a fixture from anywhere but the live server proves the code matches the
 * source it was copied from, not the site.
 *
 * `broken_entities.xml` is the one exception and is labelled as synthetic: it
 * exists to exercise [com.dispatch.reader.feed.Sanitize.xmlSafe], and no real
 * publisher would be a useful fixture for "every repair at once".
 */
class FeedParserTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes().toString(Charsets.UTF_8)

    // ------------------------------------------------------------ RSS 2.0

    @Test
    fun `bbc rss2 reads titles links thumbnails and dates`() {
        val feed = FeedParser.parse(fixture("bbc_rss2.xml"))

        assertEquals("BBC News", feed.title)
        assertEquals(2, feed.items.size)

        val first = feed.items[0]
        assertTrue(first.title.startsWith("'Grief can colour memory'"))
        assertEquals(
            "https://www.bbc.co.uk/news/articles/c8e3rk47r4ego?at_medium=RSS&at_campaign=rss",
            first.link,
        )
        assertEquals(
            "https://ichef.bbci.co.uk/ace/standard/240/cpsprodpb/c3f8/live/92718a00.jpg",
            first.imageUrl,
        )
        // Wed, 16 Sep 2026 20:46:30 GMT
        assertEquals(1789591590000L, first.publishedAt)
        // BBC's feeds carry no author at all, and the widget's byline has to
        // cope with that rather than printing "null".
        assertNull(first.author)
    }

    @Test
    fun `a channel image does not overwrite the feed title or link`() {
        // BBC's <image> block contains its own <title> and <link>. A parser that
        // does not track it ends up with the feed's link pointing at the logo's
        // target — here, deliberately a different URL from the channel's.
        val feed = FeedParser.parse(fixture("bbc_rss2.xml"))
        assertEquals("BBC News", feed.title)
        assertEquals("https://www.bbc.co.uk/news", feed.siteLink)
        assertEquals(
            "https://news.bbcimg.co.uk/nol/shared/img/bbc_news_120x60.gif",
            feed.iconUrl,
        )
    }

    @Test
    fun `guardian picks the widest media content and reads dc creator`() {
        val feed = FeedParser.parse(fixture("guardian_rss2.xml"))
        val item = feed.items.single()

        assertEquals("Rachel Savage in Johannesburg", item.author)
        // Three <media:content> at 140, 460 and 700; the widest wins.
        assertTrue(item.imageUrl!!.contains("width=700"))
        // The description is escaped HTML; the title's entities are decoded.
        assertTrue(item.title.startsWith("‘We are prey’"))
        assertTrue(item.summaryHtml!!.contains("<p>"))
    }

    @Test
    fun `npr prefers content encoded and skips the tracking pixel`() {
        val feed = FeedParser.parse(fixture("npr_rss2.xml"))
        val item = feed.items.single()

        assertEquals("Teri Schultz", item.author)
        // The only images in this item are inside content:encoded: a real
        // photograph and a 1x1 analytics beacon at the end. Choosing the beacon
        // would put a tracking request on the home screen for every NPR story.
        assertTrue(item.imageUrl!!.contains("gettyimages"))
        assertTrue(!item.imageUrl!!.contains("npr-rss-pixel"))
        // content:encoded outranks <description> as the body.
        assertTrue(item.summaryHtml!!.contains("<p>"))
        // Wed, 16 Sep 2026 16:08:27 -0400
        assertEquals(1789589307000L, item.publishedAt)
    }

    @Test
    fun `cbs sports trims padded text and ignores a non-url guid`() {
        val feed = FeedParser.parse(fixture("cbssports_rss2.xml"))
        val item = feed.items.single()

        // Every element's text is wrapped over several lines and indented.
        assertTrue(item.title.startsWith("Lionel Messi"))
        assertTrue(!item.title.contains("\n"))
        assertEquals(
            "https://www.cbssports.com/soccer/news/lionel-messis-100th-inter-miami-goal/",
            item.link,
        )
        // guid isPermaLink="false" holding a UUID must never become the link.
        assertEquals("cd8a5446-cc44-4772-9fcd-3a24b39d1a08", item.guid)
        assertEquals(
            "https://sportshub.cbsistatic.com/i/2026/09/17/64e9b936/messi.jpg",
            item.imageUrl,
        )
        assertEquals("Chuck Booth", item.author)
    }

    // --------------------------------------------------------------- Atom

    @Test
    fun `atom entries use the alternate link and the author name`() {
        val feed = FeedParser.parse(fixture("verge_atom.xml"))
        assertEquals("The Verge", feed.title)

        val item = feed.items.single()
        assertEquals("Andrew Webster", item.author)
        // <id> is https://www.theverge.com/?p=996314 and must not be the link;
        // rel="self" on the feed must not be the site link either.
        assertEquals(
            "https://www.theverge.com/entertainment/996314/tiff-2026-halloween-streaming",
            item.link,
        )
        assertEquals("https://www.theverge.com/", feed.siteLink)
        assertEquals("The streamers are fighting over Halloween", item.title)
        // 2026-09-16T23:00:00-04:00
        assertEquals(1789614000000L, item.publishedAt)
        // The picture is only in <content>, inside a <figure>.
        assertTrue(item.imageUrl!!.contains("NUP_211117.jpg"))
    }

    // ---------------------------------------------------------- RSS 1.0

    @Test
    fun `rdf items are found even though they are siblings of the channel`() {
        // In RSS 1.0 <item> is NOT inside <channel>, and the channel holds an
        // <items><rdf:Seq> table of contents that is not content at all. A
        // parser that only looks inside <channel> finds nothing here.
        val feed = FeedParser.parse(fixture("nature_rdf.xml"))

        assertEquals("Nature", feed.title)
        assertEquals(2, feed.items.size)
        val first = feed.items[0]
        assertEquals("https://www.nature.com/articles/s41586-026-11110-5", first.link)
        // Two <dc:creator> elements: both, joined, not just the first.
        assertEquals("Bart De Strooper, Eric Karran", first.author)
        // <dc:date> is a bare date.
        assertNotNull(first.publishedAt)
    }

    // ------------------------------------------------------------ repairs

    @Test
    fun `undeclared html entities do not stop the parse`() {
        // A strict XML parser is REQUIRED to fail on &mdash; and &nbsp;, which
        // publishers' templates emit constantly. This document also opens with
        // a byte-order mark and a blank line before the declaration, has a bare
        // & in a URL, and carries an entity nothing knows.
        val feed = FeedParser.parse(fixture("broken_entities.xml"))
        val item = feed.items.single()

        assertEquals(1, feed.items.size)
        assertTrue(item.title.contains("—"))
        assertTrue(item.title.contains("café"))
        assertEquals("https://example.org/a?x=1&y=2", item.link)
        // The 1x1 gif is rejected on both its name and its declared size; the
        // real picture is chosen.
        assertEquals("https://example.org/real.jpg", item.imageUrl)
    }

    @Test
    fun `an empty or junk document is empty rather than an exception`() {
        assertEquals(0, FeedParser.parse("").items.size)
        assertEquals(0, FeedParser.parse("not xml at all").items.size)
        assertEquals(0, FeedParser.parse("<html><body>hello</body></html>").items.size)
        assertEquals(0, FeedParser.parse("<rss><channel><item>").items.size)
    }

    @Test
    fun `an item with no link is dropped, not stored with an empty one`() {
        val xml = """
            <rss version="2.0"><channel><title>T</title>
              <item><title>No link here</title></item>
              <item><title>Has one</title><link>https://example.org/a</link></item>
            </channel></rss>
        """.trimIndent()
        val feed = FeedParser.parse(xml)
        assertEquals(1, feed.items.size)
        assertEquals("Has one", feed.items[0].title)
    }

    @Test
    fun `relative links resolve against the feed's own address`() {
        val xml = """
            <rss version="2.0"><channel><title>T</title>
              <item><title>Relative</title><link>/story/1</link></item>
            </channel></rss>
        """.trimIndent()
        val feed = FeedParser.parse(xml, "https://example.org/feed/rss.xml")
        assertEquals("https://example.org/story/1", feed.items.single().link)
    }

    @Test
    fun `http links are upgraded rather than dropped`() {
        // The app's network security config forbids cleartext, so an http link
        // would fail. Every publisher redirects to https anyway.
        val xml = """
            <rss version="2.0"><channel><title>T</title>
              <item><title>Old</title><link>http://example.org/story</link></item>
            </channel></rss>
        """.trimIndent()
        assertEquals("https://example.org/story", FeedParser.parse(xml).items.single().link)
    }

    @Test
    fun `the item cap bounds a hostile feed`() {
        val items = (1..FeedParser.MAX_ITEMS + 50).joinToString("") {
            "<item><title>Item $it</title><link>https://example.org/$it</link></item>"
        }
        val feed = FeedParser.parse("<rss version=\"2.0\"><channel><title>T</title>$items</channel></rss>")
        assertEquals(FeedParser.MAX_ITEMS, feed.items.size)
    }

    @Test
    fun `an external entity is not resolved`() {
        // The XXE case: a feed is a document from a stranger. Two defences
        // have to hold — Sanitize.xmlSafe escapes a reference to an entity it
        // does not know, and the parser resolves no external entity even if one
        // reached it.
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <rss version="2.0"><channel><title>T</title>
              <item><title>&xxe;</title><link>https://example.org/a</link></item>
            </channel></rss>
        """.trimIndent()
        val feed = FeedParser.parse(xml)
        val title = feed.items.firstOrNull()?.title.orEmpty()
        assertTrue("an entity was expanded: $title", !title.contains("root:"))
    }
}
