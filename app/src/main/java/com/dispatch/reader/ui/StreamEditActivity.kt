package com.dispatch.reader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dispatch.reader.App
import com.dispatch.reader.R
import com.dispatch.reader.data.Feed
import com.dispatch.reader.data.Repo
import com.dispatch.reader.data.Stream
import com.dispatch.reader.feed.SyncScheduler
import com.dispatch.reader.shell.Frame
import com.dispatch.reader.util.Safely
import com.dispatch.reader.widget.NewsWidgetProvider

/**
 * The stream builder: a name, a refresh cadence, whole categories, and a
 * searchable view of the feed library.
 *
 * This is where the hundred bundled feeds earn their place. They are not seven
 * ready-made sections to live inside — they are the raw material, and the
 * categories exist so that a reader can find "the three football feeds" among a
 * hundred without scrolling past ninety-seven of them.
 *
 * ## Two kinds of membership, on purpose
 *
 * A stream holds feeds picked one by one **and** whole categories. The
 * difference matters over time: a category rule keeps working. Tag a new paper
 * "Local" next month and every stream that asked for Local has it, without the
 * reader remembering to come back. Ticking feeds one by one is the precise
 * tool; a category is the durable one.
 *
 * ## The cadence
 *
 * Per stream, not per app: a news stream someone watches all day and a weekly
 * long-reads stream have no business being fetched at the same rate. A feed
 * that belongs to several streams is refreshed on the shortest of their
 * cadences — it cannot be fetched twice at different rates, and the reader
 * asked for it that often somewhere.
 */
class StreamEditActivity : AppCompatActivity() {

    private lateinit var frame: Frame
    private lateinit var nameField: EditText
    private lateinit var cadenceSpinner: Spinner
    private lateinit var categoriesButton: Button
    private lateinit var searchField: EditText
    private lateinit var categoryFilter: Spinner
    private lateinit var summary: TextView
    private lateinit var adapter: PickAdapter

    private var streamId: Long = 0L
    private val selectedFeeds = LinkedHashSet<Long>()
    private val selectedCategories = LinkedHashSet<String>()
    private var allCategories: List<String> = emptyList()
    private var filterCategory: String? = null
    private var query: String = ""

