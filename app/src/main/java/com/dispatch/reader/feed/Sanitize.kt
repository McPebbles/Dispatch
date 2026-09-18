package com.dispatch.reader.feed

/**
 * The string handling every feed reader ends up needing, and the reasons each
 * piece exists. All of it is free of `android.*` and tested on the JVM.
 *
 * ## Why [xmlSafe] exists
 *
 * A feed is XML, and XML defines exactly five named entities. HTML defines
 * hundreds, and publishers' templates write them into feeds constantly —
 * `&nbsp;`, `&mdash;`, `&rsquo;`. A conforming XML parser is *required* to fail
 * on an undeclared entity, so a strict parse of a real-world feed throws on
 * markup that every browser renders without complaint. The choice is between
 * a lenient parser (Android's KXmlParser has a relaxed mode; the JVM's SAX does
 * not, which would mean the tests exercise a different parser from the app) and
 * repairing the document first. This app repairs the document, so that the same
 * bytes go through the same parser here and on device.
 *
 * ## Why the repair must respect CDATA
 *
 * Inside `<![CDATA[ … ]]>` the text is literal: `&nbsp;` there *is* the six
 * characters, and rewriting it to `&#160;` would corrupt the content rather
 * than fix it. NPR, BBC and The Verge all wrap their bodies in CDATA, so this
 * is not a hypothetical.
 */
object Sanitize {

    /** The five XML predefines. Everything else has to be rewritten or escaped. */
    private val XML_NAMED = setOf("amp", "lt", "gt", "quot", "apos")

    /**
     * HTML named entities worth decoding, mapped to code points.
     *
     * Not the full HTML5 table — that is 2,231 entries and most of them never
     * appear in a news feed. This is the set observed in the bundled feeds plus
     * the usual typography.
     */
    private val NAMED: Map<String, Int> = mapOf(
        "nbsp" to 160, "iexcl" to 161, "cent" to 162, "pound" to 163, "curren" to 164,
        "yen" to 165, "brvbar" to 166, "sect" to 167, "uml" to 168, "copy" to 169,
        "ordf" to 170, "laquo" to 171, "not" to 172, "shy" to 173, "reg" to 174,
        "macr" to 175, "deg" to 176, "plusmn" to 177, "sup2" to 178, "sup3" to 179,
        "acute" to 180, "micro" to 181, "para" to 182, "middot" to 183, "cedil" to 184,
        "sup1" to 185, "ordm" to 186, "raquo" to 187, "frac14" to 188, "frac12" to 189,
        "frac34" to 190, "iquest" to 191,
        "Agrave" to 192, "Aacute" to 193, "Acirc" to 194, "Atilde" to 195, "Auml" to 196,
        "Aring" to 197, "AElig" to 198, "Ccedil" to 199, "Egrave" to 200, "Eacute" to 201,
        "Ecirc" to 202, "Euml" to 203, "Igrave" to 204, "Iacute" to 205, "Icirc" to 206,
        "Iuml" to 207, "ETH" to 208, "Ntilde" to 209, "Ograve" to 210, "Oacute" to 211,
        "Ocirc" to 212, "Otilde" to 213, "Ouml" to 214, "times" to 215, "Oslash" to 216,
        "Ugrave" to 217, "Uacute" to 218, "Ucirc" to 219, "Uuml" to 220, "Yacute" to 221,
        "THORN" to 222, "szlig" to 223,
        "agrave" to 224, "aacute" to 225, "acirc" to 226, "atilde" to 227, "auml" to 228,
        "aring" to 229, "aelig" to 230, "ccedil" to 231, "egrave" to 232, "eacute" to 233,
        "ecirc" to 234, "euml" to 235, "igrave" to 236, "iacute" to 237, "icirc" to 238,
        "iuml" to 239, "eth" to 240, "ntilde" to 241, "ograve" to 242, "oacute" to 243,
        "ocirc" to 244, "otilde" to 245, "ouml" to 246, "divide" to 247, "oslash" to 248,
        "ugrave" to 249, "uacute" to 250, "ucirc" to 251, "uuml" to 252, "yacute" to 253,
        "thorn" to 254, "yuml" to 255,
        "OElig" to 338, "oelig" to 339, "Scaron" to 352, "scaron" to 353, "Yuml" to 376,
        "fnof" to 402, "circ" to 710, "tilde" to 732,
        "ensp" to 8194, "emsp" to 8195, "thinsp" to 8201, "zwnj" to 8204, "zwj" to 8205,
        "lrm" to 8206, "rlm" to 8207,
        "ndash" to 8211, "mdash" to 8212, "horbar" to 8213,
        "lsquo" to 8216, "rsquo" to 8217, "sbquo" to 8218,
        "ldquo" to 8220, "rdquo" to 8221, "bdquo" to 8222,
        "dagger" to 8224, "Dagger" to 8225, "bull" to 8226, "hellip" to 8230,
        "permil" to 8240, "prime" to 8242, "Prime" to 8243,
        "lsaquo" to 8249, "rsaquo" to 8250, "oline" to 8254, "frasl" to 8260,
        "euro" to 8364, "trade" to 8482,
        "larr" to 8592, "uarr" to 8593, "rarr" to 8594, "darr" to 8595, "harr" to 8596,
        "minus" to 8722, "lowast" to 8727, "ne" to 8800, "le" to 8804, "ge" to 8805,
        "loz" to 9674, "spades" to 9824, "clubs" to 9827, "hearts" to 9829, "diams" to 9830
    )

