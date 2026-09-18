package com.dispatch.reader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.dispatch.reader.R

/**
 * The navigation drawer's contents.
 *
 * Everything a reader might want to reach is on one surface: All feeds, Saved,
 * every stream they have built by name and with its unread count, and then the
 * four things that create or manage what is above — new stream, add feed, the
 * feed library, categories — plus Settings.
 *
 * 1.0.x had all of this behind an overflow menu, and the result was a reader
 * who could not find how to add a feed or make a stream. A menu you have to
 * open to discover what is in it is not navigation.
 */
class DrawerAdapter(
    private val onStream: (Long) -> Unit,
    private val onAction: (Int) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        /** A stream, "All feeds" or "Saved stories". */
        data class Item(
            val streamId: Long,
            val label: String,
            val icon: Int,
            val count: Int,
            val selected: Boolean,
        ) : Row()

        data class Header(val label: String) : Row()

        data class Action(val action: Int, val label: String, val icon: Int) : Row()

        object Divider : Row()
    }

    private var rows: List<Row> = emptyList()

    fun submit(list: List<Row>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Divider -> TYPE_DIVIDER
        else -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.row_drawer_header, parent, false))
            TYPE_DIVIDER -> DividerHolder(inflater.inflate(R.layout.row_drawer_divider, parent, false))
            else -> ItemHolder(inflater.inflate(R.layout.row_drawer, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).label.text = row.label
            is Row.Divider -> Unit
            is Row.Item -> {
                val h = holder as ItemHolder
                val context = h.itemView.context
                h.label.text = row.label
                h.icon.setImageResource(row.icon)
                h.count.text = if (row.count > 0) row.count.toString() else ""
                // The current view is marked by weight and colour rather than a
                // background: a selected row with a filled background reads as a
                // button, and this is a list of places, not of actions.
                h.label.setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (row.selected) R.color.accent_text else R.color.body_text,
                    )
                )
                h.label.setTypeface(null, if (row.selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                h.itemView.setOnClickListener { onStream(row.streamId) }
            }
            is Row.Action -> {
                val h = holder as ItemHolder
                h.label.text = row.label
                h.icon.setImageResource(row.icon)
                h.count.text = ""
                h.label.setTextColor(ContextCompat.getColor(h.itemView.context, R.color.body_text))
                h.label.setTypeface(null, android.graphics.Typeface.NORMAL)
                h.itemView.setOnClickListener { onAction(row.action) }
            }
        }
    }

    class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.drawerIcon)
        val label: TextView = view.findViewById(R.id.drawerLabel)
        val count: TextView = view.findViewById(R.id.drawerCount)
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.drawerHeaderLabel)
    }

    class DividerHolder(view: View) : RecyclerView.ViewHolder(view)

    companion object {
        private const val TYPE_ITEM = 0
        private const val TYPE_HEADER = 1
        private const val TYPE_DIVIDER = 2

        const val ACTION_NEW_STREAM = 1
        const val ACTION_ADD_FEED = 2
        const val ACTION_LIBRARY = 3
        const val ACTION_CATEGORIES = 4
        const val ACTION_SETTINGS = 5
    }
}
