#!/usr/bin/env python3
"""
Invariants this app has to keep, checked against the source rather than against
a copy of it.

Everything here is something a compiler cannot see and a unit test would need a
device for: which files are allowed to touch `android.*`, whether the widget's
PendingIntents have the mutability the platform requires, whether the bundled
feed set still agrees with the category list in the Kotlin, and whether the
widget's layout uses only the view types RemoteViews can inflate.

Usage: python3 tools/rules_suite.py
Exit:  0 clean, 1 with one line per failure.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app", "src", "main")
JAVA = os.path.join(MAIN, "java", "com", "dispatch", "reader")
RES = os.path.join(MAIN, "res")
TEST_JAVA = os.path.join(ROOT, "app", "src", "test", "java")
ANDROID = "{http://schemas.android.com/apk/res/android}"

FAILURES = []
CHECKS = [0]


def fail(msg):
    FAILURES.append(msg)


def check(name, condition, detail=""):
    CHECKS[0] += 1
    if not condition:
        fail("%s%s" % (name, (": " + detail) if detail else ""))


def read(*parts):
    path = os.path.join(JAVA, *parts)
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def strip_comments(text):
    """
    Source with its comments removed.

    Necessary because several of these rules search for a name, and this
    codebase explains its choices in prose: FeedParser's KDoc says why it does
    NOT use `android.util.Xml`, and build.gradle.kts says it pulls in no
    analytics SDK. A rule that cannot tell a mention from a use fails on the
    documentation that exists to prevent the very thing it is checking for.
    """
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def body_of(text, name):
    """
    A function's body, found by matching braces rather than by indentation.

    An earlier version of this file looked for the next line that was four
    spaces and a closing brace, which is wrong for anything inside a companion
    object: it swallowed every following function, and the mutability rules
    below then read the row template's flags while claiming to check the
    header buttons'. A check that quietly examines the wrong code is worse
    than no check.
    """
    start = text.find("fun %s(" % name)
    if start < 0:
        return None
    open_brace = text.find("{", start)
    if open_brace < 0:
        # An expression body: everything to the end of the statement.
        equals = text.find("=", start)
        return text[start:text.find("\n\n", start)] if equals > 0 else None
    depth = 0
    for i in range(open_brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    return None


def mentions(text, name):
    """Whole-token search: FLAG_IMMUTABLE must not match FLAG_MUTABLE."""
    return re.search(r"(?<![A-Za-z0-9_])%s(?![A-Za-z0-9_])" % re.escape(name), text) is not None


# ---------------------------------------------------------------------------
# 1. The pure core.
#
# Everything the JVM test suite exercises has to stay free of android.*, or the
# tests silently stop being able to run it. This is the invariant that makes
# FeedParserTest, OpmlTest, DatesTest and WidgetMixTest possible at all.
# ---------------------------------------------------------------------------

PURE = [
    ("feed", "Sanitize.kt"),
    ("feed", "Dates.kt"),
    ("feed", "FeedParser.kt"),
    ("feed", "ParsedFeed.kt"),
    ("feed", "Discovery.kt"),
    ("data", "Models.kt"),
    ("data", "Opml.kt"),
    ("widget", "WidgetMix.kt"),
    ("web", "BrowserChoice.kt"),
    ("web", "PrivateTabs.kt"),
]


def rule_pure_core():
    for parts in PURE:
        text = strip_comments(read(*parts))
        offenders = re.findall(r"^import (android\.[\w.]+)", text, re.M)
        check(
            "pure: %s imports no android.*" % "/".join(parts),
            not offenders,
            ", ".join(offenders),
        )
        # An android type can also arrive fully qualified.
        inline = re.findall(r"[^\w.](android\.(?:content|widget|view|util|app|graphics)\.\w+)", text)
        check(
            "pure: %s names no android type inline" % "/".join(parts),
            not inline,
            ", ".join(sorted(set(inline))),
        )


# ---------------------------------------------------------------------------
# 2. The widget's PendingIntents.
#
# A collection widget needs two kinds with opposite flags, and getting it wrong
# does not throw: an immutable template makes every row open the same article.
# ---------------------------------------------------------------------------

def rule_pending_intents():
    text = strip_comments(read("widget", "NewsWidgetProvider.kt"))

    body = body_of(text, "articleTemplate")
    check("widget: articleTemplate exists", body is not None)
    if body:
        check("widget: the row template is FLAG_MUTABLE", mentions(body, "FLAG_MUTABLE"))
        check("widget: the row template is not immutable", not mentions(body, "FLAG_IMMUTABLE"))

    for name in ("configIntent", "refreshIntent", "openAppIntent"):
        body = body_of(text, name)
        check("widget: %s exists" % name, body is not None)
        if body:
            check("widget: %s is FLAG_IMMUTABLE" % name, mentions(body, "FLAG_IMMUTABLE"))
            check("widget: %s is not mutable" % name, not mentions(body, "FLAG_MUTABLE"))

    # Two widgets sharing one adapter intent is the other classic: the extras
    # are not part of an Intent's identity for setRemoteAdapter, so without a
    # distinct data URI both widgets show the first one's stream.
    check(
        "widget: the adapter intent carries a per-widget data URI",
        "setRemoteAdapter" in text and "toUri(Intent.URI_INTENT_SCHEME)" in text,
    )


# ---------------------------------------------------------------------------
# 3. The widget's layouts.
#
# RemoteViews inflates in the launcher's process and supports a fixed list of
# view types. Anything else fails there and nowhere else — the widget simply
# comes out blank.
# ---------------------------------------------------------------------------

REMOTE_VIEWS_OK = {
    "FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout",
    "AnalogClock", "Button", "Chronometer", "ImageButton", "ImageView",
    "ProgressBar", "TextView", "ViewFlipper", "ListView", "GridView",
    "StackView", "AdapterViewFlipper", "TextClock", "View", "Space",
}


def rule_widget_layouts():
    for name in ("widget_news.xml", "widget_row.xml"):
        path = os.path.join(RES, "layout", name)
        root = ET.parse(path).getroot()
        for element in root.iter():
            tag = element.tag.split("}")[-1]
            check(
                "widget layout %s uses only RemoteViews types" % name,
                tag in REMOTE_VIEWS_OK,
                "<%s> is not one of them" % tag,
            )
        text = open(path, encoding="utf-8").read()
        # ?attr/ resolves against the launcher's theme, not this app's.
        check(
            "widget layout %s uses no theme attributes" % name,
            "?attr/" not in text and "?android:attr/" not in text,
        )


# ---------------------------------------------------------------------------
# 4. The bundled feed set agrees with the code.
# ---------------------------------------------------------------------------

def rule_bundled_feeds():
    models = read("data", "Models.kt")
    order = re.search(r"val ORDER: List<String> = listOf\((.*?)\)", models, re.S)
    check("Categories.ORDER exists", order is not None)

    constants = dict(re.findall(r'const val (\w+) = "([^"]+)"', models))
    names = []
    if order:
        for token in re.findall(r"\b([A-Z_]+)\b", order.group(1)):
            if token in constants:
                names.append(constants[token])

    opml = os.path.join(MAIN, "assets", "default_feeds.opml")
    check("the bundled OPML exists", os.path.exists(opml))
    if not os.path.exists(opml):
        return

    root = ET.parse(opml).getroot()
    body = root.find("body")
    groups = [(g.get("title"), list(g)) for g in body]
    group_names = [name for name, _ in groups]

    check(
        "every OPML category is named in Categories.kt",
        set(group_names) == set(names),
        "opml=%s kotlin=%s" % (sorted(group_names), sorted(names)),
    )

    urls = [o.get("xmlUrl") for _, feeds in groups for o in feeds]
    check("the bundled set is 100 feeds", len(urls) == 100, "found %d" % len(urls))
    check("no bundled feed is listed twice", len(urls) == len(set(urls)))
    for url in urls:
        check("every bundled feed is https", url.startswith("https://"), url)

    # The per-category counts the app's own description promises.
    wanted = {
        "World": (10, 20), "Politics & Geopolitics": (10, 20), "Business": (10, 20),
        "Science & Technology": (5, 10), "Health & Wellness": (5, 10),
        "Sports": (5, 10), "Entertainment & Pop Culture": (5, 10),
    }
    for name, feeds in groups:
        low, high = wanted.get(name, (1, 100))
        check(
            "category %s has between %d and %d feeds" % (name, low, high),
            low <= len(feeds) <= high,
            "has %d" % len(feeds),
        )


# ---------------------------------------------------------------------------
# 5. Suite-wide prohibitions.
# ---------------------------------------------------------------------------

BANNED_DEPENDENCIES = [
    "com.google.android.gms", "com.google.firebase", "play-services",
    "com.google.android.play", "crashlytics", "analytics",
]


def rule_no_google():
    gradle = strip_comments(
        open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    )
    for banned in BANNED_DEPENDENCIES:
        check("no %s dependency" % banned, banned not in gradle)

    for dirpath, _, names in os.walk(JAVA):
        for name in names:
            if not name.endswith(".kt"):
                continue
            text = strip_comments(open(os.path.join(dirpath, name), encoding="utf-8").read())
            rel = os.path.relpath(os.path.join(dirpath, name), ROOT)
            check("%s has no JavascriptInterface" % rel, "@JavascriptInterface" not in text)
            check("%s creates no WebView" % rel, "WebView(" not in text)
            check("%s imports no Play services" % rel, "com.google.android.gms" not in text)


# ---------------------------------------------------------------------------
# 6. The frame rule's prerequisite: uiMode in configChanges.
# ---------------------------------------------------------------------------

def rule_config_changes():
    manifest = ET.parse(os.path.join(MAIN, "AndroidManifest.xml"))
    app = manifest.getroot().find("application")
    for activity in app.findall("activity"):
        name = activity.get(ANDROID + "name")
        changes = activity.get(ANDROID + "configChanges", "")
        check(
            "%s declares uiMode in configChanges" % name,
            "uiMode" in changes,
            "configChanges=%r" % changes,
        )
        check(
            "%s declares fontScale in configChanges" % name,
            "fontScale" in changes,
        )


# ---------------------------------------------------------------------------
# 7. Retention must never be able to delete a saved story.
# ---------------------------------------------------------------------------

def rule_retention_protects_saved():
    repo = read("data", "Repo.kt")
    body = body_of(repo, "purge")
    check("Repo.purge exists", body is not None)
    if body:
        check("purge never deletes a saved story", "saved = 0" in body)
        check("purge keeps a floor of recent items per feed", "LIMIT ?" in body)

    body = body_of(repo, "upsertArticles")
    check("upsertArticles exists", body is not None)
    if body:
        # A refresh must not resurrect read state or move an article to the top
        # of the list because the publisher re-stamped it.
        check("a refresh does not overwrite published_at", 'remove("published_at")' in body)
        check("a refresh writes no read/saved column", '"read"' not in body and '"saved"' not in body)


# ---------------------------------------------------------------------------
# 8. Stream membership has exactly one definition.
#
# A stream shows a feed when the feed was picked *or* when its category was.
# Four separate queries answer "what is in this stream" — the article list, the
# unread count, mark-all-read and the feed list — and if one of them forgets
# the category half, a stream built from categories looks empty in the widget
# and full in the app. They all go through Db.streamMember.
# ---------------------------------------------------------------------------

MEMBERSHIP_USERS = ["articles", "unreadCount", "markStreamRead", "feedsIn", "feedCountIn"]


def rule_membership():
    repo = strip_comments(read("data", "Repo.kt"))
    db = strip_comments(read("data", "Db.kt"))

    check("Db.streamMember exists", "fun streamMember(" in db)
    template = body_of(db, "streamMember")
    check(
        "the membership fragment unions picked feeds and picked categories",
        "stream_feeds" in db and "stream_categories" in db and "STREAM_MEMBER_TEMPLATE" in db,
    )

    for name in MEMBERSHIP_USERS:
        body = body_of(repo, name)
        check("Repo.%s exists" % name, body is not None)
        if not body:
            continue
        check(
            "Repo.%s asks membership through Db.streamMember" % name,
            "streamMember(" in body,
        )
        # The failure this rule exists to prevent: a query that filters on
        # stream_feeds by hand and so cannot see a stream's categories.
        check(
            "Repo.%s does not hand-roll a stream_feeds filter" % name,
            "stream_feeds" not in body,
            "found a literal stream_feeds subquery",
        )

    # The template takes the stream id twice, so every caller binds it twice.
    for name in MEMBERSHIP_USERS:
        body = body_of(repo, name) or ""
        if "streamMember(" not in body:
            continue
        check(
            "Repo.%s binds the stream id twice for the membership fragment" % name,
            body.count("streamId") >= 3 or body.count("id.toString()") >= 2,
            "the fragment has two ? placeholders",
        )


# ---------------------------------------------------------------------------
# 9. Refresh cadence.
#
# A stream can ask for its own refresh interval. A feed in several streams is
# refreshed on the shortest of them, the periodic worker runs at that shortest
# cadence, and each feed is skipped until its own is due. Two things must hold
# or a fast stream drags every feed with it:
#
#   * 0 means "use the Settings interval" and must never win a MIN(), and
#   * the worker's own period can never go below WorkManager's floor, which
#     would be silently rounded up and make the schedule a lie.
# ---------------------------------------------------------------------------

def rule_cadence():
    repo = strip_comments(read("data", "Repo.kt"))
    sync = strip_comments(read("feed", "Sync.kt"))
    worker = strip_comments(read("feed", "SyncWorker.kt"))
    models = strip_comments(read("data", "Models.kt"))

    body = body_of(repo, "feedsWithCadence")
    check("Repo.feedsWithCadence exists", body is not None)
    if body:
        check(
            "the shortest cadence ignores the 0 that means 'use Settings'",
            "NULLIF(" in body,
            "MIN() over a column containing 0 always returns 0",
        )
        check("it takes the shortest, not the longest", "MIN(" in body)
        check(
            "it reaches streams through both kinds of membership",
            "stream_feeds" in body and "stream_categories" in body,
        )

    check("Stream.REFRESH_FLOOR_MINUTES is WorkManager's floor", "REFRESH_FLOOR_MINUTES = 15" in models)

    body = body_of(sync, "shortestCadence")
    check("Sync.shortestCadence exists", body is not None)
    if body:
        check(
            "the worker's period never drops below the platform floor",
            "REFRESH_FLOOR_MINUTES" in body and "maxOf(" in body,
        )

    body = body_of(sync, "due")
    check("Sync.due exists", body is not None)
    if body:
        check("a feed is skipped until its own cadence is due", "lastFetchAt" in body)

    check(
        "the periodic worker does not force a refresh of every feed",
        "force = false" in worker,
        "a forced refresh ignores per-feed cadence",
    )
    check(
        "SyncScheduler reschedules from the shortest cadence",
        "shortestCadence(" in worker,
    )


# ---------------------------------------------------------------------------
# 10. The bundled hundred are a library, not seven ready-made streams.
#
# This is the shape the reader asked for: categories are filing labels that
# make a hundred feeds findable while they build the streams they want. A first
# run that invents streams takes that decision away from them.
# ---------------------------------------------------------------------------

def rule_library_not_streams():
    seed = strip_comments(read("data", "Seed.kt"))

    body = body_of(seed, "ifNeeded")
    check("Seed.ifNeeded exists", body is not None)
    if body:
        check(
            "a first run creates no streams",
            "makeStreams = false" in body,
        )
    body = body_of(seed, "install")
    check("Seed.install exists", body is not None)
    if body:
        check("install always files the categories", "createCategory" in body or "categor" in body.lower())
        check(
            "streams are only made when the caller asks (OPML import)",
            "makeStreams" in body,
        )

    # The library screen has to be able to find one feed among a hundred.
    feeds_screen = strip_comments(read("ui", "FeedsActivity.kt"))
    check("the library has a search field", "filterSearch" in feeds_screen)
    check("the library filters by category", "filterCategory" in feeds_screen)

    # And so does the stream editor, which is where feeds are actually chosen.
    editor = strip_comments(read("ui", "StreamEditActivity.kt"))
    check("the stream editor searches feeds", "feeds(" in editor)
    check("the stream editor can add whole categories", "setStreamCategories(" in editor)
    check("the stream editor sets a cadence", "refreshMinutes" in editor)
    check("saving a stream reschedules the worker", "SyncScheduler" in editor)


# ---------------------------------------------------------------------------
# 11. Getting in and out of every screen.
#
# The reader's first complaint was navigation. Every screen that is not the
# list itself needs a back affordance wired to finish(), and the two things
# they could not find — add a feed, make a stream — have to be reachable from
# the drawer without knowing where else to look.
# ---------------------------------------------------------------------------

SCREENS = [
    "ArticleActivity", "AddFeedActivity", "StreamEditActivity", "CategoriesActivity",
    "FeedsActivity", "StreamsActivity",
]


def rule_navigation():
    for name in SCREENS:
        text = strip_comments(read("ui", name + ".kt"))
        check(
            "%s has a back button that finishes" % name,
            "R.id.backButton" in text and "finish()" in text,
        )

    main = strip_comments(read("ui", "MainActivity.kt"))
    check("the list has a drawer button", "R.id.menuButton" in main)
    check("the list has a back arrow", "R.id.backButton" in main)
    check(
        "back closes the drawer before it leaves the app",
        "OnBackPressedCallback" in main and "closeDrawer" in main,
    )

    drawer = strip_comments(read("ui", "DrawerAdapter.kt"))
    for action in ["ACTION_NEW_STREAM", "ACTION_ADD_FEED", "ACTION_LIBRARY",
                   "ACTION_CATEGORIES", "ACTION_SETTINGS"]:
        check("the drawer offers %s" % action, action in drawer)
        check("MainActivity handles %s" % action, action in main)


# ---------------------------------------------------------------------------
# 12. No stray control characters in source.
#
# A raw NUL byte reached Repo.kt once, where "\u0000uncategorised" was meant:
# it reads identically in every tool that shows text, and it is a compile
# error. Tabs and newlines only.
# ---------------------------------------------------------------------------

def rule_no_control_characters():
    bad = []
    for root in (JAVA, TEST_JAVA):
        if not os.path.isdir(root):
            continue
        for dirpath, _, names in os.walk(root):
            for name in names:
                if not name.endswith(".kt"):
                    continue
                path = os.path.join(dirpath, name)
                data = open(path, "rb").read()
                for byte in data:
                    value = byte if isinstance(byte, int) else ord(byte)
                    if value < 0x20 and value not in (0x09, 0x0A, 0x0D):
                        bad.append("%s (0x%02x)" % (os.path.relpath(path, ROOT), value))
                        break
    check("no source file carries a stray control character", not bad, ", ".join(bad))



def main():
    rule_pure_core()
    rule_pending_intents()
    rule_widget_layouts()
    rule_bundled_feeds()
    rule_no_google()
    rule_config_changes()
    rule_retention_protects_saved()
    rule_membership()
    rule_cadence()
    rule_library_not_streams()
    rule_navigation()
    rule_no_control_characters()

    print("%d invariants checked" % CHECKS[0])
    if FAILURES:
        for failure in FAILURES:
            print("FAIL  %s" % failure)
        print("\n%d failures" % len(FAILURES))
        return 1
    print("clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
