package com.dispatch.reader.feed

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import javax.xml.parsers.SAXParserFactory

/**
 * One parser for RSS 2.0, RSS 1.0 (RDF) and Atom.
 *
 * ## Why SAX
 *
 * `javax.xml.parsers` ships in both the Android runtime and the desktop JVM, so
 * the parser the unit tests exercise is the same class the app runs. The
 * alternative, `android.util.Xml` / KXmlParser, cannot be instantiated in a
 * plain JVM test without pulling in a second implementation — and a test that
 * runs a *different* parser from the app proves very little, which is a lesson
 * this project has already paid for once (see `claude/threadbare.md`, lesson 3).
 *
 * ## The shapes it was written against
 *
 * Every rule below comes from markup read off a live server on 17 Sep 2026, not
 * from a specification:
 *
 * | source | shape that forced a rule |
 * |---|---|
 * | BBC | `<media:thumbnail url=… width=…>`; a channel-level `<image>` that contains its own `<title>` and `<link>` |
 * | The Guardian | several `<media:content>` per item at different widths; body as escaped HTML in `<description>` |
 * | NPR | body in `<content:encoded>`, image only inside it as `<img>`, plus a 1×1 analytics beacon at the end |
 * | The Verge | Atom: `<entry>`, `<link rel="alternate" href=…>`, `<author><name>`, `<published>` |
 * | Nature | RSS 1.0: root `rdf:RDF`, `<item>` a *sibling* of `<channel>`, and a `<items><rdf:Seq>` list inside the channel that is not an item at all |
 * | CBS Sports | every element's text padded with newlines and indentation; `<guid isPermaLink="false">` holding a UUID; `<enclosure type="image/jpeg">` |
 *
 * ## Safety
 *
 * A feed is a document from a stranger. The reader resolves no external
 * entities and fetches nothing while parsing, so a feed cannot use this parser
 * to reach the filesystem or the network (XXE). Sizes are bounded: at most
 * [MAX_ITEMS] items and [MAX_FIELD] characters per field.
 */
object FeedParser {

    const val MAX_ITEMS = 200
    const val MAX_FIELD = 200_000

    private const val NS_ATOM = "http://www.w3.org/2005/Atom"
    private const val NS_MEDIA = "http://search.yahoo.com/mrss/"
    private const val NS_CONTENT = "http://purl.org/rss/1.0/modules/content/"
    private const val NS_DC = "http://purl.org/dc/elements/1.1/"

    /**
     * Decode bytes to text, then parse.
     *
     * The charset comes from the XML declaration when there is one, then the
     * HTTP `Content-Type`, then UTF-8. Publishers get this wrong often enough
     * that a decode failure falls back to ISO-8859-1, which cannot fail and
     * leaves mangled accents rather than no article at all.
     */
    fun parse(bytes: ByteArray, contentTypeHeader: String? = null, baseUrl: String? = null): ParsedFeed {
        val charset = charsetOf(bytes, contentTypeHeader)
        val text = try {
            String(bytes, charset)
        } catch (_: Throwable) {
            String(bytes, StandardCharsets.ISO_8859_1)
        }
        return parse(text, baseUrl)
    }

