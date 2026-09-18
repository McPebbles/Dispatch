package com.dispatch.reader.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dispatch.reader.R
import com.dispatch.reader.data.Article
import com.dispatch.reader.img.ImageStore
import com.dispatch.reader.util.Time

/**
 * The article list.
 *
 * Read state is carried by weight and opacity rather than by a badge: an unread
 * headline is medium-weight and full strength, a read one is regular and
 * dimmed. That keeps a list of a hundred stories scannable without adding a
 * column of dots down the side.
 */
class ArticleAdapter(
    private val onClick: (Article) -> Unit,
    private val onLongClick: (Article) -> Unit,
) : RecyclerView.Adapter<ArticleAdapter.Holder>() {

    private var items: List<Article> = emptyList()

    fun submit(list: List<Article>) {
        items = list
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): Article? = items.getOrNull(position)

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.row_article, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val article = items.getOrNull(position) ?: return
        val context = holder.itemView.context

        holder.title.text = article.title
        holder.title.setTypeface(null, if (article.read) Typeface.NORMAL else Typeface.BOLD)
        holder.title.alpha = if (article.read) 0.62f else 1f

        val summary = article.summary?.takeIf { it.isNotBlank() }
        holder.summary.text = summary
        holder.summary.visibility = if (summary == null) View.GONE else View.VISIBLE

        val meta = StringBuilder(article.feedTitle)
        val age = Time.relative(article.publishedAt)
        if (age.isNotEmpty()) meta.append("  ·  ").append(age)
        article.author?.takeIf { it.isNotBlank() }?.let { meta.append("  ·  ").append(it) }
        holder.meta.text = meta

        holder.saved.visibility = if (article.saved) View.VISIBLE else View.GONE

        // The image view is hidden, not placeheld: a row with no picture should
        // give its width to the headline rather than to a grey box.
        ImageStore.load(holder.image, article.imageUrl, THUMB_PX) {
            holder.image.visibility = View.GONE
        }
        holder.image.visibility =
            if (article.imageUrl.isNullOrBlank() || !ImageStore.enabled(context)) View.GONE else View.VISIBLE

        holder.itemView.setOnClickListener { onClick(article) }
        holder.itemView.setOnLongClickListener {
            onLongClick(article)
            true
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.articleTitle)
        val summary: TextView = view.findViewById(R.id.articleSummary)
        val meta: TextView = view.findViewById(R.id.articleMeta)
        val image: ImageView = view.findViewById(R.id.articleImage)
        val saved: ImageView = view.findViewById(R.id.articleSaved)
    }

    private companion object {
        /** 88dp at xxhdpi. The row's image is 88dp square. */
        const val THUMB_PX = 264
    }
}
