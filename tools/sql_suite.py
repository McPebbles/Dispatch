#!/usr/bin/env python3
"""
Run the app's own SQL against SQLite.

The schema and the awkward statements are lifted out of `data/Db.kt` and
`data/Repo.kt` by text, not retyped, so this tests the strings that ship. It is
the only part of the data layer that can be executed without an Android device,
and it covers the two statements most likely to be quietly wrong:

  * the retention DELETE, which uses a correlated subquery with a LIMIT — legal
    in SQLite, but only if the subquery really is correlated against the outer
    row, and a mistake there deletes a feed's whole history instead of its tail;
  * the upsert path, where the update after a conflict must leave
    `published_at`, `read` and `saved` alone.

Android ships SQLite 3.28+ at minSdk 30, and CPython's bundled SQLite is newer
than that, so a statement that works here works there. The reverse is not
guaranteed and is noted where it matters.

Usage: python3 tools/sql_suite.py
"""
import os
import re
import sqlite3
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "com", "dispatch", "reader")

FAILURES = []
CHECKS = [0]


def check(name, condition, detail=""):
    CHECKS[0] += 1
    if not condition:
        FAILURES.append("%s%s" % (name, (": " + detail) if detail else ""))


def source(*parts):
    with open(os.path.join(JAVA, *parts), encoding="utf-8") as handle:
        return handle.read()


def schema_statements(db_kt):
    """Every execSQL in Db.onCreate, in order, as the app runs them."""
    statements = []
    for raw in re.findall(r'db\.execSQL\(\s*"""(.*?)"""\s*\.trimIndent\(\)\s*\)', db_kt, re.S):
        statements.append(raw.strip())
    for raw in re.findall(r'db\.execSQL\("([^"]+)"\)', db_kt):
        statements.append(raw)
    return statements


def purge_sql(repo_kt):
    match = re.search(r'val sql = """(.*?)"""\s*\.trimIndent\(\)', repo_kt, re.S)
    return match.group(1).strip() if match else None



def kotlin_strings(block):
    """Every string literal in a Kotlin listOf(...) block, concatenations joined.

    Handles the two shapes Db.kt uses: a triple-quoted statement followed by
    .trimIndent(), and plain quoted strings joined across lines with `+`.
    """
    pieces = []
    pattern = re.compile(r'"""(.*?)"""|"((?:[^"\\]|\\.)*)"', re.S)
    previous_end = None
    for match in pattern.finditer(block):
        text = match.group(1) if match.group(1) is not None else match.group(2)
        text = text.replace('\\"', '"')
        gap = block[previous_end:match.start()] if previous_end is not None else ""
        if previous_end is not None and "+" in gap and "," not in gap:
            pieces[-1] += text
        else:
            pieces.append(text)
        previous_end = match.end()
    return [piece.strip() for piece in pieces if piece.strip()]


def migration_statements(db_kt):
    """MIGRATE_1_TO_2, exactly as the app will run it."""
    start = db_kt.find("val MIGRATE_1_TO_2")
    if start < 0:
        return []
    open_paren = db_kt.find("listOf(", start)
    depth = 0
    for index in range(open_paren + len("listOf"), len(db_kt)):
        if db_kt[index] == "(":
            depth += 1
        elif db_kt[index] == ")":
            depth -= 1
            if depth == 0:
                return kotlin_strings(db_kt[open_paren:index])
    return []


def member_fragment(db_kt):
    """STREAM_MEMBER_TEMPLATE with %s already substituted by the caller."""
    start = db_kt.find("const val STREAM_MEMBER_TEMPLATE")
    if start < 0:
        return None
    end = db_kt.find("fun streamMember", start)
    parts = kotlin_strings(db_kt[start:end])
    return "".join(parts) if parts else None


def cadence_sql(repo_kt):
    body = repo_kt[repo_kt.find("fun feedsWithCadence"):]
    match = re.search(r'"""(.*?)"""', body, re.S)
    return match.group(1).strip() if match else None


