package com.dispatch.reader.ui

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
import com.dispatch.reader.data.Category
import com.dispatch.reader.shell.Frame
import com.dispatch.reader.util.Safely
import com.dispatch.reader.widget.NewsWidgetProvider

/**
 * Categories: the labels that make a hundred feeds findable.
 *
 * They are the app's filing system and nothing more — not folders, not streams.
 * A feed carries one; a stream can ask for whole ones; the library filters by
 * them. The seven that shipped are marked as such but are not privileged: they
 * can be renamed, deleted, or joined by as many of the reader's own as they
 * like.
 *
 * Deleting one **keeps the feeds**. They become uncategorised, and any stream
 * that included the category loses that rule. Saying so on the confirmation is
 * the point of having a confirmation.
 */
class CategoriesActivity : AppCompatActivity() {

    private lateinit var frame: Frame
    private lateinit var empty: TextView
    private lateinit var adapter: Adapter

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
        frame.applyBarColour(ContextCompat.getColor(this, R.color.accent))

        findViewById<TextView>(R.id.screenTitle).setText(R.string.categories_title)
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.addButton).apply {
            visibility = View.VISIBLE
            contentDescription = getString(R.string.category_new)
            setOnClickListener { promptNew() }
        }
        findViewById<ImageButton>(R.id.overflowButton).visibility = View.GONE

        empty = findViewById(R.id.emptyView)
        empty.setText(R.string.categories_empty)
        adapter = Adapter(::showMenu)
        findViewById<RecyclerView>(R.id.rows).apply {
            layoutManager = LinearLayoutManager(this@CategoriesActivity)
            adapter = this@CategoriesActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        frame.applyBarColour(ContextCompat.getColor(this, R.color.accent))
        reload()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        frame.applyBarColour(ContextCompat.getColor(this, R.color.accent))
        frame.requestInsets()
    }

    private fun reload() {
        app.io.execute {
            val list = Safely.call({ app.repo.categories() }, emptyList())
            runOnUiThread {
                adapter.submit(list)
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun promptNew() {
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
                    runOnUiThread { reload() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMenu(category: Category) {
        val options = arrayOf(
            getString(R.string.action_rename),
            getString(R.string.action_delete_category),
        )
        AlertDialog.Builder(this)
            .setTitle(category.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> rename(category)
                    1 -> confirmDelete(category)
                }
            }
            .show()
    }

    private fun rename(category: Category) {
        val field = EditText(this).apply {
            setText(category.name)
            setSingleLine()
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_rename)
            .setView(field)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                app.io.execute {
                    // Three tables in one transaction: the feeds filed under it
                    // and every stream rule that names it follow the rename.
                    Safely.run { app.repo.renameCategory(category.name, name) }
                    runOnUiThread {
                        reload()
                        NewsWidgetProvider.refreshAll(this@CategoriesActivity)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(category: Category) {
        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete_category)
            .setMessage(getString(R.string.delete_category_confirm, category.name, category.feedCount))
            .setPositiveButton(R.string.action_delete_category) { _, _ ->
                app.io.execute {
                    Safely.run { app.repo.deleteCategory(category.name) }
                    runOnUiThread {
                        reload()
                        NewsWidgetProvider.refreshAll(this@CategoriesActivity)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class Adapter(val onClick: (Category) -> Unit) : RecyclerView.Adapter<Adapter.Holder>() {

        private var items: List<Category> = emptyList()

        fun submit(list: List<Category>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.row_feed, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val category = items.getOrNull(position) ?: return
            val context = holder.itemView.context
            holder.title.text = category.name
            val feeds = context.resources.getQuantityString(
                R.plurals.category_feed_count, category.feedCount, category.feedCount
            )
            holder.status.text = if (category.builtin) {
                context.getString(R.string.category_builtin, feeds)
            } else {
                feeds
            }
            holder.itemView.setOnClickListener { onClick(category) }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.feedTitle)
            val status: TextView = view.findViewById(R.id.feedStatus)
        }
    }
}