    private val app: App get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stream_edit)

        frame = Frame(
            activity = this,
            barWrapper = findViewById(R.id.topBarWrapper),
            barRow = findViewById(R.id.topBarRow),
            content = findViewById(R.id.content),
        )
        frame.install()
        frame.applyBarColour(ContextCompat.getColor(this, R.color.accent))

        streamId = intent.getLongExtra(EXTRA_STREAM_ID, 0L)

        nameField = findViewById(R.id.nameField)
        cadenceSpinner = findViewById(R.id.cadenceSpinner)
        categoriesButton = findViewById(R.id.categoriesButton)
        searchField = findViewById(R.id.feedSearch)
        categoryFilter = findViewById(R.id.feedCategoryFilter)
        summary = findViewById(R.id.selectionSummary)

        findViewById<TextView>(R.id.screenTitle).setText(
            if (streamId > 0) R.string.stream_edit_title else R.string.new_stream
        )
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
        categoriesButton.setOnClickListener { pickCategories() }

        adapter = PickAdapter { feed ->
            if (!selectedFeeds.add(feed.id)) selectedFeeds.remove(feed.id)
            adapter.notifyDataSetChanged()
            updateSummary()
        }
        findViewById<RecyclerView>(R.id.feedList).apply {
            layoutManager = LinearLayoutManager(this@StreamEditActivity)
            adapter = this@StreamEditActivity.adapter
        }

        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString().orEmpty()
                loadFeeds()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        setUpCadence()
        load()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        frame.applyBarColour(ContextCompat.getColor(this, R.color.accent))
        frame.requestInsets()
    }

    // -------------------------------------------------------------- set-up

    private fun setUpCadence() {
        val labels = resources.getStringArray(R.array.stream_cadence_entries)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        cadenceSpinner.adapter = adapter
    }

    private fun cadenceValues(): IntArray =
        resources.getStringArray(R.array.stream_cadence_values).map { it.toInt() }.toIntArray()

    private fun load() {
        val id = streamId
        app.io.execute {
            val stream = if (id > 0) Safely.call({ app.repo.stream(id) }, null) else null
            val feeds = if (id > 0) Safely.call({ app.repo.streamFeedIds(id) }, emptySet()) else emptySet()
            val cats = if (id > 0) Safely.call({ app.repo.streamCategories(id) }, emptySet()) else emptySet()
            val names = Safely.call({ app.repo.categoryNames() }, emptyList())
            runOnUiThread {
                allCategories = names
                selectedFeeds.clear()
                selectedFeeds.addAll(feeds)
                selectedCategories.clear()
                selectedCategories.addAll(cats)
                if (stream != null) {
                    nameField.setText(stream.name)
                    val values = cadenceValues()
                    val index = values.indexOf(stream.refreshMinutes)
                    cadenceSpinner.setSelection(if (index >= 0) index else 0)
                }
                setUpFilter()
                updateSummary()
                loadFeeds()
            }
        }
    }

    private fun setUpFilter() {
        val labels = ArrayList<String>()
        labels.add(getString(R.string.filter_all_categories))
        labels.addAll(allCategories)
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
                        else -> allCategories.getOrNull(position - 1)
                    }
                    loadFeeds()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
    }

    private fun loadFeeds() {
        val q = query
        val category = filterCategory
        app.io.execute {
            val feeds = Safely.call({ app.repo.feeds(q, category) }, emptyList())
            runOnUiThread {
                adapter.submit(feeds, selectedFeeds)
                updateSummary()
            }
        }
    }

    // ---------------------------------------------------------- categories

    /**
     * Whole categories, plus a way to invent one on the spot — the same
     * affordance the add-feed screen has, because the moment a reader decides
     * "this stream is my local news" is the moment the category should exist.
     */
    private fun pickCategories() {
        val labels = allCategories.toTypedArray()
        val checked = BooleanArray(labels.size) { labels[it] in selectedCategories }
        AlertDialog.Builder(this)
            .setTitle(R.string.stream_pick_categories)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.save) { _, _ ->
                selectedCategories.clear()
                labels.forEachIndexed { index, name -> if (checked[index]) selectedCategories.add(name) }
                updateSummary()
            }
            .setNeutralButton(R.string.category_new) { _, _ -> promptNewCategory() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptNewCategory() {
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
                    Safely.run { app.repo.createCategory(name) }
                    val names = Safely.call({ app.repo.categoryNames() }, emptyList())
                    runOnUiThread {
                        allCategories = names
                        selectedCategories.add(name)
                        setUpFilter()
                        updateSummary()
                        pickCategories()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // -------------------------------------------------------------- saving

    private fun updateSummary() {
        categoriesButton.text = if (selectedCategories.isEmpty()) {
            getString(R.string.stream_categories_none)
        } else {
            getString(R.string.stream_categories_some, selectedCategories.joinToString(", "))
        }
        summary.text = getString(
            R.string.stream_selection_summary,
            selectedFeeds.size,
            selectedCategories.size,
        )
    }

    private fun save() {
        val name = nameField.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.stream_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedFeeds.isEmpty() && selectedCategories.isEmpty()) {
            // A stream with no membership is not an error worth blocking — but
            // it would show nothing, and a reader who saved it by accident
            // deserves to be told rather than handed an empty list.
            AlertDialog.Builder(this)
                .setTitle(R.string.stream_empty_title)
                .setMessage(R.string.stream_empty_message)
                .setPositiveButton(R.string.save) { _, _ -> commit(name) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        commit(name)
    }

    private fun commit(name: String) {
        val minutes = cadenceValues().getOrElse(cadenceSpinner.selectedItemPosition) { Stream.REFRESH_DEFAULT }
        val feeds = selectedFeeds.toList()
        val categories = selectedCategories.toList()
        val id = streamId
        app.io.execute {
            val target = if (id > 0) {
                Safely.run { app.repo.updateStream(id, name, minutes) }
                id
            } else {
                Safely.call({ app.repo.createStream(name, feeds, categories, minutes) }, -1L)
            }
            if (target > 0) {
                Safely.run { app.repo.setStreamFeeds(target, feeds) }
                Safely.run { app.repo.setStreamCategories(target, categories) }
                // A new cadence changes how often the background job has to
                // run, and a setting that does not take effect is this
                // project's most repeated failure.
                Safely.run { SyncScheduler.reschedule(app, app.repo) }
                Safely.run { NewsWidgetProvider.refreshAll(app) }
            }
            runOnUiThread {
                if (target > 0) finish() else Toast.makeText(
                    this, R.string.stream_save_failed, Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ------------------------------------------------------------- adapter

    private class PickAdapter(val onToggle: (Feed) -> Unit) : RecyclerView.Adapter<PickAdapter.Holder>() {

        private var items: List<Feed> = emptyList()
        private var selected: Set<Long> = emptySet()

        /**
         * @param selection **aliased, not copied.** The activity owns it and
         *   mutates it as rows are tapped; a defensive copy here would freeze
         *   the ticks at whatever was selected when the list was last built,
         *   because the row's CheckBox is not clickable and only a rebind can
         *   move it.
         */
        fun submit(feeds: List<Feed>, selection: Set<Long>) {
            items = feeds
            selected = selection
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.row_pick_feed, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val feed = items.getOrNull(position) ?: return
            val context = holder.itemView.context
            holder.title.text = feed.title
            holder.subtitle.text = feed.category ?: context.getString(R.string.category_none)
            holder.check.isChecked = feed.id in selected
            holder.itemView.setOnClickListener { onToggle(feed) }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val check: CheckBox = view.findViewById(R.id.pickCheck)
            val title: TextView = view.findViewById(R.id.pickTitle)
            val subtitle: TextView = view.findViewById(R.id.pickSubtitle)
        }
    }

    companion object {
        const val EXTRA_STREAM_ID = "stream_id"
    }
}
