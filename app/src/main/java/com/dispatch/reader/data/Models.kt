package com.dispatch.reader.data

/**
 * The app's nouns. Free of `android.*` and of SQL: these are what the rest of
 * the app passes around, and what the pure-JVM tests construct.
 */

data class Feed(
    val id: Long,
    /** The feed document's address. Unique; this is the feed's identity. */
    val url: String,
    /** What to call it. The feed's own `<title>` unless the user renamed it. */
    val title: String,
    /** The publication's site, used for the "open site" action and nothing else. */
    val siteLink: String? = null,
    val iconUrl: String? = null,
    /**
     * The one category this feed is filed under, or null.
     *
     * A label, not a container: a feed's category is how it is *found* — in the
     * library's filter, and by a stream that says "everything tagged Sports".
     * One per feed, because a feed that is in three categories is really three
     * feeds' worth of ambiguity every time someone searches.
     */
    val category: String? = null,
    val addedAt: Long = 0,
    val lastFetchAt: Long = 0,
    /** Empty when the last fetch succeeded. Shown in the library when it is not. */
    val lastError: String? = null,
    /** How many articles the last fetch produced. Zero for a long time is a dead feed. */
    val lastCount: Int = 0,
    val etag: String? = null,
    val lastModified: String? = null,
) {
    /** A short label for the source, used on article rows and in the widget. */
    val shortTitle: String
        get() {
            val cut = title.indexOf(" — ")
            return if (cut > 0) title.substring(0, cut) else title
        }
}

/**
 * One story, as a feed gave it to us.
 *
 * Everything here comes out of the feed document; Dispatch never fetches the
 * article page itself. [bodyHtml] is whatever description or content the feed
 * carried, sanitised and capped at [Db.BODY_CHARS]; [summary] is the same text
 * flattened to plain text and capped at [Db.SUMMARY_CHARS] for list rows. Both
 * can be absent — plenty of feeds carry a headline and a link and nothing else
 * — which is why the article screen always offers the click-out.
 *
 * [guid] is the feed's own identifier for the item and is what deduplicates a
 * story across refreshes; it falls back to the link when a feed omits it. The
 * pair (feedId, guid) is unique, so an item that is edited upstream updates in
 * place rather than arriving twice.
 *
 * [read] and [saved] are the reader's, not the feed's, and they are the reason
 * retention never deletes a saved story.
 *
 * [feedTitle] is joined in for display and is not stored on the article row.
 */
data class Article(
    val id: Long,
    val feedId: Long,
    /** The feed's identifier for this item; the link when the feed gives none. */
    val guid: String,
    val link: String,
    val title: String,
    val author: String? = null,
    /** Plain text, for list rows. */
    val summary: String? = null,
    /** Sanitised HTML, for the article screen. */
    val bodyHtml: String? = null,
    val imageUrl: String? = null,
    /** Epoch millis. The feed's date when it had one, else when we first saw it. */
    val publishedAt: Long = 0,
    val fetchedAt: Long = 0,
    val read: Boolean = false,
    val saved: Boolean = false,
    /** Joined from the feed for display. Empty when the query did not ask for it. */
    val feedTitle: String = "",
)

/**
 * A named view over the feed library.
 *
 * A stream holds **two** kinds of membership and the union of them is what it
 * shows:
 *
 *  - [feedCount] feeds picked one by one, and
 *  - [categoryCount] whole categories, which keep working: a feed tagged
 *    "Sports" next month joins every stream that asked for Sports, without the
 *    reader having to remember to go back and tick it.
 *
 * That is the difference between a stream and a folder, and it is why the
 * bundled hundred are a *library* rather than seven ready-made streams.
 */
data class Stream(
    val id: Long,
    val name: String,
    val createdAt: Long = 0,
    /** Feeds picked explicitly. Filled in by list queries, not stored on the row. */
    val feedCount: Int = 0,
    /** Whole categories included. Likewise derived. */
    val categoryCount: Int = 0,
    /**
     * How often this stream's feeds are refreshed, in minutes.
     *
     * [REFRESH_DEFAULT] means "use the app-wide interval from Settings", which
     * is what a stream gets unless the reader says otherwise. A feed that
     * belongs to several streams is refreshed on the **shortest** of their
     * cadences — the reader asked for it that often somewhere, and a feed
     * cannot be fetched twice at different rates.
     */
    val refreshMinutes: Int = REFRESH_DEFAULT,
) {
    val isAll: Boolean get() = id == ALL_ID

    /** True when this stream would show nothing at all. */
    val isEmpty: Boolean get() = feedCount == 0 && categoryCount == 0

    companion object {
        /**
         * "All feeds" is not a row.
         *
         * It has no membership and cannot be renamed, deleted or emptied, so
         * making it a real stream would mean guarding every one of those
         * operations against one magic row. Instead it is an id the query layer
         * understands: [ALL_ID] means "no feed filter at all", which is also
         * exactly what a brand-new install should show while the reader is
         * still deciding what they want.
         */
        const val ALL_ID = 0L

        /** Saved stories, likewise a query rather than a row. */
        const val SAVED_ID = -1L

        /** [refreshMinutes] value meaning "whatever Settings says". */
        const val REFRESH_DEFAULT = 0

        /**
         * WorkManager's floor for periodic work. Anything shorter is silently
         * rounded up by the platform, so offering it would be a lie.
         */
        const val REFRESH_FLOOR_MINUTES = 15
    }
}

/**
 * A label a feed can be filed under.
 *
 * Categories are a real table rather than just the distinct values of
 * `feeds.category`, for one reason: a reader must be able to make a category
 * *before* there is anything in it. "Local" with nothing tagged yet has to
 * survive until they add the feed they made it for.
 */
data class Category(
    val name: String,
    /** True for the seven that shipped with the app. They are not privileged — only labelled. */
    val builtin: Boolean = false,
    /** How many feeds carry this label. Derived. */
    val feedCount: Int = 0,
)

/**
 * The seven categories the bundled feeds arrive tagged with.
 *
 * They are **filing labels, not streams**. Nothing in the app treats them
 * specially once the first run is over: they can be renamed, deleted, or joined
 * by as many of the reader's own as they like. Their whole job is to make a
 * hundred feeds searchable while the reader builds the streams they actually
 * want.
 */
object Categories {
    const val WORLD = "World"
    const val POLITICS = "Politics & Geopolitics"
    const val SCITECH = "Science & Technology"
    const val HEALTH = "Health & Wellness"
    const val SPORTS = "Sports"
    const val BUSINESS = "Business"
    const val ENTERTAINMENT = "Entertainment & Pop Culture"

    val ORDER: List<String> = listOf(WORLD, POLITICS, BUSINESS, SCITECH, HEALTH, SPORTS, ENTERTAINMENT)
}
