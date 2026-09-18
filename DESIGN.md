# Dispatch — the decisions, and what they cost

This is the file to read before changing anything. Everything here is either a
choice that has a cheaper-looking alternative, or a fact about the live web that
was measured rather than assumed.

---

## 1. The hundred feeds are real, and that took the whole afternoon

The bundled set was not written from memory. Every URL was fetched through a
browser on 17 September 2026 and had to answer with a document that parsed as a
feed. What that found:

| what happened | who |
|---|---|
| the feed host no longer resolves | CNN's `rss.cnn.com` |
| the feed 404s | Medical News Today `/rss` (JSON 404), Harvard Health `/blog/feed` (redirects to a web page), Lawfare `/feeds/all.rss` |
| the URL is a listing page, not a feed | France 24 `/en/rss` |
| the feed redirects to a paywall for machines | Variety, The Hollywood Reporter, Deadline, Rolling Stone — all four Penske titles now send `/feed/` to `tollbit.<domain>/feed/`, which answers `"You are not authorized … without a valid TollBit token"` |
| the publisher answers 403 to a datacentre IP | CBC (Akamai), The Washington Post (Akamai) |
| Cloudflare interstitial | Foreign Affairs, Entertainment Weekly, The Hindu |
| the feed returns an empty `text/html` body | ESPN (`/espn/rss/news` — 202 with nothing in it), SB Nation |
| the feed works but is served as `text/plain` | Japan Times, ABC News AU, The Hill, TechCrunch, STAT, WSJ, Business Insider, AV Club, Sportsnet… |
| the feed works but is served as `text/html` | Sky Sports |
| the URL redirects to the real feed | IGN → `www.ign.com/rss/articles/feed`, Fortune → `/feed/fortune-feeds/?id=…`, FT → `/rss/home/international`, Business Insider → `feeds.businessinsider.com/custom/all`, Polygon → `/feed/`, AV Club → `/rss.xml`, New Scientist → `/feed/` |

Three rules fall straight out of that table and are in the code:

1. **Never gate on `Content-Type`.** A third of these feeds are served as
   something other than XML. `FeedParser` decides by parsing.
2. **Follow redirects by hand.** `HttpURLConnection` refuses to follow a
   redirect that changes protocol, and six of the bundled feeds redirect.
   Left to the platform they would simply look empty.
3. **Show the reader which feeds are failing.** A dead feed is otherwise
   indistinguishable from a quiet one, and the symptom is a section that slowly
   stops updating. Manage feeds carries a status line per feed, and `Sync`
   turns each failure into a sentence a person can act on — 403 and 404 do not
   read the same.

CBC and the Washington Post were dropped rather than shipped as feeds that
403: the block may well be about the IP the check ran from rather than about
the app, but shipping a default that cannot be verified is the thing this
project has a standing lesson about. Canadian coverage is Global News, National
Post, The Globe and Mail and Financial Post instead.

## 2. The parser was written against markup, not against a specification

`app/src/test/resources/fixtures/` holds trimmed copies of six real feeds. Each
one forced a rule:

| fixture | what it taught |
|---|---|
| `bbc_rss2.xml` | `media:thumbnail`; a channel-level `<image>` that contains its own `<title>` and `<link>` and will overwrite the feed's if nothing tracks it; no author at all, anywhere |
| `guardian_rss2.xml` | several `<media:content>` per item at different widths — pick the widest; body as escaped HTML in `<description>` |
| `npr_rss2.xml` | the only picture is an `<img>` inside `content:encoded`, and the *last* `<img>` is a 1×1 analytics beacon. Choosing it would put a tracking request on the home screen for every NPR story |
| `verge_atom.xml` | Atom: `<link rel="alternate">` is the article, `<id>` is not a link, `<author><name>`, ISO-8601 dates |
| `nature_rdf.xml` | RSS 1.0: `<item>` is a **sibling** of `<channel>`, and the channel holds an `<items><rdf:Seq>` table of contents that is not content; two `<dc:creator>` elements for two authors; a bare `<dc:date>` |
| `cbssports_rss2.xml` | every element's text padded with newlines and indentation; `<guid isPermaLink="false">` holding a UUID that must never become the link |

The seventh, `broken_entities.xml`, is synthetic and says so: it exercises the
repair pass all at once.

### The repair pass

