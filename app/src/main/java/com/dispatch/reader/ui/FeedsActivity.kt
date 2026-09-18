package com.dispatch.reader.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dispatch.reader.App
import com.dispatch.reader.R
import com.dispatch.reader.data.Feed
import com.dispatch.reader.data.Opml
import com.dispatch.reader.data.Repo
import com.dispatch.reader.feed.Sync
import com.dispatch.reader.shell.Frame
import com.dispatch.reader.util.Safely
import com.dispatch.reader.util.Time
import com.dispatch.reader.web.ExternalLinks

/**
 * The feed library: everything subscribed, searchable, filterable by category,
 * and honest about which feeds are failing.
 *
 * Two jobs, and the second one is why this screen is worth its weight:
 *
 *  - **finding a feed among a hundred.** The bundled set is raw material for
 *    streams, which is useless if it cannot be searched. Search matches the
 *    title, the address and the category.
 *  - **saying which feeds are broken.** A hundred feeds will not stay a hundred
 *    working feeds — publishers move them, put them behind bot defences, or
 *    replace them with a holding page, and this project watched ESPN, CBC and
 *    four Penske titles do exactly that while the bundled set was assembled.
 *    Without a visible health line a dead feed is indistinguishable from a
 *    quiet one, and the only symptom is a section that slowly stops updating.
 */
class FeedsActivity : AppCompatActivity() {

    private lateinit var frame: Frame
    private lateinit var empty: TextView
    private lateinit var searchField: EditText
    private lateinit var categoryFilter: Spinner
    private lateinit var adapter: Adapter

    private var categories: List<String> = emptyList()
    private var filterCategory: String? = null
    private var query: String = ""

    private val app: App get() = application as App

