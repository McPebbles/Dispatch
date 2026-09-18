package com.dispatch.reader

import com.dispatch.reader.feed.Sanitize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SanitizeTest {

    // ------------------------------------------------------------- xmlSafe

    @Test
    fun `a known html entity becomes a numeric reference`() {
        assertEquals("<a>&#160;</a>", Sanitize.xmlSafe("<a>&nbsp;</a>"))
        assertEquals("<a>&#8212;</a>", Sanitize.xmlSafe("<a>&mdash;</a>"))
        assertEquals("<a>&#233;</a>", Sanitize.xmlSafe("<a>&eacute;</a>"))
    }

    @Test
    fun `the five xml entities and numeric references are left alone`() {
        val input = "<a>&amp;&lt;&gt;&quot;&apos;&#160;&#xA0;</a>"
        assertEquals(input, Sanitize.xmlSafe(input))
    }

    @Test
    fun `an unknown entity is escaped so it survives as text`() {
        // Not dropped: a parser would refuse the document, and deleting it
        // would silently change what the publisher wrote.
        assertEquals("<a>&amp;unknownthing;</a>", Sanitize.xmlSafe("<a>&unknownthing;</a>"))
    }

    @Test
    fun `a bare ampersand is escaped`() {
        assertEquals("<a>Tom &amp; Jerry</a>", Sanitize.xmlSafe("<a>Tom & Jerry</a>"))
        assertEquals(
            "<a href=\"https://x/?a=1&amp;b=2\"/>",
            Sanitize.xmlSafe("<a href=\"https://x/?a=1&b=2\"/>"),
        )
    }

    @Test
    fun `CDATA is copied through untouched`() {
        // Inside CDATA, &nbsp; IS the six characters. Rewriting it would corrupt
        // the content rather than repair it — and BBC, NPR and The Verge all
        // wrap their bodies in CDATA.
        val input = "<a><![CDATA[keep &nbsp; and & as-is]]></a>"
        assertEquals(input, Sanitize.xmlSafe(input))
    }

    @Test
    fun `anything before the first angle bracket is dropped`() {
        // A byte-order mark, or the blank line some publishing systems emit
        // above the declaration, makes a parser refuse the whole document.
        assertEquals("<rss/>", Sanitize.xmlSafe("﻿\n  <rss/>"))
    }

    @Test
    fun `characters xml forbids are removed`() {
        assertEquals("<a>ab</a>", Sanitize.xmlSafe("<a>a\u0001b</a>"))
        // Tab, newline and carriage return are legal and must survive.
        assertEquals("<a>a\tb\nc\rd</a>", Sanitize.xmlSafe("<a>a\tb\nc\rd</a>"))
    }

    // ---------------------------------------------------------- htmlToText

    @Test
    fun `block tags become spaces so paragraphs do not run together`() {
        assertEquals(
            "One. Two.",
            Sanitize.htmlToText("<p>One.</p><p>Two.</p>"),
        )
    }

    @Test
    fun `entities are decoded and whitespace collapsed`() {
        assertEquals(
            "Rates hold — café prices up & holding",
            Sanitize.htmlToText("Rates   hold &mdash;\n caf&eacute; prices up &amp; holding"),
        )
    }

    @Test
    fun `script and style content does not become body text`() {
        assertEquals(
            "Before After",
            Sanitize.htmlToText("Before<script>var x = 1;</script><style>p{color:red}</style>After"),
        )
    }

    @Test
    fun `comments are dropped`() {
        assertEquals("Visible", Sanitize.htmlToText("<!-- hidden -->Visible"))
    }

    @Test
    fun `null and empty are empty`() {
        assertEquals("", Sanitize.htmlToText(null))
        assertEquals("", Sanitize.htmlToText(""))
    }

    // ---------------------------------------------------------- firstImage

    @Test
    fun `the first real image wins and a tracking pixel never does`() {
        val html = "<p>Story</p>" +
            "<img src=\"https://cdn.example.org/photo.jpg\" width=\"600\" />" +
            "<img src=\"https://media.npr.org/include/images/tracking/npr-rss-pixel.png?story=1\" />"
        assertEquals("https://cdn.example.org/photo.jpg", Sanitize.firstImage(html))
    }

    @Test
    fun `a beacon before the photograph is skipped, not chosen`() {
        // This is NPR's shape with the order reversed, which is the version that
        // would put a tracker on the home screen.
        val html = "<img src='https://x.example/pixel/1x1.gif' width='1' height='1'/>" +
            "<img src='https://cdn.example.org/photo.jpg'/>"
        assertEquals("https://cdn.example.org/photo.jpg", Sanitize.firstImage(html))
    }

    @Test
    fun `a tiny declared size is rejected even with an innocent name`() {
        val html = "<img src=\"https://cdn.example.org/spacer.png\" width=\"1\" height=\"1\" />"
        assertNull(Sanitize.firstImage(html))
    }

    @Test
    fun `data-src is read when src is absent`() {
        val html = "<img data-src=\"https://cdn.example.org/lazy.jpg\" />"
        assertEquals("https://cdn.example.org/lazy.jpg", Sanitize.firstImage(html))
    }

    @Test
    fun `a relative or non-http source is not returned as absolute`() {
        assertNull(Sanitize.firstImage("<img src=\"/local/photo.jpg\" />"))
        assertNull(Sanitize.firstImage("<img src=\"data:image/gif;base64,R0lGOD\" />"))
        assertNull(Sanitize.firstImage("no images here"))
        assertNull(Sanitize.firstImage(null))
    }

    @Test
    fun `attribute reading does not confuse one name for another`() {
        val tag = "<img data-src='https://a/one.jpg' src='https://a/two.jpg' width=600>"
        assertEquals("https://a/two.jpg", Sanitize.attr(tag, "src"))
        assertEquals("https://a/one.jpg", Sanitize.attr(tag, "data-src"))
        assertEquals("600", Sanitize.attr(tag, "width"))
        assertNull(Sanitize.attr(tag, "height"))
    }

    // --------------------------------------------------------------- misc

    @Test
    fun `clip cuts on a word boundary and marks the cut`() {
        assertEquals("hello world", Sanitize.clip("hello world", 20))
        val clipped = Sanitize.clip("the quick brown fox jumps over the lazy dog", 20)
        assertTrue(clipped.endsWith("…"))
        assertTrue(clipped.length <= 21)
        assertFalse(clipped.contains("jumps"))
    }

    @Test
    fun `https upgrades http and refuses everything else`() {
        assertEquals("https://x.org/a", Sanitize.https("http://x.org/a"))
        assertEquals("https://x.org/a", Sanitize.https("https://x.org/a"))
        assertEquals("https://x.org/a", Sanitize.https("//x.org/a"))
        assertEquals("https://x.org/a", Sanitize.https("  https://x.org/a  "))
        assertNull(Sanitize.https("ftp://x.org/a"))
        assertNull(Sanitize.https("javascript:alert(1)"))
        assertNull(Sanitize.https("/relative"))
        assertNull(Sanitize.https(null))
        assertNull(Sanitize.https(""))
    }

    @Test
    fun `tracking pixels are recognised by the names they actually use`() {
        assertTrue(Sanitize.isTrackingPixel("https://media.npr.org/include/images/tracking/npr-rss-pixel.png"))
        assertTrue(Sanitize.isTrackingPixel("https://x.example/1x1.gif"))
        assertTrue(Sanitize.isTrackingPixel("https://sb.scorecardresearch.com/p?c1=2"))
        assertFalse(Sanitize.isTrackingPixel("https://i.guim.co.uk/img/media/abc/master/2981.jpg"))
        assertFalse(Sanitize.isTrackingPixel("https://ichef.bbci.co.uk/ace/standard/240/live/92718a00.jpg"))
    }
}
