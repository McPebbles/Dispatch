package com.dispatch.reader.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dispatch.reader.App
import com.dispatch.reader.R
import com.dispatch.reader.data.Stream
import com.dispatch.reader.shell.Frame
import com.dispatch.reader.util.Safely
import com.dispatch.reader.widget.NewsWidgetProvider
import com.dispatch.reader.widget.WidgetPrefs

/**
 * Streams: named subsets of the feed list.
 *
 * "All feeds" and "Saved" are not in this list and cannot be edited — they are
 * queries, not rows (see [Stream.ALL_ID]). Everything here is a real stream the
 * reader made or that the first run created from the bundled categories, and
 * all of them can be renamed, re-membered or deleted.
 */
class StreamsActivity : AppCompatActivity() {

    private lateinit var frame: Frame
    private lateinit var adapter: Adapter
    private lateinit var empty: TextView
    private val app: App get() = application as App

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
        frame.applyBarColour(ContextCompat.getColor(this, R.color.top_bar))

        findViewById<TextView>(R.id.screenTitle).setText(R.string.streams_title)
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.addButton).apply {
            visibility = View.VISIBLE
            contentDescription = getString(R.string.new_stream)
            setOnClickListener {
                startActivity(Intent(this@StreamsActivity, StreamEditActivity::class.java))
            }
        }
        findViewById<ImageButton>(R.id.overflowButton).visibility = View.GONE

        empty = findViewById(R.id.emptyView)
        empty.setText(R.string.streams_empty)
        adapter = Adapter(::showMenu)
        findViewById<RecyclerView>(R.id.rows).apply {
            layoutManager = LinearLayoutManager(this@StreamsActivity)
            adapter = this@StreamsActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        frame.applyBarColour(ContextCompat.getColor(this, R.color.top_bar))
        reload()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        frame.applyBarColour(ContextCompat.getColor(this, R.color.top_bar))
        frame.requestInsets()
    }

    private fun reload() {
        app.io.execute {
            val streams = Safely.call({ app.repo.streams() }, emptyList())
            runOnUiThread {
                adapter.submit(streams)
                empty.visibility = if (streams.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showMenu(stream: Stream) {
        val options = arrayOf(
            getString(R.string.action_edit_stream),
            getString(R.string.action_rename),
            getString(R.string.action_delete_stream),
        )
        AlertDialog.Builder(this)
            .setTitle(stream.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, StreamEditActivity::class.java)
                            .putExtra(StreamEditActivity.EXTRA_STREAM_ID, stream.id)
                    )
                    1 -> rename(stream)
                    2 -> confirmDelete(stream)
                }
            }
            .show()
    }

    private fun rename(stream: Stream) {
        val field = EditText(this).apply {
            setText(stream.name)
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
                        Safely.run { app.repo.renameStream(stream.id, name) }
                        runOnUiThread {
                            reload()
                            // A widget shows the stream's name in its header.
                            NewsWidgetProvider.refreshAll(this@StreamsActivity)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Deleting a stream removes the grouping, never the feeds or their
     * articles — `stream_feeds` cascades, `feeds` does not. The confirmation
     * says so, because "delete stream" reads like it might take a hundred
     * subscriptions with it.
     */
    private fun confirmDelete(stream: Stream) {
        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete_stream)
            .setMessage(getString(R.string.delete_stream_confirm, stream.name))
            .setPositiveButton(R.string.action_delete_stream) { _, _ ->
                app.io.execute {
                    Safely.run { app.repo.deleteStream(stream.id) }
                    val live = Safely.call({ app.repo.streams().map { it.id }.toSet() }, emptySet())
                    runOnUiThread {
                        // A widget pointed at a stream that no longer exists
                        // would show nothing, with no way for the reader to
                        // find out why. Move any orphan back to "All feeds".
                        Safely.run {
                            val manager = android.appwidget.AppWidgetManager.getInstance(this)
                            val ids = manager.getAppWidgetIds(
                                android.content.ComponentName(this, NewsWidgetProvider::class.java)
                            )
                            WidgetPrefs.repoint(this, ids, live)
                            NewsWidgetProvider.refreshAll(this)
                        }
                        reload()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class Adapter(val onClick: (Stream) -> Unit) : RecyclerView.Adapter<Adapter.Holder>() {

        private var items: List<Stream> = emptyList()

        fun submit(streams: List<Stream>) {
            items = streams
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.row_feed, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val stream = items.getOrNull(position) ?: return
            val context = holder.itemView.context
            holder.title.text = stream.name
            // What is in it and how often it refreshes, because those are the
            // two things a reader wants to check without opening it.
            val members = when {
                stream.categoryCount == 0 -> context.resources.getQuantityString(
                    R.plurals.stream_feed_count, stream.feedCount, stream.feedCount
                )
                stream.feedCount == 0 -> context.resources.getQuantityString(
                    R.plurals.stream_category_count, stream.categoryCount, stream.categoryCount
                )
                else -> context.getString(
                    R.string.stream_members_both, stream.feedCount, stream.categoryCount
                )
            }
            holder.status.text = context.getString(
                R.string.stream_status_line, members, cadenceLabel(context, stream.refreshMinutes)
            )
            holder.itemView.setOnClickListener { onClick(stream) }
        }

        /** The cadence spinner's own label, so the two screens cannot disagree. */
        private fun cadenceLabel(context: android.content.Context, minutes: Int): String {
            val values = context.resources.getStringArray(R.array.stream_cadence_values)
            val labels = context.resources.getStringArray(R.array.stream_cadence_entries)
            val index = values.indexOf(minutes.toString())
            return if (index >= 0) labels[index] else labels[0]
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.feedTitle)
            val status: TextView = view.findViewById(R.id.feedStatus)
        }
    }
}