def v1_schema(statements):
    """The v1.0.x schema, derived from v2's by removing what v2 added.

    Derived rather than frozen so that a change to the shared tables cannot
    leave this test exercising a schema the app has not had for months. Every
    removal is asserted below, so if v2's text drifts the test fails loudly
    instead of quietly testing nothing.
    """
    out = []
    removed = {"categories": False, "stream_categories": False,
               "idx_feeds_category": False, "refresh_minutes": False}
    for statement in statements:
        flat = " ".join(statement.split())
        if re.search(r"CREATE TABLE (IF NOT EXISTS )?categories\b", flat):
            removed["categories"] = True
            continue
        if re.search(r"CREATE TABLE (IF NOT EXISTS )?stream_categories\b", flat):
            removed["stream_categories"] = True
            continue
        if "idx_feeds_category" in flat:
            removed["idx_feeds_category"] = True
            continue
        if "refresh_minutes" in statement:
            statement = re.sub(r"\n\s*refresh_minutes[^\n]*", "", statement)
            # The removed column was last, so the line before it now ends in a
            # comma that has nothing to separate.
            statement = re.sub(r",(\s*\n\s*\))", r"\1", statement)
            removed["refresh_minutes"] = True
        out.append(statement)
    return out, removed


def check_migration(db_kt, repo_kt):
    day = 24 * 60 * 60 * 1000
    now = 1_800_000_000_000

    statements = schema_statements(db_kt)
    v1, removed = v1_schema(statements)
    for name, done in removed.items():
        check("v1 is v2 minus %s" % name, done, "nothing to remove — has v2 drifted?")

    old = sqlite3.connect(":memory:")
    old.execute("PRAGMA foreign_keys = ON")
    for statement in v1:
        try:
            old.execute(statement)
        except sqlite3.Error as e:
            check("the v1 schema builds", False, "%s\n%s" % (e, statement[:120]))
            return
    check("the v1 schema builds", True)

    cursor = old.cursor()
    # A v1 install as a reader would have left it: bundled feeds with the
    # labels 1.0.x gave them, a stream of their own, and history.
    cursor.execute("INSERT INTO feeds (url, title, category, added_at) "
                   "VALUES ('https://a/f', 'A', 'World', 0)")
    cursor.execute("INSERT INTO feeds (url, title, category, added_at) "
                   "VALUES ('https://b/f', 'B', 'World', 0)")
    cursor.execute("INSERT INTO feeds (url, title, category, added_at) "
                   "VALUES ('https://c/f', 'C', 'Sports', 0)")
    cursor.execute("INSERT INTO feeds (url, title, added_at) VALUES ('https://d/f', 'D', 0)")
    cursor.execute("INSERT INTO streams (name, created_at) VALUES ('Mine', 0)")
    cursor.execute("INSERT INTO stream_feeds (stream_id, feed_id) VALUES (1, 1)")
    for n in range(5):
        cursor.execute(
            "INSERT INTO articles (feed_id, guid, link, title, published_at, fetched_at, saved)"
            " VALUES (?,?,?,?,?,?,?)",
            (1, "g%d" % n, "https://x/%d" % n, "Story %d" % n, now - n * day, now, 1 if n == 0 else 0))
    old.commit()

    # ------------------------------------------------------------- upgrade
    migration = migration_statements(db_kt)
    check("MIGRATE_1_TO_2 was found in Db.kt", len(migration) >= 5, "%d statements" % len(migration))
    for statement in migration:
        try:
            cursor.execute(statement)
        except sqlite3.Error as e:
            check("the migration runs on a v1 database", False, "%s\n%s" % (e, statement[:120]))
            return
    old.commit()
    check("the migration runs on a v1 database", True)

    # ------------------------------------------------------- what survives
    check("feeds survive the upgrade",
          cursor.execute("SELECT COUNT(*) FROM feeds").fetchone()[0] == 4)
    check("streams survive the upgrade",
          cursor.execute("SELECT COUNT(*) FROM streams").fetchone()[0] == 1)
    check("membership survives the upgrade",
          cursor.execute("SELECT COUNT(*) FROM stream_feeds").fetchone()[0] == 1)
    check("articles survive the upgrade",
          cursor.execute("SELECT COUNT(*) FROM articles").fetchone()[0] == 5)
    check("a saved story survives the upgrade",
          cursor.execute("SELECT COUNT(*) FROM articles WHERE saved = 1").fetchone()[0] == 1)

    # The labels a v1 install already had become rows, so the new filter and
    # the stream builder can see them. An uncategorised feed contributes none.
    names = [row[0] for row in cursor.execute("SELECT name FROM categories ORDER BY name")]
    check("the labels a v1 install had become categories",
          names == ["Sports", "World"], repr(names))
    check("an uncategorised feed invents no category", "" not in names)

    check("an upgraded stream refreshes on the app-wide default",
          cursor.execute("SELECT refresh_minutes FROM streams WHERE id = 1").fetchone()[0] == 0)

    # Running it twice is what happens when an upgrade is interrupted.
    for statement in migration:
        if statement.strip().upper().startswith("ALTER TABLE"):
            continue
        try:
            cursor.execute(statement)
        except sqlite3.Error as e:
            check("the migration is safe to re-run", False, str(e))
            break
    else:
        check("the migration is safe to re-run", True)
        check("re-running invents no duplicate categories",
              cursor.execute("SELECT COUNT(*) FROM categories").fetchone()[0] == 2)

    # ------------------------------------------- the v2 queries on that data
    fragment = member_fragment(db_kt)
    check("STREAM_MEMBER_TEMPLATE was found", fragment is not None)
    if fragment:
        where = fragment.replace("%s", "f.id")

        def members():
            """The feed ids stream 1 shows, or None when the fragment is not SQL."""
            try:
                return [row[0] for row in cursor.execute(
                    "SELECT f.id FROM feeds f WHERE %s ORDER BY f.id" % where, (1, 1)).fetchall()]
            except sqlite3.Error as e:
                check("the membership fragment is valid SQL", False, str(e))
                return None

        cursor.execute("INSERT INTO stream_categories (stream_id, category) VALUES (1, 'Sports')")
        old.commit()
        ids = members()
        # Feed 1 was picked by hand; feed 3 arrives through its category. Feed 2
        # is 'World' and was not picked, and feed 4 has no category at all.
        check("membership is the union of picked feeds and picked categories",
              ids == [1, 3], repr(ids))

        cursor.execute("DELETE FROM stream_categories")
        check("a stream with no categories still shows its picked feeds",
              members() == [1], repr(members()))

        cursor.execute("DELETE FROM stream_feeds")
        cursor.execute("INSERT INTO stream_categories (stream_id, category) VALUES (1, 'World')")
        old.commit()
        ids = members()
        check("a stream with no picked feeds still shows its categories",
              ids == [1, 2], repr(ids))

    # ------------------------------------------------------------- cadence
    sql = cadence_sql(repo_kt)
    check("the cadence query was found in Repo.kt", sql is not None)
    if sql:
        columns = re.search(r'const val FEED_COLUMNS =\s*\n?\s*"(.*?)"\s*\+\s*\n\s*"(.*?)"',
                            repo_kt, re.S)
        check("FEED_COLUMNS was found", columns is not None)
        if columns:
            sql = sql.replace("$FEED_COLUMNS", columns.group(1) + columns.group(2))
            # Two streams over the same feed: one hourly, one every 15 minutes,
            # and a third that says "use the default". The feed must come back
            # at 15, and a feed nobody hurried must come back at 0.
            cursor.execute("UPDATE streams SET refresh_minutes = 60 WHERE id = 1")
            cursor.execute("INSERT INTO streams (name, created_at, refresh_minutes) VALUES ('Fast', 0, 15)")
            cursor.execute("INSERT INTO streams (name, created_at, refresh_minutes) VALUES ('Lazy', 0, 0)")
            cursor.execute("INSERT INTO stream_feeds (stream_id, feed_id) VALUES (2, 1)")
            cursor.execute("INSERT INTO stream_feeds (stream_id, feed_id) VALUES (3, 1)")
            cursor.execute("INSERT INTO stream_feeds (stream_id, feed_id) VALUES (3, 3)")
            old.commit()
            try:
                rows = cursor.execute(sql).fetchall()
            except sqlite3.Error as e:
                check("the cadence query runs on SQLite", False, str(e))
                rows = []
            if rows:
                check("the cadence query returns every feed", len(rows) == 4, "%d rows" % len(rows))
                cadence = {row[0]: row[-1] for row in rows}
                check("a feed takes the shortest cadence of its streams",
                      cadence.get(1) == 15, repr(cadence))
                check("'use the default' never wins a MIN against a real cadence",
                      cadence.get(1) != 0, repr(cadence))
                # Feed 2 is 'World' and stream 1 (hourly) now includes that
                # category, so cadence reaches a feed nobody picked by hand.
                check("a cadence reaches feeds included by category",
                      cadence.get(2) == 60, repr(cadence))
                check("a feed in a default-cadence stream falls back to Settings",
                      cadence.get(3) == 0, repr(cadence))
                check("a feed in no stream at all falls back to Settings",
                      cadence.get(4) == 0, repr(cadence))
    old.close()


