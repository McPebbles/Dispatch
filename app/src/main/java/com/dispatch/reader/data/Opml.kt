package com.dispatch.reader.data

import com.dispatch.reader.feed.Sanitize
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/**
 * OPML, which this app uses for three things at once:
 *
 *  1. the **bundled feed set** (`assets/default_feeds.opml`) seeded on first run;
 *  2. **import**, so a reader arriving from NewsBlur, Feedly, Inoreader or
 *     any other reader brings their subscriptions with them;
 *  3. **export**, so they can leave again. A reader that can be left is a
 *     reader worth trusting; this one writes the same file it reads.
 *
 * Groups (an `<outline>` with children rather than an `xmlUrl`) map to streams.
 * That makes the bundled categories and an imported folder structure the same
 * thing, with one code path instead of two.
 *
 * Free of `android.*` — SAX is in both runtimes — so import and export are
 * exercised on the JVM against the file the app actually ships.
 */
object Opml {

    data class Entry(val title: String, val url: String, val siteUrl: String? = null)

    /** A folder of feeds. [name] is null for feeds that sat at the top level. */
    data class Group(val name: String?, val feeds: List<Entry>)

    const val MAX_FEEDS = 2000

    fun parse(xml: String): List<Group> {
        val handler = Handler()
        runCatching {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isValidating = false
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            val reader = factory.newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.parse(InputSource(StringReader(Sanitize.xmlSafe(xml))))
        }
        return handler.groups()
    }

    /**
     * Write the user's feeds out, grouped by stream.
     *
     * A feed in two streams appears twice, which is what every other reader
     * does and what round-trips correctly: on import each copy joins its own
     * group, and [Repo.addFeed] returns the existing row for the second one, so
     * one feed ends up in both streams rather than two feeds existing.
     */
    fun export(
        groups: List<Group>,
        title: String = "Dispatch subscriptions",
        dateCreated: String? = null,
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<opml version=\"2.0\">\n")
        append("  <head>\n    <title>").append(esc(title)).append("</title>\n")
        if (dateCreated != null) append("    <dateCreated>").append(esc(dateCreated)).append("</dateCreated>\n")
        append("  </head>\n  <body>\n")
        for (group in groups) {
            val indent: String
            if (group.name != null) {
                append("    <outline text=\"").append(esc(group.name)).append("\" title=\"")
                    .append(esc(group.name)).append("\">\n")
                indent = "      "
            } else {
                indent = "    "
            }
            for (feed in group.feeds) {
                append(indent).append("<outline type=\"rss\" text=\"").append(esc(feed.title))
                    .append("\" title=\"").append(esc(feed.title))
                    .append("\" xmlUrl=\"").append(esc(feed.url)).append('"')
                if (!feed.siteUrl.isNullOrBlank()) {
                    append(" htmlUrl=\"").append(esc(feed.siteUrl)).append('"')
                }
                append(" />\n")
            }
            if (group.name != null) append("    </outline>\n")
        }
        append("  </body>\n</opml>\n")
    }

    private fun esc(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (c.code >= 0x20 || c == '\n' || c == '\t') append(c)
        }
    }

    private class Handler : DefaultHandler() {

        private val named = LinkedHashMap<String, MutableList<Entry>>()
        private val loose = ArrayList<Entry>()

        /**
         * One frame per open `<outline>`: the folder's name, or null when that
         * outline was a feed.
         *
         * Pushing on every start and popping on every end is exactly balanced,
         * including for the self-closing form — SAX reports `<outline />` as a
         * start followed immediately by an end. An earlier version pushed only
         * for folders and tried to work out when to pop, which is the kind of
         * bookkeeping that silently mis-files every feed after the first
         * container-form entry.
         */
        private val stack = ArrayList<String?>()
        private var count = 0

        override fun startElement(uri: String?, localName: String?, qName: String?, attrs: Attributes?) {
            val name = (if (!localName.isNullOrEmpty()) localName else qName.orEmpty()).lowercase()
            if (name != "outline") return
            val a = attrs ?: return

            val xmlUrl = a.value("xmlUrl")
            val label = a.value("title")?.takeIf { it.isNotBlank() }
                ?: a.value("text")?.takeIf { it.isNotBlank() }

            if (xmlUrl.isNullOrBlank()) {
                stack.add(label.orEmpty())
                return
            }
            stack.add(null)

            if (count >= MAX_FEEDS) return
            val url = Sanitize.https(Sanitize.decodeEntities(xmlUrl.trim())) ?: return
            val entry = Entry(
                title = label?.let { Sanitize.htmlToText(it) }?.takeIf { it.isNotBlank() }
                    ?: Repo.hostOf(url),
                url = url,
                siteUrl = a.value("htmlUrl")?.trim()?.takeIf { it.isNotBlank() },
            )
            count++
            val folder = enclosingFolder()
            if (folder == null) loose.add(entry) else named.getOrPut(folder) { ArrayList() }.add(entry)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = (if (!localName.isNullOrEmpty()) localName else qName.orEmpty()).lowercase()
            if (name != "outline") return
            if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
        }

        /** The innermost named folder, ignoring the feed's own frame. */
        private fun enclosingFolder(): String? {
            for (i in stack.indices.reversed()) {
                val frame = stack[i] ?: continue
                if (frame.isNotBlank()) return frame
            }
            return null
        }

        /** Attribute lookup that does not care about case, as OPML in the wild does not. */
        private fun Attributes.value(name: String): String? {
            for (i in 0 until length) {
                if (getQName(i).equals(name, true) || getLocalName(i).equals(name, true)) return getValue(i)
            }
            return null
        }

        fun groups(): List<Group> {
            val out = ArrayList<Group>(named.size + 1)
            for ((name, feeds) in named) out.add(Group(name, feeds))
            if (loose.isNotEmpty()) out.add(Group(null, loose))
            return out
        }
    }
}
