package com.dispatch.reader.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.dispatch.reader.App
import com.dispatch.reader.R
import com.dispatch.reader.data.Stream
import com.dispatch.reader.feed.Sync
import com.dispatch.reader.ui.ArticleActivity
import com.dispatch.reader.ui.MainActivity
import com.dispatch.reader.util.Safely

/**
 * The home-screen widget: up to ten stories from one stream, mixed so that no
 * feed can take more than three of the slots.
 *
 * ## The PendingIntent rule that bites
 *
 * A collection widget needs **two different kinds** of PendingIntent, and they
 * need opposite mutability flags:
 *
 *  - the header buttons are ordinary intents and must be `FLAG_IMMUTABLE`,
 *    which API 31+ requires;
 *  - the row template passed to [RemoteViews.setPendingIntentTemplate] must be
 *    `FLAG_MUTABLE`, because the whole mechanism works by the launcher filling
 *    in each row's own extras. Marked immutable it does not throw — every row
 *    simply opens the same article.
 *
 * ## Why the widget reads the database directly
 *
 * There is no broadcast carrying article data. [WidgetService]'s factory runs
 * in this process, off the main thread, and queries the same [com.dispatch.reader.data.Repo]
 * the app uses. So "refresh the widget" is only ever
 * [AppWidgetManager.notifyAppWidgetViewDataChanged] — there is no second copy
 * of the data to keep in step.
 */
class NewsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        for (id in widgetIds) render(context, manager, id)
    }

    override fun onDeleted(context: Context, widgetIds: IntArray) {
        WidgetPrefs.forget(context, widgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        newOptions: android.os.Bundle?,
    ) {
        // A resize changes how many rows fit, not what they contain, but the
        // header's title can be elided differently, so re-render.
        render(context, manager, widgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                val widgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
                )
                refreshFeeds(context, widgetId)
            }
        }
    }

    /**
     * The refresh button.
     *
     * `goAsync()` is deliberately not used: a broadcast receiver's async window
     * is ten seconds and a hundred feeds do not finish in ten seconds. The work
     * goes to the app's own executor instead, and the widget is told to
     * re-read when it is done. If the process is killed first, the next
     * scheduled sync picks it up — nothing is lost but the moment.
     */
    private fun refreshFeeds(context: Context, widgetId: Int) {
        val app = context.applicationContext as? App ?: return
        val manager = AppWidgetManager.getInstance(context)
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list)
        }
        app.io.execute {
            Safely.run { Sync.refreshAll(app, app.repo) }
            Safely.run { refreshAll(app) }
        }
    }

    companion object {

        const val ACTION_REFRESH = "com.dispatch.reader.widget.REFRESH"

        /** Tell every widget to re-read the database. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NewsWidgetProvider::class.java))
            if (ids.isEmpty()) return
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            for (id in ids) render(context, manager, id)
        }

        /** Re-render one widget's chrome, and re-read its rows. */
        fun refreshOne(context: Context, widgetId: Int) {
            val manager = AppWidgetManager.getInstance(context)
            render(context, manager, widgetId)
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list)
        }

        fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_news)
            views.setTextViewText(R.id.widget_title, streamName(context, widgetId))

            val adapterIntent = Intent(context, WidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                // The data URI is what makes this intent distinct per widget.
                // Without it two widgets share one factory and both show the
                // first one's stream — the extras are not part of an Intent's
                // identity for this purpose.
                data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_list, adapterIntent)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)

            views.setOnClickPendingIntent(R.id.widget_settings, configIntent(context, widgetId))
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(context, widgetId))
            views.setOnClickPendingIntent(R.id.widget_title, openAppIntent(context, widgetId))
            views.setPendingIntentTemplate(R.id.widget_list, articleTemplate(context, widgetId))

            manager.updateAppWidget(widgetId, views)
        }

        private fun streamName(context: Context, widgetId: Int): String {
            val streamId = WidgetPrefs.streamId(context, widgetId)
            if (streamId == Stream.ALL_ID) return context.getString(R.string.stream_all)
            if (streamId == Stream.SAVED_ID) return context.getString(R.string.stream_saved)
            val repo = (context.applicationContext as? App)?.repo ?: return context.getString(R.string.app_name)
            return Safely.call({ repo.stream(streamId)?.name }, null)
                ?: context.getString(R.string.stream_all)
        }

        /** Immutable: nothing fills anything in. */
        private fun configIntent(context: Context, widgetId: Int): PendingIntent {
            val intent = Intent(context, WidgetConfigActivity::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra(WidgetConfigActivity.EXTRA_RECONFIGURE, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                data = android.net.Uri.parse("dispatch://widget/$widgetId/config")
            }
            return PendingIntent.getActivity(
                context, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun refreshIntent(context: Context, widgetId: Int): PendingIntent {
            val intent = Intent(context, NewsWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = android.net.Uri.parse("dispatch://widget/$widgetId/refresh")
            }
            return PendingIntent.getBroadcast(
                context, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openAppIntent(context: Context, widgetId: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_STREAM_ID, WidgetPrefs.streamId(context, widgetId))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                data = android.net.Uri.parse("dispatch://widget/$widgetId/open")
            }
            return PendingIntent.getActivity(
                context, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * **Mutable on purpose.** The launcher fills in each row's article id;
         * an immutable template silently sends every row to the same article.
         */
        private fun articleTemplate(context: Context, widgetId: Int): PendingIntent {
            val intent = Intent(context, ArticleActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return PendingIntent.getActivity(
                context, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        }
    }
}
