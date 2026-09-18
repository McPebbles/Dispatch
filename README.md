# Dispatch

A news reader for GrapheneOS. RSS and Atom, a hundred feeds out of the box,
streams you compose yourself, a 4×4 home-screen widget, and no Google anything.

```
package     com.dispatch.reader
minSdk 30 · targetSdk 36 · AGP 8.11.1 · Kotlin 2.1.21 · Gradle 8.14.3
version     1.1.0 (versionCode 3)
```

Sixth app in the suite, after TastyWrap, SupplyChain, Tombot, Spoticap and
Threadbare — and the first one that is not a WebView wrapper. There is no
WebView in this app at all.

---

## What it does

**Feeds.** Add one by pasting a feed address, or just the site: the app reads
the page's own `<link rel="alternate">` advertisement, and falls back to the
handful of paths the common publishing systems use. RSS 2.0, RSS 1.0 (RDF) and
Atom all parse through one code path. Import and export OPML, so you can arrive
from another reader and leave for one.

**A hundred feeds already in it — as a library, not as a verdict.** They
arrive filed under seven categories (World, Politics & Geopolitics, Business,
Science & Technology, Health & Wellness, Sports, Entertainment & Pop Culture)
and nothing else happens: no streams are invented for you. The categories are
filing labels that make a hundred feeds findable while you build the streams
you actually want, and they are yours to rename, delete or add to. Every URL in
the bundled set returned a parseable feed when it was assembled;
`tools/check_feeds.py` re-checks them all.

**Streams.** A stream is a named view over that library, and it holds two kinds
of membership: feeds you picked one by one, **and** whole categories, which
keep working — a feed tagged Sports next month joins every stream that asked
for Sports without you going back to tick it. Each stream can set its own
refresh cadence; a feed in several streams is refreshed on the shortest of
them. Build one from the drawer, search the library by name or address, filter
it by category, and name it what you like. "All feeds" and "Saved stories" are
queries rather than rows, so they cannot be renamed, emptied or deleted.

**Adding a feed.** The `+` in the library, or **Add feed** in the drawer: paste
an address, tap Find feed, give it a title and a category (including one you
invent on the spot). Sharing a link to Dispatch from any browser opens the same
screen.

**Getting around.** A drawer on the left lists All feeds, Saved stories and
every stream with its unread count, and carries the five things you do rather
than read: new stream, add feed, the library, categories, settings. A back
arrow appears in the top bar whenever there is somewhere to go back to — out of
a stream, out of a search — and the system back gesture does exactly what it
does.

**The widget.** A 4×4 tile showing up to ten stories from one stream, with a
thumbnail, the headline, and — if you turn it on with the gear — a byline that
trails into an ellipsis. No feed can take more than three of the ten slots, so
a wire service publishing forty times an hour cannot crowd out everything else.
The same story arriving from several feeds appears once. The gear picks the
stream, makes a new one, or turns the byline on and off.

**Reading.** A story shows what the feed gave: headline, byline, date, the
publisher's picture, and the text from `content:encoded`, `<summary>` or
`<description>`. Then a button hands the full article to the browser you chose.
There is no in-app browser, so there is no surface for a publisher's scripts,
cookies or consent walls — and the app makes no request the feed did not
advertise.

**Saved stories.** Bookmark anything; saved stories are never removed by the
clean-up, at any age.

**The browser picker.** Ported from Threadbare. Article links go to the browser
you pick for this app, which need not be your system default — Vanadium for
everyday use, Firefox Focus for a hundred publishers' links, say. If you ask
for a private tab and no installed browser will honour one, the app says so
rather than opening an ordinary tab and letting you believe otherwise.

**Also:** search across everything cached, unread counts and an unread-only
view, mark-all-read, per-feed health (which feeds are failing, and why), text
size, light/dark/system theme, retention with a per-feed floor, and a
"what this app talks to" note in Settings that is accurate.

## What it does not do

No account, no sync service, no analytics, no crash reporting, no Play
services, no Firebase, no advertising ID — and **no notifications**. Push on
Android means Firebase Cloud Messaging, which means Google Play services, which
is the thing this suite exists to avoid. Dispatch polls on a schedule you set,
through the platform's own job scheduler, and says nothing until you open it.

## Building

```sh
./gradlew assembleDebug          # or gradlew.bat on Windows
./gradlew testDebugUnitTest
```

A release build signs itself from `keystore.properties` in the repo root or
from `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` in the
environment; with neither it still builds, unsigned.

## Checking

```sh
bash tools/verify_all.sh         # no Android SDK needed
./gradlew testDebugUnitTest      # the JVM suite
python3 tools/check_feeds.py     # the bundled feeds, against the live web
```

`tools/verify_all.sh` runs:

| tool | what it asserts |
|---|---|
| `verify_kotlin.py` | structure, package/directory agreement, modifier pairings |
| `verify_resources.py` | every `R.*` resolves; preference keys and defaults agree with `Prefs.kt`; a runtime-populated `ListPreference` is really populated |
| `verify_frame.py` | all four window-frame requirements, for **every** activity |
| `sql_suite.py` | the app's own SQL, lifted out of `Db.kt`/`Repo.kt` and run against SQLite: retention never deletes a saved story, the per-feed floor is really per feed, a re-fetch does not move `published_at` or reset read/saved, and the cascades go the right way |
| `rules_suite.py` | 292 invariants: the pure core stays free of `android.*`, the widget's PendingIntent mutability, RemoteViews-only widget layouts, the bundled set against `Categories.kt`, no Play services anywhere, retention cannot delete a saved story |
| `render_icon.py` | renders the adaptive icon under the launcher masks |

The JVM suite covers the feed parser against real markup from BBC, The
Guardian, NPR, The Verge, Nature and CBS Sports; date parsing against the exact
strings those feeds emit; OPML against the file that ships; and the widget's
selection rule.

## Layout

```
app/src/main/java/com/dispatch/reader/
  feed/      Http, FeedParser, Sanitize, Dates, Discovery, Sync, SyncWorker
  data/      Db, Repo, Models, Opml, Seed
  img/       ImageStore
  ui/        MainActivity, ArticleActivity, FeedsActivity, StreamsActivity, Settings…
  widget/    NewsWidgetProvider, WidgetService, WidgetMix, WidgetConfigActivity
  web/       BrowserChoice, BrowserLauncher, PrivateTabs, ExternalLinks
  shell/     Frame
```

`DESIGN.md` is the longer version: why each of those files exists and what it
cost to get right.
