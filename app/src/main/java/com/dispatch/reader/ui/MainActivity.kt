package com.dispatch.reader.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dispatch.reader.App
import com.dispatch.reader.R
import com.dispatch.reader.data.Article
import com.dispatch.reader.data.Stream
import com.dispatch.reader.feed.Sync
import com.dispatch.reader.shell.Frame
import com.dispatch.reader.util.Prefs
import com.dispatch.reader.util.Safely
import com.dispatch.reader.web.ExternalLinks

/**
 * The reading screen, and the app's navigation.
 *
 * ## Why there is a drawer
 *
 * 1.0.x put every destination behind an overflow menu: streams, the feed
 * library, "add feed", settings. A reader could not find how to add a feed or
 * build a stream, which are the two things this app is *for*. The drawer puts
 * the streams they have made in front of them by name, with the four actions
 * that create or manage them directly underneath.
 *
 * ## The back arrow
 *
 * The left slot is the drawer toggle, always. A back arrow appears beside it
 * whenever the reader is somewhere other than All feeds — inside a stream, in
 * Saved, or in a search — and returns them there. On All feeds there is nowhere
 * to go back to, so it is hidden rather than left as a control that does
 * nothing. The system back gesture does the same thing before it leaves the
 * app, so the two can never disagree.
 *
 * ## There is no pull-to-refresh, on purpose
 *
 * There was, and it behaved correctly: the suite's standing rule
 * (`claude/pull-to-refresh-rule.md`) is about a **WebView** answering
 * `canScrollVertically(-1)` wrongly, and a RecyclerView answers it properly.
 * It was removed for a different reason: the gesture is indistinguishable from
 * an overscroll at the top of the list, and here the cost of a misfire is
 * fetching every feed in the stream over a phone radio. A refresh is a button
 * now — the one in the bar, or the overflow item — and it refreshes **this
 * stream only**.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var frame: Frame
    private lateinit var drawer: DrawerLayout
    private lateinit var drawerAdapter: DrawerAdapter
    private lateinit var busyView: View
    private lateinit var busyText: TextView
    private lateinit var list: RecyclerView
    private lateinit var streamButton: TextView
    private lateinit var searchField: EditText
    private lateinit var emptyView: TextView
    private lateinit var backButton: ImageButton
    private lateinit var startCard: View
    private lateinit var adapter: ArticleAdapter

    private var streamId: Long = Stream.ALL_ID
    private var query: String = ""
    private var unreadOnly: Boolean = false

    /**
     * The stream a refresh is currently running for, or null.
     *
     * Kept as an id rather than a flag so that walking away from a refreshing
     * stream shows the stream you walked to, rather than the busy screen that
     * belongs to the one you left.
     */
    private var refreshingStream: Long? = null

    private val app: App get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        // FIRST, and not optional: this activity's manifest theme is
        // Theme.Dispatch.Splash, whose parent is Theme.SplashScreen — which is
        // NOT an AppCompat theme. installSplashScreen() is what swaps the
        // activity onto `postSplashScreenTheme`. Without it the activity keeps
        // the splash theme and AppCompatDelegate throws
        // "You need to use a Theme.AppCompat theme (or descendant)" the moment
        // setContentView runs. 1.0.0 shipped without this line and crashed on
        // every launch; tools/verify_frame.py now refuses a splash-themed
        // activity that does not call it.
        installSplashScreen()
        // Requirement 2 of the frame rule: before setContentView, always.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        frame = Frame(
            activity = this,
            barWrapper = findViewById(R.id.topBarWrapper),
            barRow = findViewById(R.id.topBarRow),
            content = findViewById(R.id.content),
        )
        frame.install()
        frame.applyBarColour(resolveColour(R.color.top_bar))

        drawer = findViewById(R.id.drawer)
        // The panel is an overlay, not part of the vertical stack, so it takes
        // its own insets or its first row sits under the clock.
        frame.insetPanel(findViewById(R.id.drawerPanel))

        busyView = findViewById(R.id.busyView)
        busyText = findViewById(R.id.busyText)
        list = findViewById(R.id.articles)
        streamButton = findViewById(R.id.streamButton)
        searchField = findViewById(R.id.searchField)
        emptyView = findViewById(R.id.emptyView)
        backButton = findViewById(R.id.backButton)
        startCard = findViewById(R.id.startCard)

        adapter = ArticleAdapter(onClick = ::openArticle, onLongClick = ::showArticleMenu)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.setHasFixedSize(true)

        drawerAdapter = DrawerAdapter(onStream = ::selectStream, onAction = ::drawerAction)
        findViewById<RecyclerView>(R.id.drawerList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = drawerAdapter
        }

        findViewById<ImageButton>(R.id.refreshButton).setOnClickListener { refreshNow() }

        findViewById<ImageButton>(R.id.menuButton).setOnClickListener {
            if (drawer.isDrawerOpen(GravityCompat.START)) {
                drawer.closeDrawer(GravityCompat.START)
            } else {
                drawer.openDrawer(GravityCompat.START)
            }
        }
        backButton.setOnClickListener { goBack() }
        streamButton.setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        findViewById<ImageButton>(R.id.searchButton).setOnClickListener { toggleSearch() }
        findViewById<ImageButton>(R.id.overflowButton).setOnClickListener { showOverflow(it) }

        findViewById<Button>(R.id.startCardBuild).setOnClickListener {
            Prefs.setStartCardDone(this)
            startCard.visibility = View.GONE
            startActivity(Intent(this, StreamEditActivity::class.java))
        }
        findViewById<Button>(R.id.startCardDismiss).setOnClickListener {
            Prefs.setStartCardDone(this)
            startCard.visibility = View.GONE
        }

        searchField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                query = searchField.text.toString().trim()
                reload()
                true
            } else {
                false
            }
        }

        // The drawer and the back arrow answer the system back button in the
        // same order the reader would expect, so gesture and button agree.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
                    canGoBack() -> goBack()
                    else -> finish()
                }
            }
        })

        unreadOnly = Prefs.unreadOnly(this)
        // A widget's intent names the stream it is tied to and wins; otherwise
        // the reader's "open on" setting decides, which defaults to wherever
        // they left off.
        streamId = intent.getLongExtra(EXTRA_STREAM_ID, Prefs.openStream(this))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(EXTRA_STREAM_ID)) {
            streamId = intent.getLongExtra(EXTRA_STREAM_ID, Stream.ALL_ID)
        }
    }

    override fun onResume() {
        super.onResume()
        frame.applyBarColour(resolveColour(R.color.top_bar))
        reload()
        reloadDrawer()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // This activity declares uiMode in configChanges, so it is not
        // recreated on a day/night switch and anything resolved at inflate
        // would stay the daytime colour until the next cold start.
        frame.applyBarColour(resolveColour(R.color.top_bar))
        frame.requestInsets()
    }

    // ---------------------------------------------------------- navigation

    /**
     * Whether there is anywhere to go back *to*.
     *
     * An open search box counts even before anything is typed: opening it is a
     * step the reader took, and back has to undo it or the gesture leaves the
     * app from a screen that visibly changed.
     */
    private fun canGoBack(): Boolean =
        streamId != Stream.ALL_ID || query.isNotEmpty() || searchField.visibility == View.VISIBLE

    private fun goBack() {
        // One step at a time: a search closes back to the stream it was run
        // in, and only then does back leave the stream for All feeds.
        if (query.isNotEmpty() || searchField.visibility == View.VISIBLE) {
            searchField.setText("")
            searchField.visibility = View.GONE
            streamButton.visibility = View.VISIBLE
            query = ""
            reload()
            return
        }
        streamId = Stream.ALL_ID
        Prefs.setSelectedStream(this, streamId)
        reload()
        reloadDrawer()
    }

    private fun selectStream(id: Long) {
        streamId = id
        Prefs.setSelectedStream(this, id)
        query = ""
        searchField.setText("")
        drawer.closeDrawer(GravityCompat.START)
        reload()
        reloadDrawer()
    }

    private fun drawerAction(action: Int) {
        drawer.closeDrawer(GravityCompat.START)
        when (action) {
            DrawerAdapter.ACTION_NEW_STREAM -> startActivity(Intent(this, StreamEditActivity::class.java))
            DrawerAdapter.ACTION_ADD_FEED -> startActivity(Intent(this, AddFeedActivity::class.java))
            DrawerAdapter.ACTION_LIBRARY -> startActivity(Intent(this, FeedsActivity::class.java))
            DrawerAdapter.ACTION_CATEGORIES -> startActivity(Intent(this, CategoriesActivity::class.java))
            DrawerAdapter.ACTION_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    /**
     * Rebuild the drawer.
     *
     * Unread counts are part of it, so this runs on every resume rather than
     * once: coming back from an article with the count unchanged is the kind of
     * small lie that makes a reader stop trusting the number.
     */
    private fun reloadDrawer() {
        val current = streamId
        app.io.execute {
            val streams = Safely.call({ app.repo.streams() }, emptyList())
            val allUnread = Safely.call({ app.repo.unreadCount(Stream.ALL_ID) }, 0)
            val savedCount = Safely.call({ app.repo.savedCount() }, 0)
            val counts = streams.associate { it.id to Safely.call({ app.repo.unreadCount(it.id) }, 0) }
            val feedCount = Safely.call({ app.repo.feedCount() }, 0)

            val rows = ArrayList<DrawerAdapter.Row>()
            rows.add(
                DrawerAdapter.Row.Item(
                    Stream.ALL_ID, getString(R.string.stream_all), R.drawable.ic_all,
                    allUnread, current == Stream.ALL_ID,
                )
            )
            rows.add(
                DrawerAdapter.Row.Item(
                    Stream.SAVED_ID, getString(R.string.stream_saved), R.drawable.ic_saved_on,
                    savedCount, current == Stream.SAVED_ID,
                )
            )
            rows.add(DrawerAdapter.Row.Header(getString(R.string.drawer_streams)))
            for (stream in streams) {
                rows.add(
                    DrawerAdapter.Row.Item(
                        stream.id, stream.name, R.drawable.ic_stream,
                        counts[stream.id] ?: 0, current == stream.id,
                    )
                )
            }
            if (streams.isEmpty()) {
                rows.add(
                    DrawerAdapter.Row.Action(
                        DrawerAdapter.ACTION_NEW_STREAM,
                        getString(R.string.drawer_no_streams),
                        R.drawable.ic_add,
                    )
                )
            }
            rows.add(DrawerAdapter.Row.Divider)
            rows.add(
                DrawerAdapter.Row.Action(
                    DrawerAdapter.ACTION_NEW_STREAM, getString(R.string.new_stream), R.drawable.ic_add
                )
            )
            rows.add(
                DrawerAdapter.Row.Action(
                    DrawerAdapter.ACTION_ADD_FEED, getString(R.string.add_feed), R.drawable.ic_add
                )
            )
            rows.add(
                DrawerAdapter.Row.Action(
                    DrawerAdapter.ACTION_LIBRARY,
                    getString(R.string.drawer_library, feedCount),
                    R.drawable.ic_all,
                )
            )
            rows.add(
                DrawerAdapter.Row.Action(
                    DrawerAdapter.ACTION_CATEGORIES, getString(R.string.categories_title), R.drawable.ic_category
                )
            )
            rows.add(
                DrawerAdapter.Row.Action(
                    DrawerAdapter.ACTION_SETTINGS, getString(R.string.settings_title), R.drawable.ic_settings
                )
            )

            runOnUiThread { drawerAdapter.submit(rows) }
        }
    }

    // ------------------------------------------------------------- loading

    private fun reload() {
        val currentStream = streamId
        val currentQuery = query
        val onlyUnread = unreadOnly
        app.io.execute {
            val articles = Safely.call({
                app.repo.articles(currentStream, onlyUnread, currentQuery, limit = PAGE)
            }, emptyList())
            val label = Safely.call({ streamLabel(currentStream) }, getString(R.string.stream_all))
            val streamMissing = currentStream > 0 && Safely.call({ app.repo.stream(currentStream) }, null) == null
            val showStart = !Prefs.startCardDone(this) &&
                Safely.call({ app.repo.streams().isEmpty() }, false)
            runOnUiThread {
                if (currentStream != streamId || currentQuery != query) return@runOnUiThread
                // A refresh owns the screen of the stream it is refreshing until
                // it finishes, and calls reload() itself when it does.
                if (refreshingStream == currentStream) return@runOnUiThread
                busyView.visibility = View.GONE
                list.visibility = View.VISIBLE
                // A stream deleted from another screen must not leave the
                // reader looking at an empty view with a name on it.
                if (streamMissing) {
                    goBack()
                    return@runOnUiThread
                }
                streamButton.text = label
                backButton.visibility = if (canGoBack()) View.VISIBLE else View.GONE
                startCard.visibility = if (showStart && currentQuery.isEmpty()) View.VISIBLE else View.GONE
                adapter.submit(articles)
                emptyView.visibility = if (articles.isEmpty()) View.VISIBLE else View.GONE
                emptyView.setText(
                    when {
                        currentQuery.isNotEmpty() -> R.string.empty_search
                        currentStream == Stream.SAVED_ID -> R.string.empty_saved
                        onlyUnread -> R.string.empty_unread
                        else -> R.string.empty_stream
                    }
                )
            }
        }
    }

    private fun streamLabel(id: Long): String = when (id) {
        Stream.ALL_ID -> getString(R.string.stream_all)
        Stream.SAVED_ID -> getString(R.string.stream_saved)
        else -> app.repo.stream(id)?.name ?: getString(R.string.stream_all)
    }

    /**
     * Refresh the stream on screen, and hide it while that happens.
     *
     * Two rules, both from watching this on a phone:
     *
     *  - **only this stream's feeds.** The reader asked about what they are
     *    looking at. "All feeds" still means all of them, because that is what
     *    it says.
     *  - **the list goes away until it is done.** A story tapped mid-refresh
     *    could be one the sync was in the middle of rewriting, and it opened
     *    blank. There is nothing to tap now, and the count says why.
     */
    private fun refreshNow() {
        if (refreshingStream != null) return
        val stream = streamId
        refreshingStream = stream
        showBusy(getString(R.string.refreshing))
        app.io.execute {
            // force = true: the reader asked now, so cadence does not apply.
            val summary = Safely.call({
                Sync.refreshStream(this, app.repo, stream, force = true) { done, total ->
                    runOnUiThread {
                        if (refreshingStream == stream && busyView.visibility == View.VISIBLE) {
                            busyText.text = getString(R.string.refreshing_count, done, total)
                        }
                    }
                }
            }, null)
            runOnUiThread {
                refreshingStream = null
                busyView.visibility = View.GONE
                if (summary != null) toast(summaryText(summary))
                reload()
                reloadDrawer()
            }
        }
    }

    private fun showBusy(message: String) {
        busyText.text = message
        busyView.visibility = View.VISIBLE
        list.visibility = View.GONE
        emptyView.visibility = View.GONE
        startCard.visibility = View.GONE
    }

    private fun summaryText(summary: Sync.Summary): String = when {
        summary.feedsTried == 0 -> getString(R.string.refresh_no_feeds)
        summary.newArticles == 0 && summary.feedsFailed == 0 -> getString(R.string.refresh_nothing_new)
        summary.feedsFailed == 0 -> resources.getQuantityString(
            R.plurals.refresh_new, summary.newArticles, summary.newArticles
        )
        else -> getString(R.string.refresh_partial, summary.newArticles, summary.feedsFailed)
    }

    // -------------------------------------------------------------- chrome

    private fun toggleSearch() {
        val showing = searchField.visibility == View.VISIBLE
        searchField.visibility = if (showing) View.GONE else View.VISIBLE
        streamButton.visibility = if (showing) View.VISIBLE else View.GONE
        if (showing) {
            searchField.setText("")
            query = ""
            reload()
        } else {
            searchField.requestFocus()
            // The arrow is set in reload(), which opening the field does not
            // trigger — without this the box opens with no way back out of it.
            backButton.visibility = View.VISIBLE
        }
    }

    private fun showOverflow(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.inflate(R.menu.main)
        menu.menu.findItem(R.id.action_unread_only).isChecked = unreadOnly
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> refreshNow()
                R.id.action_unread_only -> {
                    unreadOnly = !unreadOnly
                    Prefs.setUnreadOnly(this, unreadOnly)
                    reload()
                }
                R.id.action_mark_all_read -> markAllRead()
                R.id.action_edit_stream -> editCurrentStream()
                R.id.action_feeds -> startActivity(Intent(this, FeedsActivity::class.java))
                R.id.action_streams -> startActivity(Intent(this, StreamsActivity::class.java))
                R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        menu.menu.findItem(R.id.action_edit_stream).isVisible = streamId > 0
        menu.show()
    }

    private fun editCurrentStream() {
        if (streamId <= 0) return
        startActivity(
            Intent(this, StreamEditActivity::class.java)
                .putExtra(StreamEditActivity.EXTRA_STREAM_ID, streamId)
        )
    }

    private fun markAllRead() {
        val current = streamId
        AlertDialog.Builder(this)
            .setTitle(R.string.mark_all_read)
            .setMessage(R.string.mark_all_read_confirm)
            .setPositiveButton(R.string.mark_all_read) { _, _ ->
                app.io.execute {
                    Safely.run { app.repo.markStreamRead(current) }
                    runOnUiThread {
                        reload()
                        reloadDrawer()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ articles

    private fun openArticle(article: Article) {
        startActivity(
            Intent(this, ArticleActivity::class.java)
                .putExtra(ArticleActivity.EXTRA_ARTICLE_ID, article.id)
        )
    }

    private fun showArticleMenu(article: Article) {
        val options = arrayOf(
            getString(R.string.action_open_external),
            getString(if (article.saved) R.string.action_unsave else R.string.action_save),
            getString(if (article.read) R.string.action_mark_unread else R.string.action_mark_read),
            getString(R.string.action_copy_link),
            getString(R.string.action_share),
        )
        AlertDialog.Builder(this)
            .setTitle(article.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> ExternalLinks.open(this, article.link)
                    1 -> app.io.execute {
                        Safely.run { app.repo.setSaved(article.id, !article.saved) }
                        runOnUiThread { reload() }
                    }
                    2 -> app.io.execute {
                        Safely.run { app.repo.setRead(article.id, !article.read) }
                        runOnUiThread {
                            reload()
                            reloadDrawer()
                        }
                    }
                    3 -> ExternalLinks.copy(this, article.link)
                    4 -> share(article)
                }
            }
            .show()
    }

    private fun share(article: Article) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, article.title)
            putExtra(Intent.EXTRA_TEXT, article.title + "\n" + article.link)
        }
        Safely.run { startActivity(Intent.createChooser(intent, getString(R.string.action_share))) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun resolveColour(id: Int): Int = androidx.core.content.ContextCompat.getColor(this, id)

    companion object {
        const val EXTRA_STREAM_ID = "stream_id"

        /** How many rows the list holds. Beyond this, use search. */
        const val PAGE = 300
    }
}
