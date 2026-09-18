package com.dispatch.reader.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.dispatch.reader.feed.ParsedFeed
import com.dispatch.reader.feed.Sanitize

/**
 * The store. A hand-written [SQLiteOpenHelper] rather than Room.
 *
 * Room would be less code, but it is an annotation processor, and this project
 * has no way to compile the app while writing it. A code generator that fails
 * at build time is exactly the kind of failure that cannot be diagnosed from
 * here, whereas SQL that is wrong fails at run time with a message naming the
 * column. The tables are also simple enough that Room would be buying very
 * little.
 *
 * Everything here runs off the main thread; callers use [Repo].
 */
class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE feeds (
              id            INTEGER PRIMARY KEY AUTOINCREMENT,
              url           TEXT NOT NULL UNIQUE,
              title         TEXT NOT NULL,
              site_link     TEXT,
              icon_url      TEXT,
              category      TEXT,
              added_at      INTEGER NOT NULL DEFAULT 0,
              last_fetch_at INTEGER NOT NULL DEFAULT 0,
              last_error    TEXT,
              last_count    INTEGER NOT NULL DEFAULT 0,
              etag          TEXT,
              last_modified TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE streams (
              id              INTEGER PRIMARY KEY AUTOINCREMENT,
              name            TEXT NOT NULL,
              created_at      INTEGER NOT NULL DEFAULT 0,
              refresh_minutes INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE stream_feeds (
              stream_id INTEGER NOT NULL REFERENCES streams(id) ON DELETE CASCADE,
              feed_id   INTEGER NOT NULL REFERENCES feeds(id)   ON DELETE CASCADE,
              PRIMARY KEY (stream_id, feed_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE categories (
              name       TEXT PRIMARY KEY,
              created_at INTEGER NOT NULL DEFAULT 0,
              builtin    INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE stream_categories (
              stream_id INTEGER NOT NULL REFERENCES streams(id) ON DELETE CASCADE,
              category  TEXT NOT NULL,
              PRIMARY KEY (stream_id, category)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE articles (
              id           INTEGER PRIMARY KEY AUTOINCREMENT,
              feed_id      INTEGER NOT NULL REFERENCES feeds(id) ON DELETE CASCADE,
              guid         TEXT NOT NULL,
              link         TEXT NOT NULL,
              title        TEXT NOT NULL,
              author       TEXT,
              summary      TEXT,
              body_html    TEXT,
              image_url    TEXT,
              published_at INTEGER NOT NULL DEFAULT 0,
              fetched_at   INTEGER NOT NULL DEFAULT 0,
              read         INTEGER NOT NULL DEFAULT 0,
              saved        INTEGER NOT NULL DEFAULT 0,
              saved_at     INTEGER NOT NULL DEFAULT 0,
              UNIQUE (feed_id, guid)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_articles_published ON articles(published_at DESC)")
        db.execSQL("CREATE INDEX idx_articles_feed ON articles(feed_id, published_at DESC)")
        db.execSQL("CREATE INDEX idx_articles_saved ON articles(saved, saved_at DESC)")
        db.execSQL("CREATE INDEX idx_stream_feeds_feed ON stream_feeds(feed_id)")
        db.execSQL("CREATE INDEX idx_feeds_category ON feeds(category)")
    }

    /**
     * Migrations are additive and never destructive.
     *
     * `articles.saved` is the reader's own library and a reader that loses
     * saved stories on an update has failed at the one job they did explicitly.
     * Streams are the same: a stream someone built by hand is not the app's to
     * throw away because the app changed its mind about defaults.
     *
     * The v1 → v2 path is exercised by `tools/sql_suite.py`, which builds a
     * real v1 database, runs these statements against it and checks that the
     * rows are still there afterwards — the suite has a standing bug class for
     * "no upgrade-path test".
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            for (statement in MIGRATE_1_TO_2) db.execSQL(statement)
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never destructive. A downgrade means someone installed an older
        // build; leaving the newer schema in place is better than deleting
        // their data.
    }

    companion object {
        const val NAME = "dispatch.db"

        /**
         * 1 — first release.
         * 2 — categories as a table, category membership for streams, and a
         *     per-stream refresh cadence.
         */
        const val VERSION = 2

        /**
         * v1 → v2, as a list so the test can run exactly what the app runs.
         *
         * `ALTER TABLE ... ADD COLUMN` cannot add a foreign key, which is why
         * `feeds.category` stays a plain TEXT label rather than becoming a
         * reference. Renaming a category therefore updates three tables in one
         * transaction; see [Repo.renameCategory].
         */
        val MIGRATE_1_TO_2: List<String> = listOf(
            """
            CREATE TABLE IF NOT EXISTS categories (
              name       TEXT PRIMARY KEY,
              created_at INTEGER NOT NULL DEFAULT 0,
              builtin    INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS stream_categories (
              stream_id INTEGER NOT NULL REFERENCES streams(id) ON DELETE CASCADE,
              category  TEXT NOT NULL,
              PRIMARY KEY (stream_id, category)
            )
            """.trimIndent(),
            "ALTER TABLE streams ADD COLUMN refresh_minutes INTEGER NOT NULL DEFAULT 0",
            "CREATE INDEX IF NOT EXISTS idx_feeds_category ON feeds(category)",
            // Every label already in use becomes a row, so the library's filter
            // and the stream builder see the categories a v1 install already
            // had. builtin is set from the shipped list by Seed.
            "INSERT OR IGNORE INTO categories (name, created_at, builtin) " +
                "SELECT DISTINCT category, 0, 0 FROM feeds WHERE category IS NOT NULL AND category <> ''",
            // Seed does this on a fresh install, but an upgrade never runs it
            // (the install is already seeded), so the seven shipped labels
            // would read as the reader's own forever.
            // Written out rather than built from Categories.ORDER: a migration
            // is a record of what one past version did, and it must not change
            // meaning later because a constant was edited.
            "UPDATE categories SET builtin = 1 WHERE name IN (" +
                "'World', 'Politics & Geopolitics', 'Business', 'Science & Technology', " +
                "'Health & Wellness', 'Sports', 'Entertainment & Pop Culture')",
        )

        /** Plain-text summaries are for list rows; anything longer is waste. */
        const val SUMMARY_CHARS = 600

        /** The article screen shows the feed's own HTML, but not an unbounded amount of it. */
        const val BODY_CHARS = 20_000

        fun articleFrom(c: Cursor): Article = Article(
            id = c.getLong(0),
            feedId = c.getLong(1),
            guid = c.getString(2),
            link = c.getString(3),
            title = c.getString(4),
            author = c.getStringOrNull(5),
            summary = c.getStringOrNull(6),
            bodyHtml = c.getStringOrNull(7),
            imageUrl = c.getStringOrNull(8),
            publishedAt = c.getLong(9),
            fetchedAt = c.getLong(10),
            read = c.getInt(11) != 0,
            saved = c.getInt(12) != 0,
            feedTitle = if (c.columnCount > 13) c.getString(13) else "",
        )

        const val ARTICLE_COLUMNS =
            "a.id, a.feed_id, a.guid, a.link, a.title, a.author, a.summary, a.body_html, " +
                "a.image_url, a.published_at, a.fetched_at, a.read, a.saved, f.title"

        /**
         * **The membership rule, in one place.**
         *
         * A stream shows a feed when the feed was picked explicitly *or* when
         * its category was. Every query that asks "what is in this stream"
         * goes through this fragment, so the two kinds of membership cannot
         * drift apart between the article list, the widget and the unread
         * count. `%s` is the feed-id expression to test (`a.feed_id` when
         * joining articles, `f.id` when listing feeds); both `?` take the same
         * stream id.
         */
        const val STREAM_MEMBER_TEMPLATE =
            "(%s IN (SELECT feed_id FROM stream_feeds WHERE stream_id = ?) " +
                "OR (f.category IS NOT NULL AND f.category IN " +
                "(SELECT category FROM stream_categories WHERE stream_id = ?)))"

        fun streamMember(feedIdColumn: String): String =
            STREAM_MEMBER_TEMPLATE.format(feedIdColumn)

        /**
         * The row values for one parsed item.
         *
         * `published_at` falls back to *now* when the feed gave no date, so an
         * undated item sorts by when it arrived instead of by the epoch, which
         * would bury it at the bottom of the list forever.
         */
        fun valuesFor(feedId: Long, item: com.dispatch.reader.feed.ParsedItem, now: Long): ContentValues =
            ContentValues().apply {
                put("feed_id", feedId)
                put("guid", item.guid ?: item.link)
                put("link", item.link)
                put("title", item.title)
                put("author", item.author)
                put("summary", Sanitize.clip(Sanitize.htmlToText(item.summaryHtml), SUMMARY_CHARS))
                put("body_html", item.summaryHtml?.take(BODY_CHARS))
                put("image_url", item.imageUrl)
                put("published_at", item.publishedAt ?: now)
                put("fetched_at", now)
            }

        fun feedValuesFrom(parsed: ParsedFeed): ContentValues = ContentValues().apply {
            parsed.siteLink?.let { put("site_link", it) }
            parsed.iconUrl?.let { put("icon_url", it) }
        }
    }
}

internal fun Cursor.getStringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)
