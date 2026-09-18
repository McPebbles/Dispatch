package com.dispatch.reader.widget

import com.dispatch.reader.data.Article

/**
 * Choosing the handful of stories a home-screen widget shows.
 *
 * The rule the widget is built around: **at most [DEFAULT_PER_FEED] articles
 * from any one feed**, so that a stream containing both a wire service that
 * publishes forty times an hour and a magazine that publishes twice a day does
 * not show forty wire stories.
 *
 * ## Why a strict cap is the wrong rule on its own
 *
 * A cap of three works when a stream has ten feeds. It fails when a stream has
 * two: three plus three is six, and the widget then shows six stories in a
 * space built for ten, with four empty rows, forever. The cap exists to mix
 * sources — and with two sources there is nothing to mix.
 *
 * So the cap is applied in **tiers**. Every feed's newest three articles are
 * tier 0, its next three are tier 1, and so on. The widget fills from tier 0
 * first, then tier 1, and stops when it is full or has run out. With many feeds
 * it never leaves tier 0 and behaves exactly like a strict cap; with two feeds
 * it keeps filling and the widget is full. Nothing has to detect which case it
 * is in.
 *
 * ## Display order is recency, not selection order
 *
 * Selection is per feed; display is chronological. The two are separated
 * deliberately: a reader glancing at a widget expects the top row to be the
 * newest thing in it, which is not the order the tiers produce.
 *
 * Free of `android.*`: the whole of this file is exercised on the JVM.
 */
object WidgetMix {

    const val DEFAULT_LIMIT = 10
    const val DEFAULT_PER_FEED = 3

    /**
     * @param candidates newest first. More than [limit] is expected; the caller
     *   over-fetches so that the cap has something to choose between.
     * @param fill when true (the default) the tiers keep going until the widget
     *   is full, so a stream with one or two feeds still shows ten stories.
     *   Set it false for a strict cap that never shows more than [perFeed] from
     *   any feed even if that leaves the widget half empty. Nothing in the app
     *   passes false today; it exists so that the choice is visible and
     *   reversible in one place rather than buried in the loop below.
     */
    fun pick(
        candidates: List<Article>,
        limit: Int = DEFAULT_LIMIT,
        perFeed: Int = DEFAULT_PER_FEED,
        fill: Boolean = true,
    ): List<Article> {
        if (limit <= 0 || candidates.isEmpty()) return emptyList()
        val cap = if (perFeed <= 0) Int.MAX_VALUE else perFeed

        val seenPerFeed = HashMap<Long, Int>()
        // (tier, index) for a stable sort: index preserves the caller's order
        // within a tier, which is recency.
        val tiered = ArrayList<Triple<Int, Int, Article>>(candidates.size)
        candidates.forEachIndexed { index, article ->
            val n = seenPerFeed.getOrDefault(article.feedId, 0)
            seenPerFeed[article.feedId] = n + 1
            val tier = if (cap == Int.MAX_VALUE) 0 else n / cap
            tiered.add(Triple(tier, index, article))
        }

        val chosen = tiered
            .filter { fill || it.first == 0 }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .take(limit)
            .map { it.third }

        return chosen.sortedWith(
            compareByDescending<Article> { it.publishedAt }.thenBy { it.title }
        )
    }

    /**
     * Drop the same story arriving from several feeds.
     *
     * Ten sources carrying one wire story is the failure mode this exists for:
     * without it, a "World" widget on a busy morning is the same headline ten
     * times. Two items are the same story when their links point at the same
     * place, or when their titles match after normalisation.
     *
     * The *first* occurrence wins, so the caller decides precedence by ordering
     * its input — the widget passes newest first.
     */
    fun dedupe(items: List<Article>): List<Article> {
        val seenLinks = HashSet<String>()
        val seenTitles = HashSet<String>()
        val out = ArrayList<Article>(items.size)
        for (item in items) {
            val link = normaliseLink(item.link)
            val title = normaliseTitle(item.title)
            if (link.isNotEmpty() && !seenLinks.add(link)) continue
            if (title.length >= MIN_TITLE_FOR_DEDUPE && !seenTitles.add(title)) continue
            out.add(item)
        }
        return out
    }

    /**
     * Very short headlines ("Live updates") collide across unrelated stories, so
     * title matching is only trusted above this length. Links are always
     * trusted.
     */
    const val MIN_TITLE_FOR_DEDUPE = 24

    /**
     * Strip the parts of a URL that identify the reader rather than the story:
     * scheme, `www.`, tracking query parameters, fragment and trailing slash.
     *
     * BBC appends `?at_medium=RSS&at_campaign=rss` to every link in its feed,
     * so the same article reached from two BBC feeds has two different URLs.
     */
    fun normaliseLink(url: String): String {
        var s = url.trim().lowercase()
        if (s.isEmpty()) return ""
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        s = s.substringBefore('#')
        val q = s.indexOf('?')
        if (q >= 0) {
            val path = s.substring(0, q)
            val kept = s.substring(q + 1)
                .split('&')
                .filter { param ->
                    val key = param.substringBefore('=')
                    key.isNotEmpty() && TRACKING_PARAMS.none { key == it || key.startsWith(it) }
                }
                .sorted()
            s = if (kept.isEmpty()) path else path + "?" + kept.joinToString("&")
        }
        return s.trimEnd('/')
    }

    private val TRACKING_PARAMS = listOf(
        "utm_", "at_medium", "at_campaign", "at_custom", "at_bbc", "cmpid", "cmp",
        "ito", "ns_", "smid", "partner", "ref", "fbclid", "gclid", "mc_cid", "mc_eid",
        "sh_kit", "__source", "taid", "ocid", "srnd"
    )

    /**
     * A headline reduced to its words: lower case, no punctuation, no runs of
     * space. Two wires' renderings of one headline usually differ only in
     * quotation marks and dashes.
     */
    fun normaliseTitle(title: String): String {
        val out = StringBuilder(title.length)
        var space = false
        for (ch in title.lowercase()) {
            val keep = ch.isLetterOrDigit()
            if (keep) {
                if (space && out.isNotEmpty()) out.append(' ')
                space = false
                out.append(ch)
            } else {
                space = true
            }
        }
        return out.toString()
    }
}
