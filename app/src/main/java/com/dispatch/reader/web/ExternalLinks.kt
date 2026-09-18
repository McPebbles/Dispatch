package com.dispatch.reader.web

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.dispatch.reader.R
import com.dispatch.reader.util.Prefs

/**
 * Handing an article's link to a browser.
 *
 * Every link in this app belongs to a publisher, so there is exactly one policy
 * and every screen uses it: the article page, the list's long-press menu, the
 * widget's overflow and the feed list's "open site".
 *
 * The policy is Threadbare's, ported with its reasoning intact:
 *
 *  - the link goes to the browser the reader chose for this app, which may not
 *    be their system default;
 *  - a browser they chose and have since uninstalled is announced once, because
 *    that is a silent change of behaviour and, if the browser was private by
 *    design, a silent privacy change;
 *  - a browser that is private throughout is never asked about — there is
 *    nothing to request and nothing to opt out of;
 *  - **a private launch never silently becomes an ordinary one.** A reader who
 *    asked for private and got a normal tab is worse off than one who was told
 *    it was unavailable, because they will read as though they are protected.
 *    See [PrivateTabs] for why Chromium — Vanadium included — cannot honour it.
 */
object ExternalLinks {

    fun open(activity: Activity, url: String) {
        val chosen = BrowserLauncher.chosen(activity)
        chosen.stalePackage?.let {
            toast(activity, activity.getString(R.string.browser_gone, it))
        }

        if (BrowserChoice.isInherentlyPrivate(chosen.browser)) {
            openNormally(activity, url, chosen.browser?.packageName)
            return
        }

        when (Prefs.privateTabMode(activity)) {
            Prefs.PRIVATE_NEVER -> openNormally(activity, url, chosen.browser?.packageName)
            Prefs.PRIVATE_ALWAYS -> openPrivatelyOrExplain(activity, url, chosen)
            else -> ask(activity, url, chosen)
        }
    }

    /** Copy, with the clip marked sensitive. */
    fun copy(activity: Activity, url: String) {
        val done = BrowserLauncher.copyToClipboard(activity, url)
        toast(activity, activity.getString(if (done) R.string.link_copied else R.string.link_copy_failed))
    }

    /**
     * @param packageName the browser chosen for this app, or null for the
     *   system's own choice. A chosen browser that fails to start — uninstalled
     *   since the picker, or disabled — is retried without the package rather
     *   than leaving the link nowhere.
     */
    private fun openNormally(activity: Activity, url: String, packageName: String? = null) {
        if (BrowserLauncher.openNormally(activity, url, packageName)) return
        if (packageName != null && BrowserLauncher.openNormally(activity, url, null)) return
        toast(activity, activity.getString(R.string.no_browser))
    }

    private fun openPrivatelyOrExplain(activity: Activity, url: String, chosen: BrowserChoice.Resolution) {
        val recipe = BrowserLauncher.privateOption(activity)
        if (recipe != null && BrowserLauncher.openPrivately(activity, url, recipe)) return
        showDialog(activity, url, chosen, recipe = null, note = noPrivateNote(activity, chosen))
    }

    private fun ask(activity: Activity, url: String, chosen: BrowserChoice.Resolution) {
        val recipe = BrowserLauncher.privateOption(activity)
        showDialog(
            activity, url, chosen, recipe,
            note = if (recipe == null) noPrivateNote(activity, chosen) else null,
        )
    }

    /**
     * Why private is unavailable, naming the chosen browser when there is one.
     * "No browser will do this" and "the browser you picked will not" are
     * different problems with different fixes, and the second is fixable from
     * the picker.
     */
    private fun noPrivateNote(activity: Activity, chosen: BrowserChoice.Resolution): String {
        val label = chosen.browser?.label ?: return activity.getString(R.string.external_no_private)
        return activity.getString(R.string.external_no_private_chosen, label)
    }

    /**
     * A custom view rather than `setMessage` + `setItems`: AlertDialog silently
     * drops the item list when a message is set, which in Threadbare produced a
     * dialog that explained why private browsing was unavailable and offered no
     * way to continue. Every option here is a real button.
     */
    private fun showDialog(
        activity: Activity,
        url: String,
        chosen: BrowserChoice.Resolution,
        recipe: PrivateTabs.Recipe?,
        note: String?,
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_external, null, false)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.external_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .create()

        view.findViewById<TextView>(R.id.externalUrl).text = url
        view.findViewById<TextView>(R.id.externalNote).apply {
            if (note != null) {
                text = note
                visibility = View.VISIBLE
            }
        }
        view.findViewById<Button>(R.id.externalOpen).apply {
            setText(if (note != null) R.string.external_open_anyway else R.string.external_open)
            // Name the browser the link is actually going to, so "open in
            // browser" is not a mystery when it is not the system default.
            chosen.browser?.let { text = activity.getString(R.string.external_open_in, it.label) }
            setOnClickListener {
                dialog.dismiss()
                openNormally(activity, url, chosen.browser?.packageName)
            }
        }
        view.findViewById<Button>(R.id.externalPrivate).apply {
            if (recipe != null) {
                text = activity.getString(R.string.external_private, recipe.label)
                visibility = View.VISIBLE
                setOnClickListener {
                    dialog.dismiss()
                    if (!BrowserLauncher.openPrivately(activity, url, recipe)) {
                        showDialog(
                            activity, url, chosen, null,
                            activity.getString(R.string.external_private_unavailable),
                        )
                    }
                }
            }
        }
        view.findViewById<Button>(R.id.externalCopy).setOnClickListener {
            dialog.dismiss()
            copy(activity, url)
        }
        dialog.show()
    }

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