    /**
     * Make a feed document parseable by a strict XML parser without changing
     * what it says.
     *
     * Four repairs, in one pass:
     *  - anything before the first `<` is dropped (a byte-order mark, or the
     *    blank line some CMSs emit above the declaration, makes a parser refuse
     *    the document outright);
     *  - control characters that XML 1.0 forbids are removed;
     *  - a known HTML entity becomes the equivalent numeric reference;
     *  - anything else that looks like an entity, and any bare `&`, is escaped
     *    to `&amp;` so it survives as literal text.
     *
     * CDATA sections are copied through untouched — see the class comment.
     */
    fun xmlSafe(raw: String): String {
        val start = raw.indexOf('<')
        val src = if (start > 0) raw.substring(start) else raw
        val out = StringBuilder(src.length + 64)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '<' && src.startsWith("<![CDATA[", i)) {
                val end = src.indexOf("]]>", i)
                if (end < 0) {
                    out.append(src, i, src.length)
                    break
                }
                out.append(src, i, end + 3)
                i = end + 3
                continue
            }
            if (c == '&') {
                val semi = src.indexOf(';', i + 1)
                val name = if (semi in (i + 1)..(i + 32)) src.substring(i + 1, semi) else null
                if (name != null && name.isNotEmpty()) {
                    if (name in XML_NAMED || isNumericRef(name)) {
                        out.append('&').append(name).append(';')
                        i = semi + 1
                        continue
                    }
                    val cp = NAMED[name]
                    if (cp != null) {
                        out.append("&#").append(cp).append(';')
                        i = semi + 1
                        continue
                    }
                }
                out.append("&amp;")
                i++
                continue
            }
            if (isForbidden(c)) {
                i++
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun isNumericRef(name: String): Boolean {
        if (name.length < 2 || name[0] != '#') return false
        val body = if (name[1] == 'x' || name[1] == 'X') name.substring(2) else name.substring(1)
        if (body.isEmpty()) return false
        val hex = name[1] == 'x' || name[1] == 'X'
        return body.all { if (hex) it.isDigit() || it in 'a'..'f' || it in 'A'..'F' else it.isDigit() }
    }

    /** XML 1.0 permits tab, newline and carriage return, and nothing else below 0x20. */
    private fun isForbidden(c: Char): Boolean =
        (c.code < 0x20 && c != '\t' && c != '\n' && c != '\r') || c.code == 0xFFFE || c.code == 0xFFFF

    /** Decode numeric and known-named entities in text that is already plain. */
    fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semi = s.indexOf(';', i + 1)
            val name = if (semi in (i + 1)..(i + 32)) s.substring(i + 1, semi) else null
            if (name == null || name.isEmpty()) {
                out.append(c)
                i++
                continue
            }
            val cp = when {
                isNumericRef(name) -> {
                    val hex = name[1] == 'x' || name[1] == 'X'
                    val body = if (hex) name.substring(2) else name.substring(1)
                    body.toIntOrNull(if (hex) 16 else 10)
                }
                name == "amp" -> 38
                name == "lt" -> 60
                name == "gt" -> 62
                name == "quot" -> 34
                name == "apos" -> 39
                else -> NAMED[name]
            }
            if (cp == null || cp <= 0 || cp > 0x10FFFF) {
                out.append(c)
                i++
            } else {
                out.appendCodePoint(cp)
                i = semi + 1
            }
        }
        return out.toString()
    }

    /**
     * An HTML fragment as readable plain text.
     *
     * Feed summaries are HTML about half the time — The Guardian escapes a
     * string of `<p>` tags into `<description>`, NPR puts the same thing in
     * `content:encoded` inside CDATA. Block-level tags become a space so that
     * "…paragraph one.</p><p>Paragraph two…" does not read as one run-on word.
     */
    fun htmlToText(html: String?): String {
        val s = html.orEmpty()
        if (s.isEmpty()) return ""
        val out = StringBuilder(s.length)
        var i = 0
        var inTag = false
        var inScript = false
        while (i < s.length) {
            val c = s[i]
            if (!inTag && c == '<') {
                if (s.startsWith("<!--", i)) {
                    val end = s.indexOf("-->", i)
                    i = if (end < 0) s.length else end + 3
                    continue
                }
                val lower = s.substring(i, minOf(s.length, i + 8)).lowercase()
                if (lower.startsWith("<script") || lower.startsWith("<style")) inScript = true
                if (lower.startsWith("</script") || lower.startsWith("</style")) inScript = false
                inTag = true
                out.append(' ')
                i++
                continue
            }
            if (inTag) {
                if (c == '>') inTag = false
                i++
                continue
            }
            if (!inScript) out.append(c)
            i++
        }
        return collapse(decodeEntities(out.toString()))
    }

    /** Collapse runs of whitespace, including the non-breaking space. */
    fun collapse(s: String): String {
        val out = StringBuilder(s.length)
        var space = false
        for (c in s) {
            val ws = c.isWhitespace() || c.code == 160
            if (ws) {
                space = true
            } else {
                if (space && out.isNotEmpty()) out.append(' ')
                space = false
                out.append(c)
            }
        }
        return out.toString()
    }

    /** Cut to [max] characters on a word boundary, adding an ellipsis. */
    fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = s.lastIndexOf(' ', max - 1)
        val head = if (cut > max / 2) s.substring(0, cut) else s.substring(0, max - 1)
        return head.trimEnd(' ', ',', ';', ':', '-', '—') + "…"
    }

    /**
     * The first image in an HTML fragment that is plausibly the article's
     * picture.
     *
     * The reason this is not simply "the first `<img>`": NPR's `content:encoded`
     * ends with
     * `<img src='https://media.npr.org/include/images/tracking/npr-rss-pixel.png?story=…'/>`,
     * a one-pixel analytics beacon. Choosing it would put a tracking request in
     * the widget for every NPR story, on a home screen, forever. So a candidate
     * has to survive [isTrackingPixel] and any width/height it declares.
     */
    fun firstImage(html: String?): String? {
        val s = html ?: return null
        var i = 0
        while (true) {
            val open = indexOfIgnoreCase(s, "<img", i)
            if (open < 0) return null
            val close = s.indexOf('>', open)
            if (close < 0) return null
            val tag = s.substring(open, close)
            val src = attr(tag, "src") ?: attr(tag, "data-src")
            i = close + 1
            if (src.isNullOrBlank()) continue
            val url = decodeEntities(src.trim())
            if (isTrackingPixel(url)) continue
            val w = attr(tag, "width")?.toIntOrNull()
            val h = attr(tag, "height")?.toIntOrNull()
            if (w != null && w in 1..32) continue
            if (h != null && h in 1..32) continue
            if (!looksLikeHttpUrl(url)) continue
            return url
        }
    }

    private fun indexOfIgnoreCase(s: String, needle: String, from: Int): Int {
        var i = from
        while (i <= s.length - needle.length) {
            if (s.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) return i
            i++
        }
        return -1
    }

    /** Read `name="value"`, `name='value'` or `name=value` out of a tag. */
    fun attr(tag: String, name: String): String? {
        var i = 0
        while (true) {
            val at = indexOfIgnoreCase(tag, name, i)
            if (at < 0) return null
            i = at + name.length
            // Must be preceded by whitespace and followed by '=' so that
            // "data-src" is not matched when looking for "src".
            if (at > 0 && !tag[at - 1].isWhitespace()) continue
            var j = i
            while (j < tag.length && tag[j].isWhitespace()) j++
            if (j >= tag.length || tag[j] != '=') continue
            j++
            while (j < tag.length && tag[j].isWhitespace()) j++
            if (j >= tag.length) return null
            val quote = tag[j]
            if (quote == '"' || quote == '\'') {
                val end = tag.indexOf(quote, j + 1)
                if (end < 0) return null
                return tag.substring(j + 1, end)
            }
            var end = j
            while (end < tag.length && !tag[end].isWhitespace() && tag[end] != '>') end++
            return tag.substring(j, end)
        }
    }

    /**
     * URLs that are analytics beacons rather than pictures.
     *
     * Deliberately conservative: a false positive costs one missing thumbnail,
     * a false negative puts a tracker on the home screen.
     */
    fun isTrackingPixel(url: String): Boolean {
        val u = url.lowercase()
        val markers = listOf(
            "rss-pixel", "/tracking/", "/track/", "/track?", "tracking-pixel",
            "beacon", "/pixel", "pixel.", "pixel?", "1x1", "spacer.gif",
            "scorecardresearch", "doubleclick", "googletagmanager", "google-analytics",
            "/stats/", "stats.", "/imp?", "utm_source=rss&utm_medium=pixel"
        )
        return markers.any { it in u }
    }

    fun looksLikeHttpUrl(url: String): Boolean {
        val u = url.trim()
        return (u.startsWith("http://", true) || u.startsWith("https://", true)) && u.length > 10
    }

    /**
     * Force a URL to https.
     *
     * The app's network security config forbids cleartext, so an `http://`
     * image or link would simply fail. Almost every publisher redirects http to
     * https anyway, so upgrading is both safer and more likely to work than
     * refusing. Anything that is not http stays as it is and is rejected later.
     */
    fun https(url: String?): String? {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return null
        if (u.startsWith("https://", true)) return u
        if (u.startsWith("http://", true)) return "https://" + u.substring(7)
        if (u.startsWith("//")) return "https:$u"
        return null
    }
}
