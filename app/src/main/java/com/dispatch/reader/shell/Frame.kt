package com.dispatch.reader.shell

import android.app.Activity
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * The window frame. One implementation, used by **every** activity.
 *
 * This is a port of Tombot's `shell/Frame.kt` (by way of Threadbare), and the
 * standing rule it came from is in the project as `claude/frame-rule.md`. The short version: at
 * targetSdk 36 edge-to-edge is not optional, the system stops reserving the
 * status-bar strip, and `android:statusBarColor` is ignored. An activity that
 * does not paint that strip and inset its own content draws underneath it, and
 * on a scrolling screen the content rides up over the app's own header.
 *
 * The failure that produced the rule is worth restating, because it is the
 * likely failure here too: in Tombot the main screen was worked out carefully
 * and the *settings* screen was left on a stock ActionBar theme with no
 * edge-to-edge and no inset handling. One screen right, one wrong, and the
 * review that "fixed the frame" never opened the broken one.
 *
 * Dispatch has **nine** activities, which makes this the app in the suite most
 * exposed to that failure. Every one of them uses this class, and
 * `tools/verify_frame.py` walks the manifest and fails if any does not.
 *
 * Four requirements, all four needed per activity:
 *
 *  1. a NoActionBar theme with transparent system bars and
 *     `enforceStatusBarContrast` / `enforceNavigationBarContrast` false — in
 *     the night theme too;
 *  2. `enableEdgeToEdge()` in onCreate, before `setContentView`;
 *  3. a vertical layout: a full-bleed opaque bar wrapper, then the scrolling
 *     content as a *later sibling* with a layout weight;
 *  4. three insets on three views, wired here.
 */
class Frame(
    private val activity: Activity,
    /** Full-bleed wrapper whose background reaches the top of the screen. */
    private val barWrapper: View,
    /** The row of controls inside the wrapper. Takes the horizontal insets. */
    private val barRow: View,
    /** The scrolling content, a later sibling of [barWrapper]. */
    private val content: View,
) {

    /**
     * Wire the insets.
     *
     * Deliberately not `fitsSystemWindows`: that would inset the bar wrapper
     * itself, and its background would then stop short of the screen edge,
     * leaving an unpainted strip — which is the bug this whole class exists to
     * prevent.
     */
    fun install() {
        ViewCompat.setOnApplyWindowInsetsListener(barWrapper) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // Top inset becomes padding *inside* the wrapper, so the wrapper's
            // background still paints the status-bar strip.
            v.updatePadding(top = bars.top)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(barRow) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // A cutout moves what you read, not the bar's background.
            v.updatePadding(left = bars.left, right = bars.right)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = maxOf(bars.bottom, ime.bottom),
            )
            insets
        }

        requestInsets()
    }

    /**
     * Inset a navigation drawer's panel.
     *
     * A drawer is not part of the vertical stack: it is an overlay that reaches
     * the top of the screen, so it has to take the status-bar inset itself or
     * its first row sits under the clock. The left inset matters too — on a
     * device with a cutout in landscape the panel is exactly where the cutout
     * is.
     *
     * Kept here rather than in the activity so that the frame rule has one
     * implementation for every kind of surface, and so `tools/verify_frame.py`
     * can require it of any layout that has a drawer.
     */
    fun insetPanel(panel: View) {
        ViewCompat.setOnApplyWindowInsetsListener(panel) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(top = bars.top, left = bars.left, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(panel)
    }

    fun requestInsets() {
        ViewCompat.requestApplyInsets(barWrapper)
        ViewCompat.requestApplyInsets(barRow)
        ViewCompat.requestApplyInsets(content)
    }

    /**
     * Paint the bar and set the system icon contrast from that colour's
     * luminance — not from the night-mode flag.
     *
     * This is what lets one screen wear a light bar and another a brand-orange
     * one with nothing hard-coded, and it is why the settings screen can carry
     * a coloured header without a special case.
     *
     * Call from onCreate, onResume and onConfigurationChanged: any activity
     * that declares `uiMode` in configChanges is not recreated on a day/night
     * switch, so a colour resolved once at inflate stays the daytime colour
     * until the next cold start.
     */
    fun applyBarColour(colour: Int) {
        barWrapper.setBackgroundColor(colour)

        val window = activity.window ?: return
        val controller = WindowCompat.getInsetsController(window, barWrapper)
        val light = isLight(colour)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }

    companion object {
        /**
         * Luminance threshold for "dark icons on this". 0.5 is the usual line;
         * Dispatch's ink navy (#16243B) lands at about 0.02, so a screen that
         * wears the accent as its bar correctly gets light icons.
         */
        fun isLight(colour: Int): Boolean =
            ColorUtils.calculateLuminance(colour or OPAQUE) > 0.5

        /** Force full alpha before measuring, so a translucent bar is judged on its colour. */
        private const val OPAQUE: Int = -0x1000000 // 0xFF000000
    }
}
