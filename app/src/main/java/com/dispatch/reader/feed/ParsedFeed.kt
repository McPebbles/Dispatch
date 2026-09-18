package com.dispatch.reader.feed

/**
 * What a parse produced. Deliberately dumb data: no `android.*`, no database
 * identity, nothing that knows how the app stores things.
 */
data class ParsedItem(
    /** The feed's own identifier if it gave a usable one, else the link. */
    val guid: String?,
    /** Where the article lives. Null means the item is unusable and is dropped. */
    val link: String?,
    val title: String,
    /** `dc:creator`, an Atom `<author><name>`, or an RSS `<author>`. Often absent. */
    val author: String?,
    /** The richest body the feed offered, still as HTML. */
    val summaryHtml: String?,
    /** Epoch millis, or null when the feed gave no date this app could read. */
    val publishedAt: Long?,
    /** Absolute https URL of the article's picture, or null. */
    val imageUrl: String?,
)

data class ParsedFeed(
    val title: String?,
    /** The publication's own site, from `<link>` or an Atom alternate link. */
    val siteLink: String?,
    val description: String?,
    /** The channel's `<image><url>`, when it has one. */
    val iconUrl: String?,
    val items: List<ParsedItem>,
) {
    companion object {
        val EMPTY = ParsedFeed(null, null, null, null, emptyList())
    }
}

/** Why a fetch or parse did not produce a feed. Shown in Manage feeds. */
enum class FeedError {
    NONE,
    NETWORK,
    HTTP_STATUS,
    NOT_A_FEED,
    EMPTY,
    TOO_LARGE,
}
