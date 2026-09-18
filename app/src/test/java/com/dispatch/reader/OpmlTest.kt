package com.dispatch.reader

import com.dispatch.reader.data.Opml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OPML is three things at once here — the bundled feed set, import, and export —
 * so these tests cover the file that actually ships as well as the format.
 */
class OpmlTest {

    /**
     * The file in `src/main/assets`, not a copy of it.
     *
     * Gradle runs unit tests with the module directory as the working
     * directory, so the shipped asset is readable from here. Testing a copy
     * would prove only that the copy is well-formed.
     */
    private fun shippedDefaults(): String {
        val file = File("src/main/assets/default_feeds.opml")
            .takeIf { it.exists() }
            ?: File("app/src/main/assets/default_feeds.opml")
        assertTrue("bundled OPML not found at ${file.absolutePath}", file.exists())
        return file.readText()
    }

    // ------------------------------------------------------ the shipped set

    @Test
    fun `the bundled set parses into seven categories`() {
        val groups = Opml.parse(shippedDefaults())
        assertEquals(7, groups.size)
        assertEquals(
            listOf(
                "World",
                "Politics & Geopolitics",
                "Business",
                "Science & Technology",
                "Health & Wellness",
                "Sports",
                "Entertainment & Pop Culture",
            ),
            groups.map { it.name },
        )
    }

    @Test
    fun `the bundled set is a hundred feeds and every one is distinct`() {
        val groups = Opml.parse(shippedDefaults())
        val feeds = groups.flatMap { it.feeds }
        assertEquals(100, feeds.size)
        assertEquals(100, feeds.map { it.url }.toSet().size)
        assertEquals(100, feeds.map { it.title }.toSet().size)
    }

    @Test
    fun `every bundled feed is https and has a title`() {
        // The app's network security config forbids cleartext, so an http entry
        // would be a feed that can never load.
        for (feed in Opml.parse(shippedDefaults()).flatMap { it.feeds }) {
            assertTrue(feed.url, feed.url.startsWith("https://"))
            assertTrue(feed.url, feed.title.isNotBlank())
        }
    }

    @Test
    fun `the category counts match what the app promises`() {
        val byName = Opml.parse(shippedDefaults()).associate { it.name to it.feeds.size }
        assertEquals(20, byName["World"])
        assertEquals(20, byName["Politics & Geopolitics"])
        assertEquals(20, byName["Business"])
        assertEquals(10, byName["Science & Technology"])
        assertEquals(10, byName["Health & Wellness"])
        assertEquals(10, byName["Sports"])
        assertEquals(10, byName["Entertainment & Pop Culture"])
    }

    @Test
    fun `an ampersand in a feed url survives the parse`() {
        // Euronews and CNBC both carry a query string with an &amp; in it, and
        // an entity-handling bug here would produce a URL that 404s.
        val urls = Opml.parse(shippedDefaults()).flatMap { it.feeds }.map { it.url }
        val euronews = urls.first { it.contains("euronews") }
        assertEquals("https://www.euronews.com/rss?level=theme&name=news", euronews)
        assertTrue(urls.any { it.contains("partnerId=wrss01&id=100003114") })
    }

    // -------------------------------------------------------- the format

    @Test
    fun `a flat file with no folders lands in one unnamed group`() {
        val xml = """
            <opml version="1.0"><head><title>Flat</title></head><body>
              <outline type="rss" text="One" xmlUrl="https://a.example/feed" />
              <outline type="rss" text="Two" xmlUrl="https://b.example/feed" />
            </body></opml>
        """.trimIndent()
        val groups = Opml.parse(xml)
        assertEquals(1, groups.size)
        assertNull(groups[0].name)
        assertEquals(2, groups[0].feeds.size)
    }

