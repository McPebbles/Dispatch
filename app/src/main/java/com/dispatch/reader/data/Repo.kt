package com.dispatch.reader.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.dispatch.reader.feed.ParsedFeed
import com.dispatch.reader.feed.ParsedItem

/**
 * Every question the app asks the database, in one place.
 *
 * A single instance is held by [com.dispatch.reader.App]; SQLiteOpenHelper's
 * connection pool handles the threading, and nothing here touches the UI. All
 * of it is called from a background executor.
 */
class Repo(context: Context) {

    private val helper = Db(context.applicationContext)
    private val db: SQLiteDatabase get() = helper.writableDatabase

    // ---------------------------------------------------------------- feeds

    /**
     * The feed library, optionally searched and filtered.
     *
     * This is the query behind both "Manage feeds" and the stream builder's
     * feed picker. A hundred feeds is far too many to scroll, so finding one
     * has to be a first-class operation rather than something the reader does
     * with their thumb.
     *
     * @param query matches the title, the address, and the category name.
     * @param category exact category, or null for all. [UNCATEGORISED] selects
     *   feeds with no category at all, which is the set most in need of
     *   attention after an OPML import.
     */
    fun feeds(query: String = "", category: String? = null): List<Feed> {
        val where = StringBuilder()
        val args = ArrayList<String>()
        val q = query.trim()
        if (q.isNotEmpty()) {
            where.append(" AND (f.title LIKE ? OR f.url LIKE ? OR f.category LIKE ?) ")
            val like = "%" + q.replace("%", "").replace("_", "") + "%"
            args.add(like); args.add(like); args.add(like)
        }
        when (category) {
            null -> Unit
            UNCATEGORISED -> where.append(" AND (f.category IS NULL OR f.category = '') ")
            else -> {
                where.append(" AND f.category = ? ")
                args.add(category)
            }
        }
        return db.rawQuery(
            "$FEED_SELECT WHERE 1=1 $where ORDER BY LOWER(f.title)",
            args.toTypedArray(),
        ).use { c -> buildList { while (c.moveToNext()) add(feedFrom(c)) } }
    }

    fun feed(id: Long): Feed? =
        db.rawQuery("$FEED_SELECT WHERE f.id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) feedFrom(c) else null
        }

    fun feedByUrl(url: String): Feed? =
        db.rawQuery("$FEED_SELECT WHERE f.url = ?", arrayOf(url)).use { c ->
            if (c.moveToFirst()) feedFrom(c) else null
        }

    /**
     * The feeds a stream shows: the ones picked explicitly, plus every feed
     * carrying one of the stream's categories.
     */
    fun feedsIn(streamId: Long): List<Feed> = when (streamId) {
        Stream.ALL_ID, Stream.SAVED_ID -> feeds()
        else -> db.rawQuery(
            "$FEED_SELECT WHERE ${Db.streamMember("f.id")} ORDER BY LOWER(f.title)",
            arrayOf(streamId.toString(), streamId.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(feedFrom(c)) } }
    }

