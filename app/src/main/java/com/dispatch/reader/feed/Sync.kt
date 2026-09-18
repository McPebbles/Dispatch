package com.dispatch.reader.feed

import android.content.Context
import com.dispatch.reader.data.Feed
import com.dispatch.reader.data.Repo
import com.dispatch.reader.data.Stream
import com.dispatch.reader.util.Prefs
import com.dispatch.reader.util.Safely
import com.dispatch.reader.widget.NewsWidgetProvider
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One refresh: fetch every feed, parse it, write what is new, tidy up.
 *
 * Runs on a small pool rather than one at a time (a hundred feeds in series at
 * a second each is an unusable refresh) and rather than all at once (a hundred
 * simultaneous TLS handshakes on a phone radio is worse than useless). Four is
 * a deliberate compromise and is the only concurrency in the app.
 */
object Sync {

    private const val THREADS = 4
    private const val OVERALL_TIMEOUT_MINUTES = 5L

    data class Summary(
        val feedsTried: Int,
        val feedsFailed: Int,
        val newArticles: Int,
        val notModified: Int,
        /** Feeds whose cadence said they were not due yet. */
        val feedsSkipped: Int = 0,
    ) {
        val ok: Boolean get() = feedsFailed == 0
    }

    /** One feed's outcome, so the caller can report a single failed add precisely. */
    data class One(val newArticles: Int, val error: String?, val notModified: Boolean)

