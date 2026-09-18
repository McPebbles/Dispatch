package com.dispatch.reader.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.dispatch.reader.App
import com.dispatch.reader.R
import com.dispatch.reader.data.Article
import com.dispatch.reader.ui.ArticleActivity
import com.dispatch.reader.util.Safely
import com.dispatch.reader.util.Time

/**
 * The widget's rows.
 *
 * [RemoteViewsFactory.onDataSetChanged] and [RemoteViewsFactory.getViewAt] both
 * run on a binder thread in this app's process, which is what makes it safe —
 * and necessary — to query the database and decode a bitmap synchronously here.
 * There is no asynchronous path: a `RemoteViews` is a description of a view
 * that will be inflated in the *launcher's* process, so a bitmap has to be in
 * hand before the row is returned.
 */
class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        return Factory(applicationContext, widgetId)
    }

    private class Factory(
        private val context: Context,
        private val widgetId: Int,
    ) : RemoteViewsFactory {

        private var rows: List<Article> = emptyList()
        private var showByline = WidgetPrefs.BYLINE_DEFAULT

        override fun onCreate() = Unit

        /**
         * Re-read the stream.
         *
         * The over-fetch is deliberate: [WidgetMix] cannot enforce a per-feed
         * cap on a list that has already been cut to ten, so the query asks for
         * a couple of hundred candidates and the mix does the choosing.
         */
        override fun onDataSetChanged() {
            val app = context.applicationContext as? App
            showByline = WidgetPrefs.byline(context, widgetId)
            rows = Safely.call({
                val repo = app?.repo ?: return@call emptyList()
                val streamId = WidgetPrefs.streamId(context, widgetId)
                val candidates = repo.widgetCandidates(streamId, CANDIDATES)
                WidgetMix.pick(WidgetMix.dedupe(candidates))
            }, emptyList())
        }

        override fun onDestroy() {
            rows = emptyList()
        }

        override fun getCount(): Int = rows.size

        override fun getViewAt(position: Int): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_row)
            val article = rows.getOrNull(position) ?: return row

            row.setTextViewText(R.id.row_title, article.title)

            // The byline line carries the source as well as the author, because
            // in a mixed stream "which paper is this" is the more useful of the
            // two and the author is often absent — BBC's feeds carry no author
            // at all. Ellipsising is the layout's job (singleLine + marquee is
            // not available in RemoteViews, so it is ellipsize="end").
            val byline = byline(article)
            if (showByline && byline.isNotEmpty()) {
                row.setTextViewText(R.id.row_byline, byline)
                row.setViewVisibility(R.id.row_byline, View.VISIBLE)
            } else {
                row.setViewVisibility(R.id.row_byline, View.GONE)
            }

            row.setTextViewText(R.id.row_meta, Time.shortAgo(context, article.publishedAt))

            val bitmap = Safely.call({
                com.dispatch.reader.img.ImageStore.getBlocking(article.imageUrl, THUMB_PX)
            }, null)
            if (bitmap != null) {
                row.setImageViewBitmap(R.id.row_image, bitmap)
                row.setViewVisibility(R.id.row_image, View.VISIBLE)
            } else {
                // GONE, not a placeholder: a 4x4 tile has no room to spend on a
                // grey rectangle, and the title simply takes the width.
                row.setViewVisibility(R.id.row_image, View.GONE)
            }

            // What the launcher fills into the provider's mutable template.
            row.setOnClickFillInIntent(
                R.id.row_root,
                Intent().putExtra(ArticleActivity.EXTRA_ARTICLE_ID, article.id),
            )
            return row
        }

        private fun byline(article: Article): String {
            val source = article.feedTitle.trim()
            val author = article.author?.trim().orEmpty()
            return when {
                author.isNotEmpty() && source.isNotEmpty() -> "$author · $source"
                author.isNotEmpty() -> author
                else -> source
            }
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long = rows.getOrNull(position)?.id ?: position.toLong()

        override fun hasStableIds(): Boolean = true

        private companion object {
            const val CANDIDATES = 200

            /**
             * Thumbnail size in pixels.
             *
             * A widget row's image is 56dp; 168px covers that at xxhdpi. Every
             * bitmap here crosses a binder transaction into the launcher, and
             * the whole `RemoteViews` for a widget must fit in a 1MB-ish
             * transaction — ten full-resolution pictures would not, and the
             * widget would come back blank with an obscure log line.
             */
            const val THUMB_PX = 168
        }
    }
}