    fun feedCountIn(streamId: Long): Int = when (streamId) {
        Stream.ALL_ID, Stream.SAVED_ID -> countOf("SELECT COUNT(*) FROM feeds")
        else -> db.rawQuery(
            "SELECT COUNT(*) FROM feeds f WHERE ${Db.streamMember("f.id")}",
            arrayOf(streamId.toString(), streamId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /**
     * Add a feed, or return the existing one.
     *
     * Adding a feed twice is not an error the reader should be made to care
     * about, so a duplicate URL returns the row that is already there. The URL
     * is the identity — the title is only a label and the reader may change it.
     */
    fun addFeed(
        url: String,
        title: String,
        siteLink: String? = null,
        iconUrl: String? = null,
        category: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Long {
        feedByUrl(url)?.let { existing ->
            // A feed added again with a category, when it had none, takes it:
            // that is the reader filing something they already had.
            if (!category.isNullOrBlank() && existing.category.isNullOrBlank()) {
                setFeedCategory(existing.id, category)
            }
            return existing.id
        }
        if (!category.isNullOrBlank()) createCategory(category, now = now)
        val values = ContentValues().apply {
            put("url", url)
            put("title", title.ifBlank { hostOf(url) })
            put("site_link", siteLink)
            put("icon_url", iconUrl)
            put("category", category?.takeIf { it.isNotBlank() })
            put("added_at", now)
        }
        return db.insertWithOnConflict("feeds", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            .takeIf { it > 0 } ?: (feedByUrl(url)?.id ?: -1L)
    }

    fun renameFeed(id: Long, title: String) {
        db.update("feeds", ContentValues().apply { put("title", title) }, "id = ?", arrayOf(id.toString()))
    }

    /** File a feed under a category, or under none when [category] is null. */
    fun setFeedCategory(id: Long, category: String?) {
        val clean = category?.trim()?.takeIf { it.isNotEmpty() }
        if (clean != null) createCategory(clean)
        db.update(
            "feeds",
            ContentValues().apply { if (clean == null) putNull("category") else put("category", clean) },
            "id = ?", arrayOf(id.toString()),
        )
    }

    fun deleteFeed(id: Long) {
        db.delete("feeds", "id = ?", arrayOf(id.toString()))
    }

    /** What the sync loop writes back after a fetch, successful or not. */
    fun recordFetch(
        feedId: Long,
        error: String?,
        count: Int,
        etag: String?,
        lastModified: String?,
        parsed: ParsedFeed?,
        now: Long = System.currentTimeMillis(),
    ) {
        val values = ContentValues().apply {
            put("last_fetch_at", now)
            put("last_error", error)
            put("last_count", count)
            put("etag", etag)
            put("last_modified", lastModified)
            parsed?.siteLink?.let { put("site_link", it) }
            parsed?.iconUrl?.let { put("icon_url", it) }
        }
        db.update("feeds", values, "id = ?", arrayOf(feedId.toString()))
    }

    // ----------------------------------------------------------- categories

    /**
     * Every category, with how many feeds carry it.
     *
     * Categories live in their own table rather than being the distinct values
     * of `feeds.category`, so that a category made *before* the feed it was
     * made for still exists when the reader comes back to it.
     */
    fun categories(): List<Category> = db.rawQuery(
        "SELECT c.name, c.builtin, " +
            "(SELECT COUNT(*) FROM feeds f WHERE f.category = c.name) " +
            "FROM categories c ORDER BY LOWER(c.name)",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(Category(c.getString(0), c.getInt(1) != 0, c.getInt(2)))
        }
    }

    fun categoryNames(): List<String> = categories().map { it.name }

    fun createCategory(name: String, builtin: Boolean = false, now: Long = System.currentTimeMillis()): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val rowId = db.insertWithOnConflict(
            "categories", null,
            ContentValues().apply {
                put("name", clean)
                put("created_at", now)
                put("builtin", if (builtin) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        return rowId > 0
    }

    /**
     * Rename a category everywhere it is referenced.
     *
     * Three tables, one transaction. `feeds.category` is a plain label rather
     * than a foreign key — `ALTER TABLE ... ADD COLUMN` cannot add one, and the
     * v1 → v2 migration had to be additive — so the cascade is written out here
     * instead of being declared once in the schema.
     */
    fun renameCategory(from: String, to: String): Boolean {
        val target = to.trim()
        if (target.isEmpty() || target == from) return false
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "categories", null,
                ContentValues().apply {
                    put("name", target)
                    put("created_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            db.execSQL("UPDATE feeds SET category = ? WHERE category = ?", arrayOf<Any>(target, from))
            db.execSQL(
                "UPDATE OR IGNORE stream_categories SET category = ? WHERE category = ?",
                arrayOf<Any>(target, from),
            )
            db.delete("stream_categories", "category = ?", arrayOf(from))
            db.delete("categories", "name = ?", arrayOf(from))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    /**
     * Delete a category. **Feeds survive** — they simply become uncategorised,
     * and any stream that included the category loses that rule.
     */
    fun deleteCategory(name: String) {
        db.beginTransaction()
        try {
            db.execSQL("UPDATE feeds SET category = NULL WHERE category = ?", arrayOf<Any>(name))
            db.delete("stream_categories", "category = ?", arrayOf(name))
            db.delete("categories", "name = ?", arrayOf(name))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // -------------------------------------------------------------- streams

    fun streams(): List<Stream> = db.rawQuery(
        "SELECT s.id, s.name, s.created_at, s.refresh_minutes, " +
            "(SELECT COUNT(*) FROM stream_feeds sf WHERE sf.stream_id = s.id), " +
            "(SELECT COUNT(*) FROM stream_categories sc WHERE sc.stream_id = s.id) " +
            "FROM streams s ORDER BY s.created_at, s.id",
        null,
    ).use { c -> buildList { while (c.moveToNext()) add(streamFrom(c)) } }

    fun stream(id: Long): Stream? {
        if (id == Stream.ALL_ID || id == Stream.SAVED_ID) return null
        return db.rawQuery(
            "SELECT s.id, s.name, s.created_at, s.refresh_minutes, " +
                "(SELECT COUNT(*) FROM stream_feeds sf WHERE sf.stream_id = s.id), " +
                "(SELECT COUNT(*) FROM stream_categories sc WHERE sc.stream_id = s.id) " +
                "FROM streams s WHERE s.id = ?",
            arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) streamFrom(c) else null }
    }

    fun createStream(
        name: String,
        feedIds: Collection<Long> = emptyList(),
        categories: Collection<String> = emptyList(),
        refreshMinutes: Int = Stream.REFRESH_DEFAULT,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val id = db.insert("streams", null, ContentValues().apply {
            put("name", name)
            put("created_at", now)
            put("refresh_minutes", refreshMinutes)
        })
        if (id > 0) {
            if (feedIds.isNotEmpty()) setStreamFeeds(id, feedIds)
            if (categories.isNotEmpty()) setStreamCategories(id, categories)
        }
        return id
    }

    fun updateStream(id: Long, name: String, refreshMinutes: Int) {
        db.update(
            "streams",
            ContentValues().apply {
                put("name", name)
                put("refresh_minutes", refreshMinutes)
            },
            "id = ?", arrayOf(id.toString()),
        )
    }

    fun renameStream(id: Long, name: String) {
        db.update("streams", ContentValues().apply { put("name", name) }, "id = ?", arrayOf(id.toString()))
    }

    fun deleteStream(id: Long) {
        db.delete("streams", "id = ?", arrayOf(id.toString()))
    }

    fun streamFeedIds(streamId: Long): Set<Long> {
        if (streamId == Stream.ALL_ID || streamId == Stream.SAVED_ID) return emptySet()
        return db.rawQuery(
            "SELECT feed_id FROM stream_feeds WHERE stream_id = ?",
            arrayOf(streamId.toString()),
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    fun streamCategories(streamId: Long): Set<String> {
        if (streamId == Stream.ALL_ID || streamId == Stream.SAVED_ID) return emptySet()
        return db.rawQuery(
            "SELECT category FROM stream_categories WHERE stream_id = ?",
            arrayOf(streamId.toString()),
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    }

    fun setStreamFeeds(streamId: Long, feedIds: Collection<Long>) {
        db.beginTransaction()
        try {
            db.delete("stream_feeds", "stream_id = ?", arrayOf(streamId.toString()))
            for (feedId in feedIds.distinct()) {
                db.insertWithOnConflict(
                    "stream_feeds", null,
                    ContentValues().apply {
                        put("stream_id", streamId)
                        put("feed_id", feedId)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setStreamCategories(streamId: Long, names: Collection<String>) {
        db.beginTransaction()
        try {
            db.delete("stream_categories", "stream_id = ?", arrayOf(streamId.toString()))
            for (name in names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
                db.insertWithOnConflict(
                    "stream_categories", null,
                    ContentValues().apply {
                        put("stream_id", streamId)
                        put("category", name)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ------------------------------------------------------------- articles

    /**
     * Write a fetch's items.
     *
     * The conflict target is `(feed_id, guid)`, and the update deliberately
     * leaves `published_at`, `read` and `saved` alone:
     *
     *  - **published_at** because a publisher who re-stamps a story would
     *    otherwise send it back to the top of the list every refresh;
     *  - **read** and **saved** because they are the reader's, not the feed's.
     *
     * @return how many rows were genuinely new.
     */
    fun upsertArticles(feedId: Long, items: List<ParsedItem>, now: Long = System.currentTimeMillis()): Int {
        if (items.isEmpty()) return 0
        var fresh = 0
        db.beginTransaction()
        try {
            for (item in items) {
                val link = item.link?.takeIf { it.isNotBlank() } ?: continue
                val guid = item.guid?.takeIf { it.isNotBlank() } ?: link
                val values = Db.valuesFor(feedId, item, now)
                val rowId = db.insertWithOnConflict("articles", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                if (rowId > 0) {
                    fresh++
                } else {
                    values.remove("published_at")
                    values.remove("fetched_at")
                    db.update(
                        "articles", values, "feed_id = ? AND guid = ?",
                        arrayOf(feedId.toString(), guid),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return fresh
    }

    /**
     * The article list for a stream.
     *
     * @param streamId [Stream.ALL_ID], [Stream.SAVED_ID] or a real stream.
     * @param query free text over title, summary and feed name; blank for none.
     */
    fun articles(
        streamId: Long,
        unreadOnly: Boolean = false,
        query: String = "",
        limit: Int = 200,
        offset: Int = 0,
    ): List<Article> {
        val where = StringBuilder()
        val args = ArrayList<String>()

        when (streamId) {
            Stream.ALL_ID -> Unit
            Stream.SAVED_ID -> where.append(" AND a.saved = 1 ")
            else -> {
                where.append(" AND ").append(Db.streamMember("a.feed_id")).append(' ')
                args.add(streamId.toString())
                args.add(streamId.toString())
            }
        }
        if (unreadOnly && streamId != Stream.SAVED_ID) where.append(" AND a.read = 0 ")
        val q = query.trim()
        if (q.isNotEmpty()) {
            where.append(" AND (a.title LIKE ? OR a.summary LIKE ? OR f.title LIKE ?) ")
            val like = "%" + q.replace("%", "").replace("_", "") + "%"
            args.add(like); args.add(like); args.add(like)
        }

        val order = if (streamId == Stream.SAVED_ID) "a.saved_at DESC" else "a.published_at DESC"
        val sql = "SELECT ${Db.ARTICLE_COLUMNS} FROM articles a JOIN feeds f ON f.id = a.feed_id " +
            "WHERE 1=1 $where ORDER BY $order, a.id DESC LIMIT $limit OFFSET $offset"
        return db.rawQuery(sql, args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(Db.articleFrom(c)) }
        }
    }

    fun article(id: Long): Article? = db.rawQuery(
        "SELECT ${Db.ARTICLE_COLUMNS} FROM articles a JOIN feeds f ON f.id = a.feed_id WHERE a.id = ?",
        arrayOf(id.toString()),
    ).use { c -> if (c.moveToFirst()) Db.articleFrom(c) else null }

    fun setRead(id: Long, read: Boolean) {
        db.update("articles", ContentValues().apply { put("read", if (read) 1 else 0) }, "id = ?", arrayOf(id.toString()))
    }

    fun markStreamRead(streamId: Long) {
        when (streamId) {
            Stream.ALL_ID -> db.execSQL("UPDATE articles SET read = 1 WHERE read = 0")
            Stream.SAVED_ID -> db.execSQL("UPDATE articles SET read = 1 WHERE read = 0 AND saved = 1")
            else -> db.execSQL(
                "UPDATE articles SET read = 1 WHERE read = 0 AND feed_id IN " +
                    "(SELECT f.id FROM feeds f WHERE ${Db.streamMember("f.id")})",
                arrayOf<Any>(streamId, streamId),
            )
        }
    }

    fun setSaved(id: Long, saved: Boolean, now: Long = System.currentTimeMillis()) {
        db.update(
            "articles",
            ContentValues().apply {
                put("saved", if (saved) 1 else 0)
                put("saved_at", if (saved) now else 0L)
            },
            "id = ?", arrayOf(id.toString()),
        )
    }

    fun unreadCount(streamId: Long): Int = when (streamId) {
        Stream.ALL_ID -> countOf("SELECT COUNT(*) FROM articles WHERE read = 0")
        Stream.SAVED_ID -> countOf("SELECT COUNT(*) FROM articles WHERE read = 0 AND saved = 1")
        else -> db.rawQuery(
            "SELECT COUNT(*) FROM articles a WHERE a.read = 0 AND a.feed_id IN " +
                "(SELECT f.id FROM feeds f WHERE ${Db.streamMember("f.id")})",
            arrayOf(streamId.toString(), streamId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun savedCount(): Int = countOf("SELECT COUNT(*) FROM articles WHERE saved = 1")

    fun feedCount(): Int = countOf("SELECT COUNT(*) FROM feeds")

    /**
     * Over-fetch for the widget.
     *
     * [com.dispatch.reader.widget.WidgetMix] needs several articles per feed to
     * choose between, so this asks for far more rows than the widget shows.
     */
    fun widgetCandidates(streamId: Long, take: Int = 120): List<Article> =
        articles(streamId = streamId, unreadOnly = false, limit = take)

    // ------------------------------------------------------------- refresh

    /** A feed and the cadence its streams imply. */
    data class Due(val feed: Feed, val cadenceMinutes: Int)

    /**
     * Every feed, with the refresh cadence its streams ask for.
     *
     * A feed inherits the **shortest** cadence of the streams it belongs to,
     * by either kind of membership. A feed in no stream, or in streams that all
     * say "use the default", gets [Stream.REFRESH_DEFAULT] and the caller
     * substitutes the app-wide interval.
     *
     * `MIN(NULLIF(refresh_minutes, 0))` is what does that: zero means "default"
     * rather than "never", so it must not win a MIN against a real cadence.
     */
    fun feedsWithCadence(): List<Due> = db.rawQuery(
        """
        SELECT $FEED_COLUMNS, COALESCE(MIN(NULLIF(s.refresh_minutes, 0)), 0)
          FROM feeds f
          LEFT JOIN streams s ON s.id IN (
                SELECT sf.stream_id FROM stream_feeds sf WHERE sf.feed_id = f.id
                UNION
                SELECT sc.stream_id FROM stream_categories sc WHERE sc.category = f.category
          )
         GROUP BY f.id
         ORDER BY f.last_fetch_at
        """.trimIndent(),
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(Due(feedFrom(c), c.getInt(12)))
        }
    }

    // ------------------------------------------------------------ retention

    /**
     * Delete what nobody will miss.
     *
     * Two rules, both conservative:
     *  - a **saved** article is never deleted, at any age;
     *  - a feed always keeps its newest [keepPerFeed] articles regardless of
     *    age, so a monthly publication does not vanish between issues.
     *
     * @return rows deleted.
     */
    fun purge(keepDays: Int, keepPerFeed: Int = 50, now: Long = System.currentTimeMillis()): Int {
        if (keepDays <= 0) return 0
        val cutoff = now - keepDays * 24L * 60L * 60L * 1000L
        val sql = """
            DELETE FROM articles
             WHERE saved = 0
               AND published_at < ?
               AND id NOT IN (
                     SELECT id FROM articles a2
                      WHERE a2.feed_id = articles.feed_id
                      ORDER BY a2.published_at DESC
                      LIMIT ?
               )
        """.trimIndent()
        db.execSQL(sql, arrayOf<Any>(cutoff, keepPerFeed))
        return changes()
    }

    private fun changes(): Int = countOf("SELECT changes()")

    fun vacuum() {
        runCatching { db.execSQL("VACUUM") }
    }

    // -------------------------------------------------------------- helpers

    fun isEmpty(): Boolean = countOf("SELECT COUNT(*) FROM feeds") == 0

    private fun countOf(sql: String): Int =
        db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun feedFrom(c: Cursor): Feed = Feed(
        id = c.getLong(0),
        url = c.getString(1),
        title = c.getString(2),
        siteLink = c.getStringOrNull(3),
        iconUrl = c.getStringOrNull(4),
        category = c.getStringOrNull(5),
        addedAt = c.getLong(6),
        lastFetchAt = c.getLong(7),
        lastError = c.getStringOrNull(8),
        lastCount = c.getInt(9),
        etag = c.getStringOrNull(10),
        lastModified = c.getStringOrNull(11),
    )

    private fun streamFrom(c: Cursor): Stream = Stream(
        id = c.getLong(0),
        name = c.getString(1),
        createdAt = c.getLong(2),
        refreshMinutes = c.getInt(3),
        feedCount = c.getInt(4),
        categoryCount = c.getInt(5),
    )

    companion object {
        /** Passed as `category` to [feeds] to select the feeds with none. */
        const val UNCATEGORISED = "\u0000uncategorised"

        private const val FEED_COLUMNS =
            "f.id, f.url, f.title, f.site_link, f.icon_url, f.category, f.added_at, " +
                "f.last_fetch_at, f.last_error, f.last_count, f.etag, f.last_modified"

        private const val FEED_SELECT = "SELECT $FEED_COLUMNS FROM feeds f"

        /** A readable fallback title for a feed whose document has none. */
        fun hostOf(url: String): String = runCatching {
            java.net.URI(url).host?.removePrefix("www.") ?: url
        }.getOrDefault(url)
    }
}