    /**
     * @param force fetch every feed regardless of cadence. Pull-to-refresh and
     *   the Settings button pass true: the reader asked *now*, and telling them
     *   "not due yet" would be answering a question they did not ask. The
     *   background worker passes false, which is what makes a per-stream
     *   cadence mean anything.
     */
    fun refreshAll(
        context: Context,
        repo: Repo,
        force: Boolean = true,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Summary {
        val defaultMinutes = Prefs.syncIntervalMinutes(context)
        val now = System.currentTimeMillis()
        val all = repo.feedsWithCadence()
        val feeds = if (force) all.map { it.feed } else all.filter { due(it, defaultMinutes, now) }.map { it.feed }
        val skipped = all.size - feeds.size
        if (feeds.isEmpty()) return Summary(0, 0, 0, 0, skipped)

        val pool = Executors.newFixedThreadPool(THREADS)
        var failed = 0
        var fresh = 0
        var notModified = 0
        var done = 0
        try {
            val tasks = feeds.map { feed ->
                Callable {
                    val result = refreshOne(repo, feed)
                    synchronized(this) {
                        done++
                        if (result.error != null) failed++
                        if (result.notModified) notModified++
                        fresh += result.newArticles
                        onProgress?.invoke(done, feeds.size)
                    }
                    result
                }
            }
            pool.invokeAll(tasks, OVERALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            pool.shutdownNow()
        }

        Safely.run {
            val keepDays = Prefs.retentionDays(context)
            if (keepDays > 0) repo.purge(keepDays)
        }
        Prefs.setLastSync(context, System.currentTimeMillis())
        // The widget reads the database directly, so it only needs telling that
        // there is something new to read.
        Safely.run { NewsWidgetProvider.refreshAll(context) }

        return Summary(feeds.size, failed, fresh, notModified, skipped)
    }

    /**
     * Is this feed due?
     *
     * The cadence comes from the shortest-cadence stream the feed belongs to;
     * zero means the stream said "use the default", so the app-wide interval
     * from Settings applies. A default of zero — "only when I ask" — means a
     * background run has nothing to do, which is exactly right: the scheduler
     * will not even be enqueued.
     *
     * The 60-second slack stops a feed from being skipped because the job fired
     * a heartbeat early, which would otherwise push every refresh out by a whole
     * cadence.
     */
    fun due(entry: Repo.Due, defaultMinutes: Int, now: Long): Boolean {
        val minutes = if (entry.cadenceMinutes > 0) entry.cadenceMinutes else defaultMinutes
        if (minutes <= 0) return false
        if (entry.feed.lastFetchAt <= 0L) return true
        return now - entry.feed.lastFetchAt >= minutes * 60_000L - 60_000L
    }

    /**
     * How often the background job has to run to honour every stream.
     *
     * The shortest cadence anyone asked for, floored at WorkManager's 15-minute
     * minimum. Returns 0 when nothing wants a background refresh at all, and
     * the scheduler then cancels the job rather than keeping a registered job
     * that does nothing.
     */
    fun shortestCadence(repo: Repo, defaultMinutes: Int): Int {
        val streamCadences = repo.streams().map { it.refreshMinutes }.filter { it > 0 }
        val candidates = streamCadences + listOfNotNull(defaultMinutes.takeIf { it > 0 })
        val shortest = candidates.minOrNull() ?: return 0
        return maxOf(shortest, Stream.REFRESH_FLOOR_MINUTES)
    }

    /**
     * Fetch and store one feed.
     *
     * A 304 is a success with nothing to do — the feed has not changed since
     * the validator this app sent — and clears any previous error.
     */
    fun refreshOne(repo: Repo, feed: Feed): One {
        val response = Http.get(feed.url, feed.etag, feed.lastModified)

        if (response.notModified) {
            repo.recordFetch(feed.id, null, feed.lastCount, feed.etag, feed.lastModified, null)
            return One(0, null, notModified = true)
        }
        if (!response.ok) {
            val message = describe(response)
            repo.recordFetch(feed.id, message, 0, feed.etag, feed.lastModified, null)
            return One(0, message, notModified = false)
        }

        val bytes = response.bytes ?: ByteArray(0)
        val parsed = FeedParser.parse(
            bytes,
            contentTypeHeader = response.contentType,
            baseUrl = response.finalUrl ?: feed.url,
        )
        if (parsed.items.isEmpty()) {
            // An empty parse is not always an error: a feed can legitimately be
            // empty. It is only reported as one when the document did not even
            // look like a feed, so that a publisher replacing a dead feed with
            // an HTML holding page shows up in Manage feeds rather than as a
            // section that quietly stops updating.
            val text = String(bytes, Charsets.ISO_8859_1)
            val message = if (Discovery.looksLikeFeed(text)) null else "This address is no longer a feed"
            repo.recordFetch(feed.id, message, 0, response.etag, response.lastModified, parsed)
            return One(0, message, notModified = false)
        }

        val added = repo.upsertArticles(feed.id, parsed.items)
        repo.recordFetch(feed.id, null, parsed.items.size, response.etag, response.lastModified, parsed)
        return One(added, null, notModified = false)
    }

    /** What went wrong, in words a reader can act on. */
    fun describe(response: Http.Result): String = when (response.error) {
        FeedError.HTTP_STATUS -> when (response.status) {
            401, 403 -> "The publisher refused the request (${response.status})"
            404, 410 -> "Not found (${response.status}) — the feed has probably moved"
            429 -> "Rate limited (429) — too many requests"
            in 500..599 -> "The publisher's server had an error (${response.status})"
            else -> response.errorDetail ?: "HTTP ${response.status}"
        }
        FeedError.TOO_LARGE -> "The feed is too large to read"
        FeedError.NETWORK -> response.errorDetail?.let { shortenNetwork(it) } ?: "No connection"
        else -> response.errorDetail ?: "Could not read the feed"
    }

    private fun shortenNetwork(detail: String): String = when {
        detail.startsWith("UnknownHostException") -> "Could not find that server"
        detail.startsWith("SocketTimeoutException") -> "The server did not answer in time"
        detail.startsWith("SSLHandshakeException") -> "The server's certificate was not accepted"
        detail.startsWith("ConnectException") -> "Could not connect"
        else -> detail.substringBefore(':')
    }

    // ------------------------------------------------------------- adding

    /**
     * What an address turned out to be, without saving anything.
     *
     * Adding a feed is two steps on purpose: **find**, then **file**. The
     * reader gets to see what was found, rename it, and choose a category
     * before it lands in their library — which is the whole reason "add feed"
     * is a screen rather than a one-line dialog that guesses.
     */
    sealed class Probe {
        data class Found(
            val url: String,
            val title: String,
            val siteLink: String?,
            val iconUrl: String?,
            val items: List<ParsedItem>,
        ) : Probe()

        /** The address held several feeds; the reader picks. */
        data class Choices(val urls: List<String>) : Probe()

        data class Already(val feedId: Long, val title: String) : Probe()

        data class Failed(val message: String) : Probe()
    }

    /**
     * Work out what the reader pasted: a feed, or a site that has one.
     *
     * The order — fetch, then read the page's own advertisement, then guess —
     * is [Discovery]'s, and the site is only guessed at when it advertised
     * nothing.
     */
    /**
     * @param depth how many advertised links have been followed to get here.
     *   A page whose `<link rel="alternate">` points back at itself — or at a
     *   page that points back here — would otherwise recurse until the stack
     *   runs out, one HTTP request per level. Two hops is more than any real
     *   site needs.
     */
    const val MAX_PROBE_HOPS = 2

    fun probe(repo: Repo, input: String, depth: Int = 0): Probe {
        val url = Sanitize.https(input.trim())
            ?: return Probe.Failed("That does not look like a web address")

        repo.feedByUrl(url)?.let { return Probe.Already(it.id, it.title) }

        val response = Http.get(url)
        if (!response.ok) return Probe.Failed(describe(response))
        val finalUrl = response.finalUrl ?: url
        val body = response.bytes ?: return Probe.Failed("That address returned nothing")
        val text = String(body, FeedParser.charsetOf(body, response.contentType))

        if (Discovery.looksLikeFeed(text)) {
            val parsed = FeedParser.parse(text, finalUrl)
            if (parsed.items.isNotEmpty() || parsed.title != null) {
                repo.feedByUrl(finalUrl)?.let { return Probe.Already(it.id, it.title) }
                return found(finalUrl, parsed)
            }
        }

        val advertised = Discovery.discover(text, finalUrl)
        when {
            advertised.size == 1 && advertised.first() != finalUrl && depth < MAX_PROBE_HOPS ->
                return probe(repo, advertised.first(), depth + 1)
            advertised.size > 1 -> return Probe.Choices(advertised)
        }

        for (guess in Discovery.guessesFor(finalUrl)) {
            if (guess == finalUrl) continue
            val attempt = Http.get(guess)
            if (!attempt.ok) continue
            val guessBytes = attempt.bytes ?: continue
            val guessText = String(guessBytes, FeedParser.charsetOf(guessBytes, attempt.contentType))
            if (!Discovery.looksLikeFeed(guessText)) continue
            val parsed = FeedParser.parse(guessText, attempt.finalUrl ?: guess)
            if (parsed.items.isNotEmpty()) {
                val resolved = attempt.finalUrl ?: guess
                repo.feedByUrl(resolved)?.let { return Probe.Already(it.id, it.title) }
                return found(resolved, parsed)
            }
        }

        return Probe.Failed("No feed found at that address")
    }

    private fun found(url: String, parsed: ParsedFeed): Probe.Found = Probe.Found(
        url = url,
        title = parsed.title?.takeIf { it.isNotBlank() } ?: Repo.hostOf(url),
        siteLink = parsed.siteLink,
        iconUrl = parsed.iconUrl,
        items = parsed.items,
    )

    /**
     * File a probed feed, with the title and category the reader chose.
     *
     * @return the new feed's id, or -1.
     */
    fun save(repo: Repo, found: Probe.Found, title: String, category: String?): Long {
        val name = title.trim().ifEmpty { found.title }
        val id = repo.addFeed(found.url, name, found.siteLink, found.iconUrl, category)
        if (id <= 0) return -1
        repo.upsertArticles(id, found.items)
        repo.recordFetch(id, null, found.items.size, null, null, null)
        return id
    }

    /** Probe and file in one step, for callers with nothing to ask the reader. */
    fun addFromUrl(repo: Repo, input: String, category: String? = null): Probe {
        return when (val outcome = probe(repo, input)) {
            is Probe.Found -> {
                save(repo, outcome, outcome.title, category)
                outcome
            }
            else -> outcome
        }
    }
}