XML defines five named entities. HTML defines hundreds, and publishers' feeds
are full of them. A conforming XML parser is *required* to fail on
`&nbsp;` — so a strict parse of a real feed throws on markup every browser
renders. `Sanitize.xmlSafe` rewrites known HTML entities to numeric references,
escapes everything else, drops anything before the first `<`, and removes the
control characters XML forbids — **without touching CDATA**, where `&nbsp;` is
literally six characters and rewriting it would corrupt the body. BBC, NPR and
The Verge all wrap their bodies in CDATA.

The alternative was a lenient parser (Android's KXmlParser has a relaxed mode),
and it was rejected because the JVM has no such parser: the tests would then
exercise a different implementation from the app. This project has a standing
lesson about exactly that.

### Dates

The obvious implementation is `DateTimeFormatter.RFC_1123_DATE_TIME`. Run
against the shapes these feeds emit, it rejects three of them:

```
Mon, 01 Sep 2026 09:00:00 EST     obsolete zone names other than GMT
Mon, 1 Sep 2026 09:00:00 GMT      a day without a leading zero
Fri, 16 Sep 2026 20:46:30 GMT     day-of-week disagreeing with the date
```

All three are legal RFC-822 or a publisher bug a reader should not be punished
for, and a rejected date is invisible: the item silently gets "now" and sorts
wrongly. So the RFC-822 path is a small tokeniser. **It was prototyped in Java
and run against all fifteen cases in `DatesTest` before the Kotlin was
written** — which is how the RFC_1123 failures were discovered rather than
guessed at.

## 3. The widget

### The mixing rule, and the case it gets wrong

At most three articles per feed, so a wire service cannot own the widget. But a
strict cap fails on a stream with two feeds: three plus three is six stories in
a space built for ten, with four empty rows, forever. The cap exists to mix
sources, and with two sources there is nothing to mix.

So the cap is applied in **tiers**: every feed's newest three are tier 0, its
next three tier 1, and the widget fills from tier 0 first. With many feeds it
never leaves tier 0 and behaves exactly like a strict cap; with two it keeps
going and the widget is full. `WidgetMix.pick(..., fill = false)` turns the
relaxation off in one place if that judgement is ever wrong.

Selection is per feed; **display is chronological**. A reader glancing at a
widget expects the top row to be the newest thing in it, which is not the order
the tiers produce.

Wire stories arriving from several feeds are deduplicated — by link, after
stripping tracking parameters (BBC appends `?at_medium=RSS&at_campaign=rss` to
every link in its feed, so one article has as many URLs as it has feeds), and
by headline, but only for headlines long enough not to collide by accident.

### The platform traps

- **Two PendingIntents, opposite flags.** The header buttons must be
  `FLAG_IMMUTABLE`, which API 31+ requires. The row template passed to
  `setPendingIntentTemplate` must be `FLAG_MUTABLE`, because the launcher works
  by filling in each row's extras. Marked immutable it does not throw — every
  row just opens the same article. `rules_suite.py` asserts both.
- **The adapter intent needs a per-widget data URI.** Extras are not part of an
  Intent's identity for `setRemoteAdapter`, so two widgets otherwise share one
  factory and both show the first one's stream.
- **RemoteViews inflates in the launcher's process.** Only a fixed list of view
  types works, and `?attr/` resolves against the launcher's theme, not this
  app's. Both are checked.
- **Bitmaps cross a binder transaction.** Ten full-resolution pictures do not
  fit in it, and the widget comes back blank with an obscure log line. Row
  thumbnails are decoded to 168px.
- **`updatePeriodMillis` is 0.** The platform floor is 30 minutes and it wakes
  the device. The refresh is WorkManager's, on the reader's schedule; the
  widget is told to re-read when it finishes.
- **The gear exists because Android's own reconfiguration entry point is not
  discoverable** from the home screen. `configuration_optional` means a freshly
  placed widget works immediately rather than forcing a setup screen on someone
  who just dragged it out of the picker.

## 4. Things deliberately not used

| not used | why |
|---|---|
| Room | an annotation processor, and this app is written where it cannot be compiled. A codegen failure at build time is the one class of failure that cannot be diagnosed from here. `data/Db.kt` is a `SQLiteOpenHelper` and its SQL fails with a message naming the column |
| OkHttp / Retrofit | what a reader needs from HTTP is a GET, a conditional GET, a redirect chain and a size cap |
| Coil / Glide | `img/ImageStore.kt` is a memory cache, a disk cache and a downsampling decode. Reusing `Http` means images are fetched with the same no-cookie, https-only client as feeds |
| Jetpack Compose | the suite's window-frame rule and its audit are written for Views, and this app has six activities to keep in line |
| a foreground service | never needed: no expedited work, nothing to show a notification for. WorkManager's own `SystemForegroundService` and its two `FOREGROUND_SERVICE*` permissions are removed in the manifest, so the installed app's permission list stays true |
| notifications | they would mean push, which means Firebase, which means Play services |

## 4a. The crash 1.0.0 shipped with

`MainActivity` wears `Theme.Dispatch.Splash`, whose parent is
`Theme.SplashScreen` — **not an AppCompat theme**. `installSplashScreen()` is
what moves the activity onto `postSplashScreenTheme`; without it the activity
keeps the splash theme and `AppCompatDelegate` throws

```
java.lang.IllegalStateException: You need to use a Theme.AppCompat theme
(or descendant) with this activity.
    at AppCompatDelegateImpl.createSubDecor
    at MainActivity.onCreate(MainActivity.kt:65)   <- setContentView
```

on every launch. The theme was ported from Threadbare; the one line that makes
it legal was not, because `MainActivity` was written from scratch rather than
adapted. **A theme is not portable on its own** — it carries an obligation on
the activity that wears it.

`tools/verify_frame.py` now follows `postSplashScreenTheme` (which it already
resolved, for the theme checks) and refuses an activity that wears a splash
theme without calling `installSplashScreen()` before `super.onCreate()`. It is
negative-tested three ways: call deleted, call moved after `setContentView`,
and — the one that matters — call deleted while the *comment explaining it*
stays. That last case is how the first version of this check passed on broken
code: it searched the file for a name, and the name was in a comment. The
checker now strips comments first, the same fix `rules_suite.py` needed.

## 5. The window frame

`shell/Frame.kt` is Tombot's, by way of Threadbare, and the standing rule is
`claude/frame-rule.md` in the project. At targetSdk 36 edge-to-edge is not
optional; an activity that does not paint the status-bar strip and inset its own
content draws underneath it.

The failure that produced the rule was **one screen right and one screen
wrong** — Tombot's settings screen was left on a stock ActionBar theme and the
review that "fixed the frame" never opened it. Dispatch has six activities,
which makes it the app in the suite most exposed to that, so every one of them
uses the same `Frame` and `tools/verify_frame.py` walks the manifest and fails
if any does not.

## 6. Pull-to-refresh is safe here, and the reason matters

The suite has a rule about `SwipeRefreshLayout` (`claude/pull-to-refresh-rule.md`)
written after it ate scrolling in two apps. That rule is about a **WebView**:
the refresh layout asks the child "can you scroll up?", and a WebView showing an
app-shell site answers 0 forever.

A `RecyclerView` answers correctly, from its own layout manager, synchronously.
So pull-to-refresh works here — not by luck, but because the condition the rule
is about is absent. It is written down in `MainActivity` so that nobody ports
the WebView workaround into a screen that does not need it.

## 7. What has *not* been verified

Stated plainly, because the suite's worst rounds have all started with an
unverified premise presented as a caveat:

- **Nothing here has been compiled.** No Kotlin compiler and no Android SDK are
  reachable from the environment this was written in (Maven Central,
  `maven.google.com` and `services.gradle.org` are all blocked). The first real
  check is `./gradlew assembleDebug`.
- **The SQL has been executed** — `tools/sql_suite.py` runs the schema and the
  retention statement out of the Kotlin against real SQLite, which is how the
  correlated `LIMIT` subquery was confirmed rather than hoped for. Everything
  else in the data layer has not.
- **The JUnit suite has never been executed.** It is written against the real
  fixtures and the assertions were computed by hand or prototyped in Java, but
  `./gradlew testDebugUnitTest` is the first time it runs.
- **Nothing has run on a device.** In particular: the widget's binder
  transaction size with ten thumbnails, whether the launcher honours
  `configuration_optional`, whether the gear's PendingIntent survives a
  launcher restart, and how a hundred feeds behave on a phone radio.
- **The image loader's threading** is reasoned about, not observed.
- `tools/check_feeds.py` has never been run end to end — the environment that
  wrote it could not reach the feeds. Its loader was exercised; its network
  path was not.
