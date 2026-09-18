package com.dispatch.reader.widget

import android.content.Context
import com.dispatch.reader.data.Stream

/**
 * One widget's settings, keyed by its `appWidgetId`.
 *
 * Deliberately *not* in the app's default SharedPreferences: these are not the
 * reader's preferences, they belong to an object on the home screen that can be
 * created and destroyed many times over, and mixing them in would leave the
 * settings file accumulating `stream_37` keys forever. A separate file also
 * makes [forget] — called from the provider's `onDeleted` — obviously complete.
 */
object WidgetPrefs {

    private const val FILE = "widget_config"
    private const val KEY_STREAM = "stream_"
    private const val KEY_BYLINE = "byline_"

    /**
     * Whether a widget shows the byline.
     *
     * Off by default. At 4×4 a row has space for a thumbnail and two lines of
     * headline; the byline is the third thing, and a reader who wants it can
     * say so from the gear.
     */
    const val BYLINE_DEFAULT = false

    private fun file(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun streamId(context: Context, widgetId: Int): Long =
        file(context).getLong(KEY_STREAM + widgetId, Stream.ALL_ID)

    fun setStreamId(context: Context, widgetId: Int, streamId: Long) {
        file(context).edit().putLong(KEY_STREAM + widgetId, streamId).apply()
    }

    fun byline(context: Context, widgetId: Int): Boolean =
        file(context).getBoolean(KEY_BYLINE + widgetId, BYLINE_DEFAULT)

    fun setByline(context: Context, widgetId: Int, show: Boolean) {
        file(context).edit().putBoolean(KEY_BYLINE + widgetId, show).apply()
    }

    fun isConfigured(context: Context, widgetId: Int): Boolean =
        file(context).contains(KEY_STREAM + widgetId)

    /** Called when a widget is removed from the home screen. */
    fun forget(context: Context, widgetIds: IntArray) {
        val editor = file(context).edit()
        for (id in widgetIds) {
            editor.remove(KEY_STREAM + id)
            editor.remove(KEY_BYLINE + id)
        }
        editor.apply()
    }

    /**
     * Widgets pointing at a stream that has been deleted.
     *
     * Deleting a stream in the app must not leave a widget showing nothing with
     * no explanation, so the provider moves any orphan back to "All feeds" and
     * the widget's header says which stream it is on.
     */
    fun repoint(context: Context, widgetIds: IntArray, liveStreamIds: Set<Long>) {
        val prefs = file(context)
        val editor = prefs.edit()
        var changed = false
        for (id in widgetIds) {
            val stream = prefs.getLong(KEY_STREAM + id, Stream.ALL_ID)
            if (stream != Stream.ALL_ID && stream != Stream.SAVED_ID && stream !in liveStreamIds) {
                editor.putLong(KEY_STREAM + id, Stream.ALL_ID)
                changed = true
            }
        }
        if (changed) editor.apply()
    }
}
