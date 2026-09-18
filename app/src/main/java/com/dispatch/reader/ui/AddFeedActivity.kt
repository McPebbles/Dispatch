package com.dispatch.reader.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dispatch.reader.App
import com.dispatch.reader.R
import com.dispatch.reader.data.Opml
import com.dispatch.reader.data.Seed
import com.dispatch.reader.feed.Sync
import com.dispatch.reader.shell.Frame
import com.dispatch.reader.util.Safely

/**
 * Add a feed: paste an address, see what was found, name it, file it.
 *
 * In 1.0.x this was a one-field dialog behind two menus, and the reader's
 * verdict was that the app had no way to add a feed at all. It is a screen now,
 * reachable from the drawer in one tap, and it does three things that dialog
 * could not:
 *
 *  - **finds** the feed from a site address, and says what it found before
 *    anything is saved;
 *  - lets the reader **rename** it — a publisher's own `<title>` is often
 *    "Feed" or the site's marketing tagline;
 *  - lets them **file it under a category**, including one they invent here,
 *    which is what makes the library searchable later and what lets a stream
 *    say "everything tagged Local".
 */
class AddFeedActivity : AppCompatActivity() {

    private lateinit var frame: Frame
    private lateinit var urlField: EditText
    private lateinit var titleField: EditText
    private lateinit var statusText: TextView
    private lateinit var resultBlock: View
    private lateinit var categorySpinner: Spinner
    private lateinit var findButton: Button
    private lateinit var saveButton: Button

    private var found: Sync.Probe.Found? = null
    private var categories: List<String> = emptyList()
    private var chosenCategory: String? = null

    private val app: App get() = application as App

    private val importer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { importOpml(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_feed)

        frame = Frame(
            activity = this,
            barWrapper = findViewById(R.id.topBarWrapper),
            barRow = findViewById(R.id.topBarRow),
            content = findViewById(R.id.content),
        )
        frame.install()
        frame.applyBarColour(ContextCompat.getColor(this, R.color.accent))

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        urlField = findViewById(R.id.urlField)
        titleField = findViewById(R.id.titleField)
        statusText = findViewById(R.id.statusText)
        resultBlock = findViewById(R.id.resultBlock)
        categorySpinner = findViewById(R.id.categorySpinner)
        findButton = findViewById(R.id.findButton)
        saveButton = findViewById(R.id.saveButton)

        findButton.setOnClickListener { find(urlField.text.toString()) }
        saveButton.setOnClickListener { save() }
        findViewById<Button>(R.id.importButton).setOnClickListener {
            importer.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf("text/xml", "application/xml", "text/x-opml", "*/*"),
                    )
            )
        }

        // A URL shared into the app from a browser arrives here ready to go.
        intent?.let { incoming ->
            val shared = incoming.getStringExtra(Intent.EXTRA_TEXT) ?: incoming.dataString
            if (!shared.isNullOrBlank()) {
                urlField.setText(shared.trim())
                find(shared)
            }
        }

        loadCategories()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        frame.applyBarColour(ContextCompat.getColor(this, R.color.accent))
        frame.requestInsets()
    }

    // ------------------------------------------------------------- finding

    private fun find(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        found = null
        resultBlock.visibility = View.GONE
        findButton.isEnabled = false
        status(getString(R.string.add_feed_looking))

        app.io.execute {
            val outcome = Safely.call({ Sync.probe(app.repo, text) }, null)
            runOnUiThread {
                findButton.isEnabled = true
                when (outcome) {
                    is Sync.Probe.Found -> showFound(outcome)
                    is Sync.Probe.Choices -> chooseFeed(outcome.urls)
                    is Sync.Probe.Already -> status(getString(R.string.add_feed_already, outcome.title))
                    is Sync.Probe.Failed -> status(outcome.message)
                    null -> status(getString(R.string.add_feed_failed))
                }
            }
        }
    }

    private fun showFound(result: Sync.Probe.Found) {
        found = result
        titleField.setText(result.title)
        status(
            resources.getQuantityString(
                R.plurals.add_feed_found, result.items.size, result.title, result.items.size
            )
        )
        resultBlock.visibility = View.VISIBLE
    }

    private fun chooseFeed(urls: List<String>) {
        status(getString(R.string.add_feed_which))
        AlertDialog.Builder(this)
            .setTitle(R.string.add_feed_which)
            .setItems(urls.toTypedArray()) { _, which ->
                urlField.setText(urls[which])
                find(urls[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun status(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------- categories

    private fun loadCategories(select: String? = null) {
        app.io.execute {
            val names = Safely.call({ app.repo.categoryNames() }, emptyList())
            runOnUiThread {
                categories = names
                val labels = ArrayList<String>()
                labels.add(getString(R.string.category_none))
                labels.addAll(names)
                labels.add(getString(R.string.category_new))
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                categorySpinner.adapter = adapter

                val wanted = select ?: chosenCategory
                val index = names.indexOf(wanted)
                categorySpinner.setSelection(if (index >= 0) index + 1 else 0)
                chosenCategory = if (index >= 0) wanted else null

                categorySpinner.onItemSelectedListener =
                    object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: android.widget.AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long,
                        ) {
                            when (position) {
                                0 -> chosenCategory = null
                                labels.size - 1 -> promptNewCategory()
                                else -> chosenCategory = categories.getOrNull(position - 1)
                            }
                        }

                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                    }
            }
        }
    }

    /**
     * Make a category from here.
     *
     * A reader filing their first local paper should not have to go somewhere
     * else to invent "Local" first — the category they want exists only in
     * their head until this moment.
     */
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
                if (name.isEmpty()) {
                    loadCategories()
                    return@setPositiveButton
                }
                app.io.execute {
                    Safely.run { app.repo.createCategory(name) }
                    runOnUiThread { loadCategories(select = name) }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> loadCategories() }
            .setOnCancelListener { loadCategories() }
            .show()
    }

    // -------------------------------------------------------------- saving

    private fun save() {
        val result = found ?: return
        val title = titleField.text.toString()
        val category = chosenCategory
        saveButton.isEnabled = false
        app.io.execute {
            val id = Safely.call({ Sync.save(app.repo, result, title, category) }, -1L)
            runOnUiThread {
                saveButton.isEnabled = true
                if (id > 0) {
                    Toast.makeText(
                        this,
                        getString(R.string.add_feed_added, title.ifBlank { result.title }, result.items.size),
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                } else {
                    status(getString(R.string.add_feed_failed))
                }
            }
        }
    }

    private fun importOpml(uri: Uri) {
        status(getString(R.string.import_running))
        app.io.execute {
            val xml = Safely.call({
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }, null)
            if (xml.isNullOrBlank()) {
                runOnUiThread { status(getString(R.string.import_failed)) }
                return@execute
            }
            // makeStreams = true: someone arriving from another reader built
            // those folders deliberately, and throwing the structure away would
            // be rude. Each folder becomes a category AND a stream that follows
            // it, so the stream keeps working as they add to the category.
            val added = Safely.call({ Seed.install(app.repo, Opml.parse(xml), makeStreams = true) }, 0)
            runOnUiThread {
                status(resources.getQuantityString(R.plurals.import_added, added, added))
                loadCategories()
            }
        }
    }
}