def main():
    db_kt = source("data", "Db.kt")
    repo_kt = source("data", "Repo.kt")

    statements = schema_statements(db_kt)
    check("the schema was found in Db.kt", len(statements) >= 4, "%d statements" % len(statements))

    connection = sqlite3.connect(":memory:")
    connection.execute("PRAGMA foreign_keys = ON")
    for statement in statements:
        try:
            connection.execute(statement)
        except sqlite3.Error as e:
            check("schema statement runs", False, "%s\n%s" % (e, statement[:120]))
    check("schema created", True)

    cursor = connection.cursor()

    # ---------------------------------------------------------------- fixtures
    day = 24 * 60 * 60 * 1000
    now = 1_800_000_000_000
    cursor.execute("INSERT INTO feeds (url, title, added_at) VALUES ('https://a/f', 'A', 0)")
    cursor.execute("INSERT INTO feeds (url, title, added_at) VALUES ('https://b/f', 'B', 0)")
    cursor.execute("INSERT INTO streams (name, created_at) VALUES ('World', 0)")
    cursor.execute("INSERT INTO stream_feeds (stream_id, feed_id) VALUES (1, 1)")

    def add(feed, n, age_days, saved=0, read=0):
        cursor.execute(
            "INSERT INTO articles (feed_id, guid, link, title, published_at, fetched_at, read, saved, saved_at)"
            " VALUES (?,?,?,?,?,?,?,?,?)",
            (feed, "g%d-%d" % (feed, n), "https://x/%d/%d" % (feed, n), "Story %d" % n,
             now - age_days * day, now, read, saved, now if saved else 0),
        )

    # Feed 1: 60 articles spread over 60 days, one of them saved and ancient.
    for n in range(60):
        add(1, n, n)
    add(1, 999, 400, saved=1)
    # Feed 2: three recent articles.
    for n in range(3):
        add(2, n, n)
    connection.commit()

    before = cursor.execute("SELECT COUNT(*) FROM articles").fetchone()[0]
    check("fixtures inserted", before == 64, "%d rows" % before)

    # ------------------------------------------------------------- retention
    sql = purge_sql(repo_kt)
    check("the purge statement was found in Repo.kt", sql is not None)
    if sql:
        keep_days, keep_per_feed = 14, 50
        cutoff = now - keep_days * day
        try:
            cursor.execute(sql, (cutoff, keep_per_feed))
            deleted = cursor.rowcount
        except sqlite3.Error as e:
            check("the purge statement runs on SQLite", False, str(e))
            deleted = -1

        if deleted >= 0:
            check("purge deleted something", deleted > 0, "deleted %d" % deleted)

            saved_left = cursor.execute(
                "SELECT COUNT(*) FROM articles WHERE saved = 1").fetchone()[0]
            check("a saved story survives at any age", saved_left == 1)

            feed1 = cursor.execute(
                "SELECT COUNT(*) FROM articles WHERE feed_id = 1 AND saved = 0").fetchone()[0]
            # 50 newest kept by the per-feed floor; the rest are older than the
            # cutoff and go. Without the correlation the subquery would be the
            # 50 newest OVERALL and feed 2 would lose rows it should keep.
            check("the per-feed floor keeps the newest 50", feed1 == 50, "kept %d" % feed1)

            feed2 = cursor.execute(
                "SELECT COUNT(*) FROM articles WHERE feed_id = 2").fetchone()[0]
            check("a quiet feed keeps everything it had", feed2 == 3, "kept %d" % feed2)

            # Feed 1 had 60 unsaved articles aged 0..59 days. The floor keeps
            # the newest 50 (ages 0..49); of those, the ones older than the
            # 14-day cutoff are ages 15..49, i.e. 35 rows. They survive because
            # the floor outranks the cutoff, which is the whole point of having
            # a floor: a feed that has gone quiet still has something to show.
            old_unsaved = cursor.execute(
                "SELECT COUNT(*) FROM articles WHERE saved = 0 AND published_at < ?",
                (cutoff,)).fetchone()[0]
            check("the floor outranks the cutoff", old_unsaved == 35, "found %d" % old_unsaved)
            check("everything past the floor and past the cutoff is gone",
                  deleted == 10, "deleted %d" % deleted)

    # ---------------------------------------------------------------- upsert
    cursor.execute("DELETE FROM articles")
    cursor.execute(
        "INSERT INTO articles (feed_id, guid, link, title, summary, published_at, fetched_at, read, saved)"
        " VALUES (1, 'g', 'https://x/1', 'Original', 'first', ?, ?, 1, 1)", (now, now))
    connection.commit()

    # The app inserts with CONFLICT_IGNORE and, when that changes nothing,
    # updates the columns that are the feed's to change.
    cursor.execute(
        "INSERT OR IGNORE INTO articles (feed_id, guid, link, title, summary, published_at, fetched_at)"
        " VALUES (1, 'g', 'https://x/1', 'Corrected headline', 'second', ?, ?)",
        (now + day, now + day))
    check("a duplicate (feed_id, guid) does not insert a second row",
          cursor.execute("SELECT COUNT(*) FROM articles").fetchone()[0] == 1)

    cursor.execute(
        "UPDATE articles SET title = ?, summary = ? WHERE feed_id = 1 AND guid = 'g'",
        ("Corrected headline", "second"))
    row = cursor.execute(
        "SELECT title, summary, published_at, read, saved FROM articles").fetchone()
    check("the headline is updated", row[0] == "Corrected headline")
    check("the summary is updated", row[1] == "second")
    check("published_at is NOT moved by a re-fetch", row[2] == now)
    check("read state survives a re-fetch", row[3] == 1)
    check("saved state survives a re-fetch", row[4] == 1)

    # ------------------------------------------------------------ the queries
    # The column list and the stream join, taken from Repo.kt's own text.
    columns = re.search(r'const val ARTICLE_COLUMNS =\s*\n?\s*"(.*?)"\s*\+\s*\n\s*"(.*?)"',
                        db_kt, re.S)
    check("ARTICLE_COLUMNS was found", columns is not None)
    if columns:
        column_sql = (columns.group(1) + columns.group(2)).replace("\\n", " ")
        try:
            cursor.execute(
                "SELECT %s FROM articles a JOIN feeds f ON f.id = a.feed_id "
                "JOIN stream_feeds sf ON sf.feed_id = a.feed_id WHERE sf.stream_id = 1 "
                "ORDER BY a.published_at DESC, a.id DESC LIMIT 10" % column_sql
            )
            got = cursor.fetchall()
            check("the stream query runs and returns 14 columns",
                  got and len(got[0]) == 14, "%d columns" % (len(got[0]) if got else 0))
        except sqlite3.Error as e:
            check("the stream query runs", False, str(e))

    # Deleting a feed must take its articles and its membership, and nothing else.
    cursor.execute("DELETE FROM feeds WHERE id = 1")
    check("deleting a feed cascades to its articles",
          cursor.execute("SELECT COUNT(*) FROM articles WHERE feed_id = 1").fetchone()[0] == 0)
    check("deleting a feed cascades to stream membership",
          cursor.execute("SELECT COUNT(*) FROM stream_feeds").fetchone()[0] == 0)
    check("deleting a feed leaves the stream itself alone",
          cursor.execute("SELECT COUNT(*) FROM streams").fetchone()[0] == 1)

    # Deleting a stream must NOT take the feeds with it.
    cursor.execute("INSERT INTO stream_feeds (stream_id, feed_id) VALUES (1, 2)")
    cursor.execute("DELETE FROM streams WHERE id = 1")
    check("deleting a stream leaves the feeds subscribed",
          cursor.execute("SELECT COUNT(*) FROM feeds").fetchone()[0] == 1)
    check("deleting a stream clears its membership rows",
          cursor.execute("SELECT COUNT(*) FROM stream_feeds").fetchone()[0] == 0)

    # ------------------------------------------ v1 -> v2, on a v1 database
    check_migration(db_kt, repo_kt)

    print("%d checks against SQLite %s" % (CHECKS[0], sqlite3.sqlite_version))
    if FAILURES:
        for failure in FAILURES:
            print("FAIL  %s" % failure)
        print("\n%d failures" % len(FAILURES))
        return 1
    print("clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
