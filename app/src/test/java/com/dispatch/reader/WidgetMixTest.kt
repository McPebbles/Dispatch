package com.dispatch.reader

import com.dispatch.reader.data.Article
import com.dispatch.reader.widget.WidgetMix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget's selection rule: ten stories, never more than three from one feed
 * — and a full widget even when the stream has only two feeds in it.
 */
class WidgetMixTest {

    private var clock = 2_000_000_000_000L

    /** Newest first, one minute apart, in the order they are created. */
    private fun article(feedId: Long, title: String = "t${clock}"): Article {
        clock -= 60_000L
        return Article(
            id = clock,
            feedId = feedId,
            guid = "g$clock",
            link = "https://example.org/$feedId/$clock",
            title = title,
            publishedAt = clock,
            feedTitle = "Feed $feedId",
        )
    }

    // ------------------------------------------------------------ the cap

    @Test
    fun `no more than three from one feed when there is plenty of choice`() {
        val candidates = ArrayList<Article>()
        // Five feeds, ten articles each, interleaved newest-first.
        repeat(10) { for (feed in 1L..5L) candidates.add(article(feed)) }

        val picked = WidgetMix.pick(candidates)
        assertEquals(10, picked.size)
        val perFeed = picked.groupingBy { it.feedId }.eachCount()
        for ((feed, count) in perFeed) {
            assertTrue("feed $feed got $count", count <= 3)
        }
    }

    @Test
    fun `one loud feed cannot take the whole widget`() {
        val candidates = ArrayList<Article>()
        // A wire service publishing forty times to everyone else's twice.
        repeat(40) { candidates.add(article(1L)) }
        repeat(2) { candidates.add(article(2L)) }
        repeat(2) { candidates.add(article(3L)) }

        val picked = WidgetMix.pick(candidates)
        // Tier 0 is 3 + 2 + 2 = seven stories, which is all a strict cap can
        // produce here. The tiers then top the widget up from the only feed
        // that has anything left, so the loud feed ends with six of ten — not
        // the forty it would have taken with no rule at all, and not a widget
        // that is 30% empty.
        assertEquals(10, picked.size)
        assertEquals(6, picked.count { it.feedId == 1L })
        assertEquals(2, picked.count { it.feedId == 2L })
        assertEquals(2, picked.count { it.feedId == 3L })

        // With fill = false the cap is absolute and the widget shows seven.
        val strict = WidgetMix.pick(candidates, fill = false)
        assertEquals(7, strict.size)
        assertEquals(3, strict.count { it.feedId == 1L })
    }

    /**
     * The case a strict cap gets wrong.
     *
     * Two feeds and a cap of three is six stories in a widget built for ten —
     * four empty rows, forever. The cap exists to mix sources, and with two
     * sources there is nothing to mix, so the tiers keep filling.
     */
    @Test
    fun `a two-feed stream still fills the widget`() {
        val candidates = ArrayList<Article>()
        // Interleaved, as a recency-ordered query returns two equally busy
        // feeds. Selection inside a tier is by recency, so how evenly the last
        // rows divide follows from who published most recently — which is the
        // right answer, not a quota.
        repeat(20) { for (feed in 1L..2L) candidates.add(article(feed)) }

        val picked = WidgetMix.pick(candidates)
        assertEquals(10, picked.size)
        assertEquals(5, picked.count { it.feedId == 1L })
        assertEquals(5, picked.count { it.feedId == 2L })
    }

    @Test
    fun `when one feed is newer throughout it takes the topped-up rows`() {
        // Everything from feed 1 is newer than everything from feed 2. Tier 0
        // is still three each; the four rows that top the widget up go to the
        // newer feed, because inside a tier the order is recency.
        val candidates = ArrayList<Article>()
        repeat(20) { candidates.add(article(1L)) }
        repeat(20) { candidates.add(article(2L)) }

        val picked = WidgetMix.pick(candidates)
        assertEquals(10, picked.size)
        assertEquals(6, picked.count { it.feedId == 1L })
        assertEquals(4, picked.count { it.feedId == 2L })
    }

