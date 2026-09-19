package com.dispatch.reader.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.dispatch.reader.App
import com.dispatch.reader.BuildConfig
import com.dispatch.reader.R
import com.dispatch.reader.feed.SyncScheduler
import com.dispatch.reader.img.ImageStore
import com.dispatch.reader.util.Prefs
import com.dispatch.reader.util.Safely
import com.dispatch.reader.widget.NewsWidgetProvider

/**
 * The settings screen's behaviour.
 *
 * Nothing here needs a restart — this app has no WebView whose startup scripts
 * are fixed at construction, which is what forced Threadbare's restart gate. In
 * exchange, three settings have to *do* something the moment they change, and
 * a setting that silently does not take effect is the failure this project has
 * hit most often:
 *
 *  - the refresh interval and the Wi-Fi-only switch have to re-enqueue the
 *    WorkManager job, or the screen shows one schedule while the system runs
 *    another;
 *  - the theme has to be applied immediately, or it appears to do nothing until
 *    the next cold start;
 *  - turning images off should offer to remove the ones already on disk, since
 *    "don't load images" and "and forget the ones you already loaded" are
 *    different promises and only the reader knows which they meant.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    private val watcher = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.KEY_SYNC_INTERVAL, Prefs.KEY_SYNC_WIFI_ONLY -> {
                val app = activity?.application as? App
                if (app != null) Safely.run { SyncScheduler.reschedule(app, app.repo) }
            }
            Prefs.KEY_THEME -> {
                val app = activity?.application as? App
                app?.applyTheme()
                // Widgets set to follow the app are drawn by the launcher and
                // will not notice this on their own; without it the tile keeps
                // yesterday's colours until something else happens to it.
                if (app != null) Safely.run { NewsWidgetProvider.refreshAll(app) }
                activity?.recreate()
            }
            Prefs.KEY_IMAGES -> onImagesChanged()
            Prefs.KEY_LINK_BROWSER -> populateBrowsers()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        populateBrowsers()
        populateStreams()
        wireActions()
    }

    override fun onResume() {
        super.onResume()
        Prefs.of(requireContext()).registerOnSharedPreferenceChangeListener(watcher)
        refreshStorageSummary()
    }

    override fun onPause() {
        super.onPause()
        Prefs.of(requireContext()).unregisterOnSharedPreferenceChangeListener(watcher)
    }

    /**
     * The browser picker's entries come from the device, so they cannot live in
     * `arrays.xml`. `tools/verify_resources.py` knows this preference is filled
     * in here and fails if that stops being true — a ListPreference with no
     * entries from either source is an empty dialog.
     *
     * Each browser is labelled with what it can actually do about privacy,
     * because this list is where someone decides which browser to trust with
     * links they did not choose to follow. Those labels are claims, so "always
     * private" is only ever attached to a browser whose every session is
     * disposable — see [com.dispatch.reader.web.BrowserChoice.noteFor].
     */
    private fun populateBrowsers() {
        val pref = findPreference<ListPreference>(Prefs.KEY_LINK_BROWSER) ?: return
        val context = context ?: return
        val installed = com.dispatch.reader.web.BrowserLauncher.installedBrowsers(context)

        val labels = ArrayList<CharSequence>()
        val values = ArrayList<CharSequence>()
        labels += getString(R.string.pref_link_browser_system)
        values += com.dispatch.reader.web.BrowserChoice.SYSTEM_DEFAULT

        for (browser in installed) {
            labels += when (com.dispatch.reader.web.BrowserChoice.noteFor(browser.packageName)) {
                com.dispatch.reader.web.BrowserChoice.PrivacyNote.ALWAYS_PRIVATE ->
                    getString(R.string.browser_always_private, browser.label)
                com.dispatch.reader.web.BrowserChoice.PrivacyNote.CAN_OPEN_PRIVATE ->
                    getString(R.string.browser_can_private, browser.label)
                com.dispatch.reader.web.BrowserChoice.PrivacyNote.NO_PRIVATE -> browser.label
            }
            values += browser.packageName
        }

        // A browser that was chosen and has since been uninstalled is kept in
        // the list, marked. Dropping it would leave the setting showing nothing
        // with no explanation of why the choice stopped being honoured.
        val stale = com.dispatch.reader.web.BrowserChoice.resolve(pref.value, installed).stalePackage
        if (stale != null) {
            labels += getString(R.string.browser_not_installed, stale)
            values += stale
        }

        pref.entries = labels.toTypedArray()
        pref.entryValues = values.toTypedArray()
        if (pref.value == null) pref.value = com.dispatch.reader.web.BrowserChoice.SYSTEM_DEFAULT
    }

    /**
     * The "open on" list.
     *
     * Built from the database because its entries *are* the reader's streams:
     * an `arrays.xml` list would be wrong the first time one is renamed. A
     * stream that has since been deleted is dropped here and [Prefs.openStream]
     * falls back, so the setting cannot point at nothing.
     */
    private fun populateStreams() {
        val pref = findPreference<ListPreference>(Prefs.KEY_OPEN_STREAM) ?: return
        val app = activity?.application as? App ?: return
        app.io.execute {
            val streams = Safely.call({ app.repo.streams() }, emptyList())
            activity?.runOnUiThread {
                val context = context ?: return@runOnUiThread
                val labels = ArrayList<CharSequence>()
                val values = ArrayList<CharSequence>()
                labels += context.getString(R.string.pref_open_stream_last)
                values += Prefs.OPEN_LAST
                labels += context.getString(R.string.stream_all)
                values += com.dispatch.reader.data.Stream.ALL_ID.toString()
                labels += context.getString(R.string.stream_saved)
                values += com.dispatch.reader.data.Stream.SAVED_ID.toString()
                for (stream in streams) {
                    labels += stream.name
                    values += stream.id.toString()
                }
                pref.entries = labels.toTypedArray()
                pref.entryValues = values.toTypedArray()
                if (pref.value == null || pref.value !in values) pref.value = Prefs.OPEN_LAST
            }
        }
    }

    private fun wireActions() {
        findPreference<Preference>(KEY_CLEAR_IMAGES)?.setOnPreferenceClickListener {
            clearImages()
            true
        }
        findPreference<Preference>(KEY_REFRESH_NOW)?.setOnPreferenceClickListener {
            val app = activity?.application as? App ?: return@setOnPreferenceClickListener true
            Toast.makeText(context, R.string.refresh_started, Toast.LENGTH_SHORT).show()
            app.io.execute {
                Safely.run { com.dispatch.reader.feed.Sync.refreshAll(app, app.repo) }
                Safely.run { NewsWidgetProvider.refreshAll(app) }
            }
            true
        }
        findPreference<Preference>(KEY_VERSION)?.summary =
            getString(R.string.pref_version_summary, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    }

    private fun onImagesChanged() {
        val context = context ?: return
        if (Prefs.loadImages(context)) return
        AlertDialog.Builder(context)
            .setTitle(R.string.pref_images_off_title)
            .setMessage(R.string.pref_images_off_message)
            .setPositiveButton(R.string.clear_images) { _, _ -> clearImages() }
            .setNegativeButton(R.string.keep, null)
            .show()
    }

    private fun clearImages() {
        val app = activity?.application as? App ?: return
        app.io.execute {
            Safely.run { ImageStore.clear() }
            activity?.runOnUiThread {
                refreshStorageSummary()
                Toast.makeText(context, R.string.images_cleared, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshStorageSummary() {
        val pref = findPreference<Preference>(KEY_CLEAR_IMAGES) ?: return
        val app = activity?.application as? App ?: return
        app.io.execute {
            val bytes = Safely.call({ ImageStore.diskBytes() }, 0L)
            activity?.runOnUiThread {
                pref.summary = getString(R.string.pref_clear_images_summary, bytes / 1024 / 1024)
            }
        }
    }

    private companion object {
        const val KEY_CLEAR_IMAGES = "clear_images"
        const val KEY_REFRESH_NOW = "refresh_now"
        const val KEY_VERSION = "version"
    }
}