    @Test
    fun `a feed written as a container does not swallow the ones after it`() {
        // The bug this guards: an <outline xmlUrl=…></outline> written in
        // container form rather than self-closing. A parser that pushes a folder
        // frame only for folders, and pops on every close, mis-files everything
        // after the first such entry.
        val xml = """
            <opml version="2.0"><body>
              <outline text="News">
                <outline type="rss" text="One" xmlUrl="https://a.example/feed"></outline>
                <outline type="rss" text="Two" xmlUrl="https://b.example/feed" />
              </outline>
              <outline text="Tech">
                <outline type="rss" text="Three" xmlUrl="https://c.example/feed" />
              </outline>
            </body></opml>
        """.trimIndent()
        val groups = Opml.parse(xml)
        assertEquals(2, groups.size)
        assertEquals("News", groups[0].name)
        assertEquals(2, groups[0].feeds.size)
        assertEquals("Tech", groups[1].name)
        assertEquals(1, groups[1].feeds.size)
    }

    @Test
    fun `nested folders file under the innermost named one`() {
        val xml = """
            <opml version="2.0"><body>
              <outline text="News">
                <outline text="World">
                  <outline type="rss" text="One" xmlUrl="https://a.example/feed" />
                </outline>
                <outline type="rss" text="Two" xmlUrl="https://b.example/feed" />
              </outline>
            </body></opml>
        """.trimIndent()
        val groups = Opml.parse(xml).associateBy { it.name }
        assertEquals(1, groups["World"]!!.feeds.size)
        assertEquals(1, groups["News"]!!.feeds.size)
    }

    @Test
    fun `text is used when title is absent, and the host when both are`() {
        val xml = """
            <opml version="2.0"><body>
              <outline type="rss" text="From text" xmlUrl="https://a.example/feed" />
              <outline type="rss" xmlUrl="https://www.b.example/feed" />
            </body></opml>
        """.trimIndent()
        val feeds = Opml.parse(xml).single().feeds
        assertEquals("From text", feeds[0].title)
        assertEquals("b.example", feeds[1].title)
    }

    @Test
    fun `an http feed is upgraded and a non-http one is dropped`() {
        val xml = """
            <opml version="2.0"><body>
              <outline type="rss" text="Old" xmlUrl="http://a.example/feed" />
              <outline type="rss" text="Odd" xmlUrl="feed://b.example/feed" />
            </body></opml>
        """.trimIndent()
        val feeds = Opml.parse(xml).single().feeds
        assertEquals(1, feeds.size)
        assertEquals("https://a.example/feed", feeds[0].url)
    }

    @Test
    fun `junk in, empty out`() {
        assertEquals(0, Opml.parse("").size)
        assertEquals(0, Opml.parse("not xml").size)
        assertEquals(0, Opml.parse("<opml><body></body></opml>").size)
    }

    // ------------------------------------------------------- round trip

    @Test
    fun `export then import reproduces the same groups and feeds`() {
        val original = Opml.parse(shippedDefaults())
        val exported = Opml.export(original)
        val reparsed = Opml.parse(exported)

        assertEquals(original.map { it.name }, reparsed.map { it.name })
        assertEquals(
            original.flatMap { it.feeds }.map { it.url },
            reparsed.flatMap { it.feeds }.map { it.url },
        )
        assertEquals(
            original.flatMap { it.feeds }.map { it.title },
            reparsed.flatMap { it.feeds }.map { it.title },
        )
    }

    @Test
    fun `export escapes what would otherwise break the file`() {
        val groups = listOf(
            Opml.Group(
                "Odd & <Ends>",
                listOf(Opml.Entry("A \"quoted\" title & more", "https://x.example/feed?a=1&b=2")),
            )
        )
        val xml = Opml.export(groups)
        assertTrue(xml.contains("&amp;"))
        assertTrue(xml.contains("&quot;") || xml.contains("&#34;"))

        val back = Opml.parse(xml).single()
        assertEquals("Odd & <Ends>", back.name)
        assertEquals("A \"quoted\" title & more", back.feeds.single().title)
        assertEquals("https://x.example/feed?a=1&b=2", back.feeds.single().url)
    }

    @Test
    fun `an unnamed group exports at the top level and comes back unnamed`() {
        val groups = listOf(Opml.Group(null, listOf(Opml.Entry("Loose", "https://x.example/feed"))))
        val back = Opml.parse(Opml.export(groups))
        assertEquals(1, back.size)
        assertNull(back[0].name)
        assertNotNull(back[0].feeds.single())
    }
}