    @Test
    fun `a single-feed stream fills the widget from that feed`() {
        val candidates = (1..20).map { article(1L) }
        assertEquals(10, WidgetMix.pick(candidates).size)
        // …and is the clearest case for why fill exists: strictly capped, a
        // one-feed stream would show three stories in a widget built for ten.
        assertEquals(3, WidgetMix.pick(candidates, fill = false).size)
    }

    @Test
    fun `fewer candidates than slots returns all of them`() {
        val candidates = listOf(article(1L), article(2L), article(3L))
        assertEquals(3, WidgetMix.pick(candidates).size)
    }

    @Test
    fun `an empty stream picks nothing rather than throwing`() {
        assertEquals(0, WidgetMix.pick(emptyList()).size)
        assertEquals(0, WidgetMix.pick(listOf(article(1L)), limit = 0).size)
    }

    // -------------------------------------------------------------- order

    @Test
    fun `the result is in recency order, not selection order`() {
        val candidates = ArrayList<Article>()
        repeat(6) { for (feed in 1L..2L) candidates.add(article(feed)) }

        val picked = WidgetMix.pick(candidates)
        val times = picked.map { it.publishedAt }
        assertEquals(times.sortedDescending(), times)
    }

    @Test
    fun `selection prefers each feed's newest`() {
        val candidates = ArrayList<Article>()
        repeat(6) { for (feed in 1L..4L) candidates.add(article(feed)) }
        val picked = WidgetMix.pick(candidates)
        // Whatever is chosen from a feed must be that feed's newest, not a
        // random three.
        for (feed in 1L..4L) {
            val fromFeed = candidates.filter { it.feedId == feed }
            val chosen = picked.filter { it.feedId == feed }
            assertEquals(
                fromFeed.take(chosen.size).map { it.id }.toSet(),
                chosen.map { it.id }.toSet(),
            )
        }
    }

    // ------------------------------------------------------------- dedupe

    @Test
    fun `the same wire story from several feeds appears once`() {
        val shared = "Rates held as inflation forecast rises again"
        val a = article(1L, shared)
        val b = article(2L, shared)
        val c = article(3L, "Something else entirely happened today")
        val out = WidgetMix.dedupe(listOf(a, b, c))
        assertEquals(2, out.size)
        assertEquals(a.id, out[0].id)
    }

    @Test
    fun `a short headline is not deduped by title`() {
        // "Live updates" collides across unrelated stories.
        val a = article(1L, "Live updates")
        val b = article(2L, "Live updates")
        assertEquals(2, WidgetMix.dedupe(listOf(a, b)).size)
    }

    @Test
    fun `the same article from two feeds of one publisher is deduped by link`() {
        // BBC appends ?at_medium=RSS&at_campaign=rss to every link, so the same
        // article reached from the World feed and the Top Stories feed has two
        // different URLs and two different headline renderings.
        val base = "https://www.bbc.co.uk/news/articles/c8e3rk47r4ego"
        val a = Article(1, 1, "g1", "$base?at_medium=RSS&at_campaign=rss", "One", publishedAt = 10)
        val b = Article(2, 2, "g2", "$base?at_medium=RSS&at_campaign=rss#comments", "Two", publishedAt = 9)
        assertEquals(1, WidgetMix.dedupe(listOf(a, b)).size)
    }

    @Test
    fun `link normalisation strips tracking parameters and keeps the rest`() {
        assertEquals(
            "bbc.co.uk/news/articles/abc",
            WidgetMix.normaliseLink("https://www.bbc.co.uk/news/articles/abc?at_medium=RSS&at_campaign=rss"),
        )
        assertEquals(
            "example.org/story?id=7",
            WidgetMix.normaliseLink("http://example.org/story?id=7&utm_source=feed"),
        )
        // A parameter that identifies the story is not tracking and must stay.
        assertEquals(
            "example.org/view?article=91&page=2",
            WidgetMix.normaliseLink("https://example.org/view?page=2&article=91"),
        )
        assertEquals("", WidgetMix.normaliseLink("   "))
    }

    @Test
    fun `title normalisation ignores punctuation and case`() {
        assertEquals(
            WidgetMix.normaliseTitle("‘We are prey’: seven bodies found"),
            WidgetMix.normaliseTitle("\"We are prey\" — Seven bodies found."),
        )
    }
}