    private val importer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { importOpml(it) }
    }
    private val exporter = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { exportOpml(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)

        frame = Frame(
            activity = this,
            barWrapper = findViewById(R.id.topBarWrapper),
            barRow = findViewById(R.id.topBarRow),
            content = findViewById(R.id.content),
        )
        frame.install()
        frame.applyBarColour(ContextCompat.getColor(this, R.color.accent))

        findViewById<TextView>(R.id.screenTitle).setText(R.string.feeds_title)
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.addButton).apply {
            visibility = View.VISIBLE
            contentDescription = getString(R.string.add_feed)
            setOnClickListener { startActivity(Intent(this@FeedsActivity, AddFeedActivity::class.java)) }
        }
        findViewById<ImageButton>(R.id.overflowButton).setOnClickListener { showOverflow(it) }

        findViewById<View>(R.id.filterRow).visibility = View.VISIBLE
        searchField = findViewById(R.id.filterSearch)
        categoryFilter = findViewById(R.id.filterCategory)
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString().orEmpty()
                reload()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        empty = findViewById(R.id.emptyView)
        empty.setText(R.string.feeds_empty)
        adapter = Adapter(::showFeedMenu)
        findViewById<RecyclerView>(R.id.rows).apply {
            layoutManager = LinearLayoutManager(this@FeedsActivity)
            adapter = this@FeedsActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        frame.applyBarColour(ContextCompat.getColor(this, R.color.accent))
        loadCategories()
        reload()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        frame.applyBarColour(ContextCompat.getColor(this, R.color.accent))
        frame.requestInsets()
    }

    private fun reload() {
        val q = query
        val category = filterCategory
        app.io.execute {
            val feeds = Safely.call({ app.repo.feeds(q, category) }, emptyList())
            runOnUiThread {
                adapter.submit(feeds)
                empty.visibility = if (feeds.isEmpty()) View.VISIBLE else View.GONE
                empty.setText(
                    if (q.isNotEmpty() || category != null) R.string.feeds_none_match else R.string.feeds_empty
                )
            }
        }
    }

    private fun loadCategories() {
        app.io.execute {
            val names = Safely.call({ app.repo.categoryNames() }, emptyList())
            runOnUiThread {
                if (names == categories && categoryFilter.adapter != null) return@runOnUiThread
                categories = names
                val labels = ArrayList<String>()
                labels.add(getString(R.string.filter_all_categories))
                labels.addAll(names)
                labels.add(getString(R.string.filter_uncategorised))
                val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                categoryFilter.adapter = spinnerAdapter
                categoryFilter.onItemSelectedListener =
                    object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: android.widget.AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long,
                        ) {
                            filterCategory = when (position) {
                                0 -> null
                                labels.size - 1 -> Repo.UNCATEGORISED
                                else -> categories.getOrNull(position - 1)
                            }
                            reload()
                        }

                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                    }
            }
        }
    }

    // ------------------------------------------------------------- per feed

    private fun showFeedMenu(feed: Feed) {
        val options = mutableListOf(
            getString(R.string.action_set_category),
            getString(R.string.action_rename),
            getString(R.string.action_refresh_one),
            getString(R.string.action_copy_feed_url),
        )
        if (!feed.siteLink.isNullOrBlank()) options.add(getString(R.string.action_open_site))
        options.add(getString(R.string.action_remove_feed))

        AlertDialog.Builder(this)
            .setTitle(feed.title)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    getString(R.string.action_set_category) -> pickCategory(feed)
                    getString(R.string.action_rename) -> rename(feed)
                    getString(R.string.action_refresh_one) -> refreshOne(feed)
                    getString(R.string.action_copy_feed_url) -> ExternalLinks.copy(this, feed.url)
                    getString(R.string.action_open_site) ->
                        feed.siteLink?.let { ExternalLinks.open(this, it) }
                    getString(R.string.action_remove_feed) -> confirmRemove(feed)
                }
            }
            .show()
    }

    /** Re-file a feed, including under a category invented here. */
    private fun pickCategory(feed: Feed) {
        val labels = ArrayList<String>()
        labels.add(getString(R.string.category_none))
        labels.addAll(categories)
        val current = categories.indexOf(feed.category).let { if (it >= 0) it + 1 else 0 }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_set_category)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                val chosen = if (which == 0) null else categories.getOrNull(which - 1)
                dialog.dismiss()
                app.io.execute {
                    Safely.run { app.repo.setFeedCategory(feed.id, chosen) }
                    runOnUiThread { reload() }
                }
            }
            .setNeutralButton(R.string.category_new) { _, _ -> promptNewCategory(feed) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptNewCategory(feed: Feed) {
        val field = EditText(this).apply {
            hint = getString(R.string.category_name_hint)
            setSingleLine()
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.category_new)
            .setView(field)
            .setPositiveButton(R.string.add) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                app.io.execute {
                    Safely.run { app.repo.setFeedCategory(feed.id, name) }
                    runOnUiThread {
                        loadCategories()
                        reload()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun rename(feed: Feed) {
        val field = EditText(this).apply {
            setText(feed.title)
            setSingleLine()
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_rename)
            .setView(field)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isNotEmpty()) {
                    app.io.execute {
                        Safely.run { app.repo.renameFeed(feed.id, name) }
                        runOnUiThread { reload() }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshOne(feed: Feed) {
        app.io.execute {
            val result = Safely.call({ Sync.refreshOne(app.repo, feed) }, null)
            runOnUiThread {
                toast(
                    when {
                        result == null -> getString(R.string.add_feed_failed)
                        result.error != null -> result.error.orEmpty()
                        result.notModified -> getString(R.string.refresh_unchanged)
                        else -> resources.getQuantityString(
                            R.plurals.refresh_new, result.newArticles, result.newArticles
                        )
                    }
                )
                reload()
            }
        }
    }

    private fun confirmRemove(feed: Feed) {
        AlertDialog.Builder(this)
            .setTitle(R.string.action_remove_feed)
            // Saying what else goes is the honest thing: the cascade takes the
            // feed's articles with it, and a reader who has saved one of them
            // should know before, not after.
            .setMessage(getString(R.string.remove_feed_confirm, feed.title))
            .setPositiveButton(R.string.action_remove_feed) { _, _ ->
                app.io.execute {
                    Safely.run { app.repo.deleteFeed(feed.id) }
                    runOnUiThread { reload() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ----------------------------------------------------------- overflow

    private fun showOverflow(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.inflate(R.menu.feeds)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import -> importer.launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(
                            Intent.EXTRA_MIME_TYPES,
                            arrayOf("text/xml", "application/xml", "text/x-opml", "*/*"),
                        )
                )
                R.id.action_export -> exporter.launch(
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/xml")
                        .putExtra(Intent.EXTRA_TITLE, "dispatch-subscriptions.opml")
                )
                R.id.action_categories -> startActivity(Intent(this, CategoriesActivity::class.java))
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        menu.show()
    }

    private fun importOpml(uri: Uri) {
        app.io.execute {
            val xml = Safely.call({
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }, null)
            if (xml.isNullOrBlank()) {
                runOnUiThread { toast(getString(R.string.import_failed)) }
                return@execute
            }
            val added = Safely.call({
                com.dispatch.reader.data.Seed.install(app.repo, Opml.parse(xml), makeStreams = true)
            }, 0)
            runOnUiThread {
                toast(resources.getQuantityString(R.plurals.import_added, added, added))
                loadCategories()
                reload()
            }
        }
    }

    private fun exportOpml(uri: Uri) {
        app.io.execute {
            val xml = Safely.call({ buildExport(app.repo) }, null)
            val bytes = (xml ?: "").toByteArray()
            val done = xml != null && Safely.call({
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                true
            }, false)
            runOnUiThread {
                toast(getString(if (done) R.string.export_done else R.string.export_failed))
            }
        }
    }

    /**
     * Group the export by **category**, not by stream.
     *
     * OPML folders are a single tree and a feed can be in many streams, so
     * exporting by stream would duplicate feeds and lose which category they
     * were filed under — and the category is the thing another reader can
     * actually use. Feeds with no category go at the top level.
     */
    private fun buildExport(repo: Repo): String {
        val groups = ArrayList<Opml.Group>()
        for (category in repo.categoryNames()) {
            val feeds = repo.feeds(category = category)
            if (feeds.isEmpty()) continue
            groups.add(Opml.Group(category, feeds.map { Opml.Entry(it.title, it.url, it.siteLink) }))
        }
        val loose = repo.feeds(category = Repo.UNCATEGORISED)
        if (loose.isNotEmpty()) {
            groups.add(Opml.Group(null, loose.map { Opml.Entry(it.title, it.url, it.siteLink) }))
        }
        return Opml.export(groups)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------- adapter

    private class Adapter(val onClick: (Feed) -> Unit) : RecyclerView.Adapter<Adapter.Holder>() {

        private var items: List<Feed> = emptyList()

        fun submit(feeds: List<Feed>) {
            items = feeds
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.row_feed, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val feed = items.getOrNull(position) ?: return
            val context = holder.itemView.context
            holder.title.text = feed.title

            val health = when {
                feed.lastError != null -> feed.lastError
                feed.lastFetchAt <= 0L -> context.getString(R.string.feed_never_fetched)
                else -> context.getString(
                    R.string.feed_ok, feed.lastCount, Time.relative(feed.lastFetchAt)
                )
            }
            val category = feed.category ?: context.getString(R.string.category_none)
            holder.status.text = context.getString(R.string.feed_status_line, category, health)
            holder.status.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (feed.lastError != null) R.color.error_text else R.color.hint_text,
                )
            )
            holder.itemView.setOnClickListener { onClick(feed) }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.feedTitle)
            val status: TextView = view.findViewById(R.id.feedStatus)
        }
    }
}