    fun parse(raw: String, baseUrl: String? = null): ParsedFeed {
        val repaired = Sanitize.xmlSafe(raw)
        if (repaired.isBlank()) return ParsedFeed.EMPTY
        val handler = Handler(baseUrl)
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isValidating = false
            setFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
            setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
            setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            val parser = factory.newSAXParser()
            val reader = parser.xmlReader
            reader.contentHandler = handler
            reader.errorHandler = handler
            // A DOCTYPE is allowed through (some feeds carry one) but is never
            // resolved: every external reference answers with an empty stream.
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.parse(InputSource(StringReader(repaired)))
        } catch (_: StopParsing) {
            // Item cap reached. Everything collected so far is good.
        } catch (_: SAXException) {
            // Malformed past repair. Whatever was collected before the break is
            // still returned: a feed that goes wrong in its 40th item should
            // still show the first 39.
        } catch (_: Throwable) {
            return handler.result()
        }
        return handler.result()
    }

    private fun setFeature(factory: SAXParserFactory, name: String, value: Boolean) {
        try {
            factory.setFeature(name, value)
        } catch (_: Throwable) {
            // Not every implementation knows every feature name; the ones that
            // matter are supported by both Android's and the JDK's parser.
        }
    }

    /** Thrown to stop the parse once [MAX_ITEMS] have been collected. */
    private class StopParsing : SAXException("item cap")

    internal fun charsetOf(bytes: ByteArray, contentTypeHeader: String?): Charset {
        val head = String(bytes, 0, minOf(bytes.size, 256), StandardCharsets.ISO_8859_1)
        declaredCharset(head)?.let { return it }
        contentTypeHeader?.let { header ->
            val idx = header.indexOf("charset=", ignoreCase = true)
            if (idx >= 0) {
                val name = header.substring(idx + 8).trim().trim('"', '\'', ';')
                    .substringBefore(';').trim()
                runCatching { return Charset.forName(name) }
            }
        }
        return StandardCharsets.UTF_8
    }

    private fun declaredCharset(head: String): Charset? {
        val decl = head.indexOf("<?xml")
        if (decl < 0) return null
        val end = head.indexOf("?>", decl)
        val slice = if (end > 0) head.substring(decl, end) else head
        val at = slice.indexOf("encoding", ignoreCase = true)
        if (at < 0) return null
        val eq = slice.indexOf('=', at)
        if (eq < 0) return null
        var i = eq + 1
        while (i < slice.length && slice[i].isWhitespace()) i++
        if (i >= slice.length) return null
        val quote = slice[i]
        if (quote != '"' && quote != '\'') return null
        val close = slice.indexOf(quote, i + 1)
        if (close < 0) return null
        val name = slice.substring(i + 1, close).trim()
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    /**
     * A candidate picture, kept with the width the feed claimed so the best one
     * can be chosen once the item ends rather than "first one wins".
     */
    private data class ImageCandidate(val url: String, val width: Int, val rank: Int)

    private class Handler(private val baseUrl: String?) : DefaultHandler() {

        private val text = StringBuilder()
        private var capture = false

        private var feedTitle: String? = null
        private var feedLink: String? = null
        private var feedDesc: String? = null
        private var feedIcon: String? = null

        private val items = ArrayList<ParsedItem>()

        private var inItem = false
        private var inChannelImage = false
        private var inAuthor = false

        private var guid: String? = null
        private var guidIsPermalink = true
        private var link: String? = null
        private var title: String? = null
        private var authors = ArrayList<String>()
        private var body: String? = null
        private var bodyRank = 0
        private var date: String? = null
        private var updated: String? = null
        private var images = ArrayList<ImageCandidate>()

        override fun startElement(uri: String?, localName: String?, qName: String?, attrs: Attributes?) {
            val name = local(localName, qName)
            val ns = uri.orEmpty()

            when {
                name == "item" || (name == "entry" && ns == NS_ATOM) -> {
                    startItem()
                    return
                }
                name == "image" && !inItem -> {
                    // A channel <image> has its own <title>, <link> and <url>.
                    // Letting those through would overwrite the feed's title
                    // with the logo's, which is how a reader ends up showing
                    // "BBC News" as a link to a 1990s GIF.
                    inChannelImage = true
                }
                name == "author" -> inAuthor = true
            }

            if (inItem) {
                when {
                    name == "guid" -> {
                        guidIsPermalink = attrs?.getValue("isPermaLink")?.equals("false", true) != true
                        startCapture()
                    }
                    name == "link" -> {
                        val href = attrs?.getValue("href")
                        if (href != null) {
                            // Atom. rel is optional and defaults to "alternate";
                            // "self", "replies", "enclosure" and the rest are
                            // not the article.
                            val rel = attrs.getValue("rel")
                            val type = attrs.getValue("type")
                            if ((rel == null || rel == "alternate") &&
                                (type == null || type.startsWith("text/html"))
                            ) {
                                if (link == null) link = href.trim()
                            }
                            if (rel == "enclosure" && isImageType(type)) {
                                addImage(href, attrs.getValue("length")?.toIntOrNull() ?: 0, RANK_ENCLOSURE)
                            }
                        } else {
                            startCapture()
                        }
                    }
                    name == "enclosure" -> {
                        val url = attrs?.getValue("url")
                        if (url != null && isImageType(attrs.getValue("type"))) {
                            addImage(url, 0, RANK_ENCLOSURE)
                        }
                    }
                    name == "thumbnail" && ns == NS_MEDIA -> {
                        val url = attrs?.getValue("url")
                        if (url != null) addImage(url, attrs.getValue("width")?.toIntOrNull() ?: 0, RANK_THUMBNAIL)
                    }
                    name == "content" && ns == NS_MEDIA -> {
                        val url = attrs?.getValue("url")
                        val medium = attrs?.getValue("medium")
                        val type = attrs?.getValue("type")
                        if (url != null && (medium == "image" || isImageType(type) || (medium == null && type == null))) {
                            addImage(url, attrs.getValue("width")?.toIntOrNull() ?: 0, RANK_MEDIA)
                        }
                    }
                    name == "content" && ns == NS_ATOM -> startCapture()
                    name == "encoded" && ns == NS_CONTENT -> startCapture()
                    name == "title" || name == "description" || name == "summary" ||
                        name == "pubDate" || name == "published" || name == "updated" ||
                        name == "date" || name == "creator" || name == "id" ||
                        (name == "name" && inAuthor) || (name == "author" && ns != NS_ATOM) -> startCapture()
                }
                return
            }

            if (inChannelImage) {
                if (name == "url") startCapture()
                return
            }

            // Channel / feed level.
            when {
                name == "title" -> startCapture()
                name == "subtitle" || name == "description" -> startCapture()
                name == "link" -> {
                    val href = attrs?.getValue("href")
                    if (href != null) {
                        val rel = attrs.getValue("rel")
                        if (rel == null || rel == "alternate") {
                            if (feedLink == null) feedLink = href.trim()
                        }
                    } else {
                        startCapture()
                    }
                }
                name == "icon" || name == "logo" -> startCapture()
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = local(localName, qName)
            val ns = uri.orEmpty()
            val value = if (capture) text.toString() else ""
            capture = false
            text.setLength(0)

            if (name == "author") inAuthor = false

            if (inItem) {
                when {
                    name == "item" || (name == "entry" && ns == NS_ATOM) -> finishItem()
                    name == "title" -> if (title == null) title = clean(value)
                    name == "link" -> if (link == null && value.isNotBlank()) link = value.trim()
                    name == "guid" || name == "id" -> if (guid == null) guid = value.trim()
                    name == "pubDate" || name == "published" || (name == "date" && ns == NS_DC) ->
                        if (date == null) date = value.trim()
                    name == "updated" -> if (updated == null) updated = value.trim()
                    // Nature lists one <dc:creator> per author, so this
                    // collects rather than taking the first and calling a
                    // two-author paper single-authored.
                    name == "creator" && ns == NS_DC -> addAuthor(value)
                    name == "name" -> if (inAuthor) addAuthor(value)
                    name == "author" && ns != NS_ATOM -> addAuthor(value)
                    name == "encoded" && ns == NS_CONTENT -> takeBody(value, RANK_ENCODED)
                    name == "content" && ns == NS_ATOM -> takeBody(value, RANK_ENCODED)
                    name == "description" -> takeBody(value, RANK_DESCRIPTION)
                    name == "summary" -> takeBody(value, RANK_SUMMARY)
                }
                return
            }

            if (inChannelImage) {
                if (name == "url" && feedIcon == null && value.isNotBlank()) feedIcon = value.trim()
                if (name == "image") inChannelImage = false
                return
            }

            when {
                name == "title" -> if (feedTitle == null) feedTitle = clean(value)
                (name == "description" || name == "subtitle") ->
                    if (feedDesc == null) feedDesc = Sanitize.htmlToText(value).ifBlank { null }
                name == "link" -> if (feedLink == null && value.isNotBlank()) feedLink = value.trim()
                (name == "icon" || name == "logo") -> if (feedIcon == null && value.isNotBlank()) feedIcon = value.trim()
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (!capture || ch == null) return
            if (text.length + length > MAX_FIELD) {
                val room = MAX_FIELD - text.length
                if (room > 0) text.appendRange(ch, start, start + room)
                return
            }
            text.appendRange(ch, start, start + length)
        }

        override fun warning(e: org.xml.sax.SAXParseException?) {}
        override fun error(e: org.xml.sax.SAXParseException?) {}

        private fun startCapture() {
            text.setLength(0)
            capture = true
        }

        private fun startItem() {
            inItem = true
            inChannelImage = false
            guid = null
            guidIsPermalink = true
            link = null
            title = null
            authors = ArrayList()
            body = null
            bodyRank = 0
            date = null
            updated = null
            images = ArrayList()
        }

        /** At most three names, joined; more than that is not a byline. */
        private fun addAuthor(value: String) {
            if (authors.size >= 3) return
            val name = clean(value) ?: return
            if (name.length > 120) return
            if (authors.none { it.equals(name, ignoreCase = true) }) authors.add(name)
        }

        private fun byline(): String? =
            if (authors.isEmpty()) null else authors.joinToString(", ")

        private fun takeBody(value: String, rank: Int) {
            if (value.isBlank()) return
            if (rank > bodyRank) {
                body = value
                bodyRank = rank
            }
        }

        private fun addImage(url: String, width: Int, rank: Int) {
            val abs = absolute(url.trim()) ?: return
            if (Sanitize.isTrackingPixel(abs)) return
            images.add(ImageCandidate(abs, width, rank))
        }

        private fun finishItem() {
            inItem = false
            val t = title?.takeIf { it.isNotBlank() }
            // The link is what makes an item usable. An RSS <guid isPermaLink>
            // that is a URL stands in when <link> is missing; a UUID guid never
            // does, which is why isPermaLink is read rather than assumed.
            val href = absolute(link)
                ?: guid?.takeIf { guidIsPermalink && Sanitize.looksLikeHttpUrl(it) }?.let { absolute(it) }
            if (t == null || href == null) return

            val bodyHtml = body
            val fromBody = Sanitize.firstImage(bodyHtml)?.let { absolute(it) }
            val picked = images
                .sortedWith(compareByDescending<ImageCandidate> { it.rank }.thenByDescending { it.width })
                .firstOrNull()?.url ?: fromBody

            items.add(
                ParsedItem(
                    guid = guid?.takeIf { it.isNotBlank() } ?: href,
                    link = href,
                    title = t,
                    author = byline(),
                    summaryHtml = bodyHtml,
                    publishedAt = Dates.parse(date) ?: Dates.parse(updated),
                    imageUrl = picked,
                )
            )
            if (items.size >= MAX_ITEMS) throw StopParsing()
        }

        /** Resolve a relative URL against the feed's own address, then force https. */
        private fun absolute(url: String?): String? {
            val u = url?.trim().orEmpty()
            if (u.isEmpty()) return null
            Sanitize.https(u)?.let { return it }
            val base = baseUrl ?: return null
            return runCatching { Sanitize.https(URI(base).resolve(u).toString()) }.getOrNull()
        }

        /**
         * A title as it should be displayed.
         *
         * [Sanitize.htmlToText] rather than a plain entity decode because
         * `<title type="html">` is legal in Atom and publishers do put markup
         * in headlines. On a title that is already plain text it changes
         * nothing.
         */
        private fun clean(value: String): String? =
            Sanitize.htmlToText(value).trim().ifBlank { null }

        private fun isImageType(type: String?): Boolean =
            type != null && type.startsWith("image/", ignoreCase = true)

        fun result(): ParsedFeed = ParsedFeed(
            title = feedTitle,
            siteLink = feedLink,
            description = feedDesc,
            iconUrl = feedIcon,
            items = items,
        )

        private companion object {
            // Higher wins. A publisher-declared thumbnail beats a media:content
            // beats an enclosure beats an <img> scraped out of the body.
            const val RANK_THUMBNAIL = 3
            const val RANK_MEDIA = 2
            const val RANK_ENCLOSURE = 1

            // Body: the fullest text wins. content:encoded and Atom <content>
            // carry the article; <summary> and <description> carry a teaser.
            const val RANK_ENCODED = 3
            const val RANK_SUMMARY = 2
            const val RANK_DESCRIPTION = 1
        }
    }

    /**
     * localName, falling back to the qualified name with any prefix removed.
     *
     * Namespace-aware parsing gives a localName for every element in a
     * well-formed document, but a feed that uses a prefix it never declared
     * (which happens) arrives with an empty localName and a qName like
     * `dc:creator`.
     */
    private fun local(localName: String?, qName: String?): String {
        val l = localName.orEmpty()
        if (l.isNotEmpty()) return l
        val q = qName.orEmpty()
        val colon = q.indexOf(':')
        return if (colon >= 0) q.substring(colon + 1) else q
    }
}
