package com.dispatch.reader.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PersistableBundle
import com.dispatch.reader.util.Prefs
import com.dispatch.reader.util.Safely

/**
 * Handing a link to a browser — which browser, ordinarily or privately.
 *
 * The honesty rule from [PrivateTabs] is enforced here: [openPrivately] returns
 * false rather than falling back to an ordinary tab, so the caller has to decide
 * what to tell the user instead of a private launch silently becoming a normal
 * one.
 */
object BrowserLauncher {

    /**
     * Every installed browser, as the picker shows them.
     *
     * The probe is a **scheme-only** `http:` URI with no host. That is what
     * separates browsers from apps that merely claim some https links: a filter
     * with a host constraint — a video app, a newspaper's own app, a bank —
     * cannot match a URI that has no host, while a browser's catch-all filter
     * can. Without the trick the picker would offer to send external
     * links to whatever app happens to handle one particular domain.
     */
    fun installedBrowsers(context: Context): List<BrowserChoice.Browser> = Safely.call({
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.fromParts("http", "", null))
            .addCategory(Intent.CATEGORY_BROWSABLE)

        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)

        BrowserChoice.sorted(
            resolved.mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                val label = Safely.call({ info.loadLabel(pm).toString() }, pkg).ifBlank { pkg }
                BrowserChoice.Browser(pkg, label)
            },
        )
    }, emptyList())

    /** The package that would handle an ordinary https link, or null. */
    fun defaultBrowser(context: Context): String? = Safely.call({
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))
        val resolved = context.packageManager.resolveActivity(
            probe, PackageManager.MATCH_DEFAULT_ONLY,
        )
        resolved?.activityInfo?.packageName?.takeIf { it != "android" }
    }, null)

    /**
     * What the stored preference means right now, including whether the browser
     * the user chose has since been uninstalled.
     */
    fun chosen(context: Context): BrowserChoice.Resolution =
        BrowserChoice.resolve(Prefs.linkBrowser(context), installedBrowsers(context))

    /** Which of the private-capable browsers are actually installed. */
    fun installedPrivateBrowsers(context: Context): List<PrivateTabs.Recipe> {
        val pm = context.packageManager
        return PrivateTabs.recipes().filter { recipe ->
            Safely.call({
                pm.getLaunchIntentForPackage(recipe.packageName) != null
            }, false)
        }
    }

    /**
     * The browser a private launch would use: the one chosen for this app if it
     * is capable, else the system default if it is, else any capable browser
     * that is installed.
     */
    fun privateOption(context: Context): PrivateTabs.Recipe? =
        PrivateTabs.choose(
            installedPrivateBrowsers(context).map { it.packageName },
            chosen(context).browser?.packageName,
            defaultBrowser(context),
        )

    /**
     * An ordinary external open, in [packageName] when one is given.
     *
     * Returns false when nothing could handle it — including the case where the
     * chosen browser was uninstalled between the picker and the tap. The caller
     * retries without a package rather than swallowing that.
     */
    fun openNormally(context: Context, url: String, packageName: String? = null): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addCategory(Intent.CATEGORY_BROWSABLE)
            if (packageName != null) setPackage(packageName)
        }
        return Safely.call({ context.startActivity(intent); true }, false)
    }

    /**
     * Open in a private tab, or report that it could not be done.
     *
     * **Never falls back to an ordinary tab.** A user who asked for private
     * browsing and got a normal tab is worse off than one who was told it was
     * unavailable, because they will read as though they are protected. The
     * caller decides what to do with a false.
     */
    fun openPrivately(context: Context, url: String, recipe: PrivateTabs.Recipe): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(recipe.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addCategory(Intent.CATEGORY_BROWSABLE)
            if (recipe.basis == PrivateTabs.Basis.EXTRA) {
                putExtra(PrivateTabs.EXTRA_PRIVATE, true)
            }
        }
        return Safely.call({ context.startActivity(intent); true }, false)
    }

    /**
     * The escape hatch, marked sensitive.
     *
     * A URL left on the clipboard is a small but real exposure: on Android 13+
     * the system shows a preview of whatever was copied, and the clip outlives
     * the moment it was needed. `IS_SENSITIVE` suppresses that preview.
     *
     * The key is spelled out rather than referenced from `ClipDescription`
     * because that field is API 33 and this app is minSdk 30. It is read by
     * name, so writing it on an older release is simply ignored — and with a
     * browser picker there is now much less reason to reach for copy at all.
     */
    fun copyToClipboard(context: Context, url: String): Boolean = Safely.call({
        val clip = ClipData.newPlainText("link", url).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(EXTRA_IS_SENSITIVE, true)
            }
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        true
    }, false)

    /** `ClipDescription.EXTRA_IS_SENSITIVE`, which is API 33. */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
}
