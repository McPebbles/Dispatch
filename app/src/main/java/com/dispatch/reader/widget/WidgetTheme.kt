package com.dispatch.reader.widget

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import com.dispatch.reader.R
import com.dispatch.reader.util.Prefs

/**
 * A widget's colours, decided by this app rather than by the launcher.
 *
 * ## Why the widget cannot use `values-night/`
 *
 * A widget's views are inflated in the **launcher's** process, against the
 * **launcher's** configuration. So `@color/widget_bg` with a `values-night`
 * override follows the *system* dark-mode switch, on the launcher's schedule,
 * and knows nothing about the app's own theme setting: a reader who puts the
 * app in dark mode while the system is light gets a white widget, and a system
 * switch reaches the widget only when something happens to re-render it.
 *
 * The fix is to stop asking the resource system: every colour is resolved here
 * from a plain, qualifier-free colour resource and pushed into the RemoteViews
 * explicitly (`setTextColor`, `setInt(..., "setBackgroundResource", …)`,
 * `setInt(..., "setColorFilter", …)`). The launcher then has nothing to decide.
 *
 * ## The three modes
 *
 * [FOLLOW] tracks the app — which is itself either a fixed choice or the
 * system's, exactly as the app resolves it — and [LIGHT] and [DARK] are fixed.
 * A widget placed on the home screen starts on whichever of light or dark the
 * app is showing at that moment, not on [FOLLOW]: the reader picked a look when
 * they placed it, and a widget that changes under them at midnight is a
 * surprise. [FOLLOW] is there for the reader who wants that.
 */
object WidgetTheme {

    const val FOLLOW = "follow"
    const val LIGHT = "light"
    const val DARK = "dark"

    /** One resolved appearance. Everything the widget draws comes from here. */
    data class Palette(
        /** A rounded-rectangle drawable, so the widget keeps its corners. */
        val backgroundRes: Int,
        val title: Int,
        val meta: Int,
        val divider: Int,
    ) {
        /** Icons read as text, not as artwork, so they take the title colour. */
        val icon: Int get() = title
    }

    /**
     * What the app is showing **right now**, as [LIGHT] or [DARK].
     *
     * Never returns [FOLLOW]: this is the question "what does the app look like
     * at this moment", which is what a new widget copies and what [FOLLOW]
     * resolves to.
     */
    fun currentAppMode(context: Context): String = when (Prefs.theme(context)) {
        Prefs.THEME_LIGHT -> LIGHT
        Prefs.THEME_DARK -> DARK
        else -> if (systemIsDark(context)) DARK else LIGHT
    }

    private fun systemIsDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun paletteFor(context: Context, mode: String): Palette {
        val dark = when (mode) {
            LIGHT -> false
            DARK -> true
            else -> currentAppMode(context) == DARK
        }
        return if (dark) dark(context) else light(context)
    }

    private fun light(context: Context) = Palette(
        backgroundRes = R.drawable.widget_background_light,
        title = ContextCompat.getColor(context, R.color.widget_light_title),
        meta = ContextCompat.getColor(context, R.color.widget_light_meta),
        divider = ContextCompat.getColor(context, R.color.widget_light_divider),
    )

    private fun dark(context: Context) = Palette(
        backgroundRes = R.drawable.widget_background_dark,
        title = ContextCompat.getColor(context, R.color.widget_dark_title),
        meta = ContextCompat.getColor(context, R.color.widget_dark_meta),
        divider = ContextCompat.getColor(context, R.color.widget_dark_divider),
    )

    /** The three choices, in the order the configuration screen shows them. */
    val MODES: List<String> = listOf(FOLLOW, LIGHT, DARK)

    fun labelFor(context: Context, mode: String): String = when (mode) {
        LIGHT -> context.getString(R.string.widget_theme_light)
        DARK -> context.getString(R.string.widget_theme_dark)
        else -> context.getString(R.string.widget_theme_follow)
    }
}
