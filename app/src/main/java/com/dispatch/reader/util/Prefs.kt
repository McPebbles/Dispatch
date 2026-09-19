package com.dispatch.reader.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.dispatch.reader.web.BrowserChoice

/**
 * Every stored setting, with its default, in one place.
 *
 * Defaults are materialised once and recorded with a schema version, and the
 * rule that comes with that is one this project has already paid for twice
 * (Threadbare 1.0.0, SupplyChain 1.1.0):
 *
 *   **Changing a default — or adding a key with one — is a schema change. Bump
 *   [SCHEMA_VERSION] and write the migration, or the change only ever reaches
 *   devices that never ran the old build.**
 *
 * The reason is in the guards below: every default is written behind a
 * `contains` check so that a value the user chose is never overwritten, which
 * also means a new key is never written at all on an install whose stored
 * schema version is already current.
 */
object Prefs {

    /**
     * 1 — first release.
     * 2 — 1.1.1 added [KEY_OPEN_STREAM]. A new key with a default is a schema
     *     change, for the reason the class note gives: every default is written
     *     behind a `contains` check, so without the bump the key would only
     *     ever be materialised on a fresh install.
     */
    const val SCHEMA_VERSION = 2
    private const val KEY_SCHEMA = "schema_version"

    const val KEY_LINK_BROWSER = "link_browser"
    const val KEY_PRIVATE_TAB = "private_tab_mode"
    const val KEY_SYNC_INTERVAL = "sync_interval"
    const val KEY_SYNC_WIFI_ONLY = "sync_wifi_only"
    const val KEY_IMAGES = "load_images"
    const val KEY_RETENTION_DAYS = "retention_days"
    const val KEY_MARK_READ_ON_OPEN = "mark_read_on_open"
    const val KEY_TEXT_SIZE = "text_size"
    const val KEY_THEME = "theme"
    const val KEY_OPEN_STREAM = "open_stream"

    /** Not settings: remembered state. */
    private const val KEY_LAST_SYNC = "last_sync"
    private const val KEY_SELECTED_STREAM = "selected_stream"
    private const val KEY_UNREAD_ONLY = "unread_only"
    private const val KEY_SEEDED = "seeded_defaults"
    private const val KEY_START_CARD = "start_card_done"

    const val PRIVATE_NEVER = "never"
    const val PRIVATE_ASK = "ask"
    const val PRIVATE_ALWAYS = "always"

    /** Minutes. "0" means only when the reader asks. */
    const val DEFAULT_SYNC_INTERVAL = "60"
    const val DEFAULT_RETENTION_DAYS = "14"

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    const val TEXT_NORMAL = "normal"

    /** [KEY_OPEN_STREAM]'s value for "wherever I left off". */
    const val OPEN_LAST = "last"

    fun of(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    fun materialiseDefaults(context: Context) {
        val p = of(context)
        if (p.getInt(KEY_SCHEMA, 0) >= SCHEMA_VERSION) return
        p.edit().apply {
            if (!p.contains(KEY_LINK_BROWSER)) putString(KEY_LINK_BROWSER, BrowserChoice.SYSTEM_DEFAULT)
            if (!p.contains(KEY_PRIVATE_TAB)) putString(KEY_PRIVATE_TAB, PRIVATE_ASK)
            if (!p.contains(KEY_SYNC_INTERVAL)) putString(KEY_SYNC_INTERVAL, DEFAULT_SYNC_INTERVAL)
            if (!p.contains(KEY_SYNC_WIFI_ONLY)) putBoolean(KEY_SYNC_WIFI_ONLY, false)
            if (!p.contains(KEY_IMAGES)) putBoolean(KEY_IMAGES, true)
            if (!p.contains(KEY_RETENTION_DAYS)) putString(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
            if (!p.contains(KEY_MARK_READ_ON_OPEN)) putBoolean(KEY_MARK_READ_ON_OPEN, true)
            if (!p.contains(KEY_TEXT_SIZE)) putString(KEY_TEXT_SIZE, TEXT_NORMAL)
            if (!p.contains(KEY_THEME)) putString(KEY_THEME, THEME_SYSTEM)
            if (!p.contains(KEY_OPEN_STREAM)) putString(KEY_OPEN_STREAM, OPEN_LAST)
            putInt(KEY_SCHEMA, SCHEMA_VERSION)
        }.apply()
    }

    fun linkBrowser(context: Context): String? =
        of(context).getString(KEY_LINK_BROWSER, BrowserChoice.SYSTEM_DEFAULT)

    fun privateTabMode(context: Context): String =
        of(context).getString(KEY_PRIVATE_TAB, PRIVATE_ASK) ?: PRIVATE_ASK

    /** Minutes between background refreshes; 0 for manual only. */
    fun syncIntervalMinutes(context: Context): Int =
        of(context).getString(KEY_SYNC_INTERVAL, DEFAULT_SYNC_INTERVAL)?.toIntOrNull() ?: 60

    fun syncOnWifiOnly(context: Context): Boolean =
        of(context).getBoolean(KEY_SYNC_WIFI_ONLY, false)

    fun loadImages(context: Context): Boolean =
        of(context).getBoolean(KEY_IMAGES, true)

    fun retentionDays(context: Context): Int =
        of(context).getString(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)?.toIntOrNull() ?: 14

    fun markReadOnOpen(context: Context): Boolean =
        of(context).getBoolean(KEY_MARK_READ_ON_OPEN, true)

    fun textSize(context: Context): String =
        of(context).getString(KEY_TEXT_SIZE, TEXT_NORMAL) ?: TEXT_NORMAL

    fun theme(context: Context): String =
        of(context).getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM

    fun lastSync(context: Context): Long = of(context).getLong(KEY_LAST_SYNC, 0L)

    fun setLastSync(context: Context, at: Long) {
        of(context).edit().putLong(KEY_LAST_SYNC, at).apply()
    }

    fun selectedStream(context: Context): Long = of(context).getLong(KEY_SELECTED_STREAM, 0L)

    /**
     * The stream to show when the app is opened cold.
     *
     * [OPEN_LAST] — the default — means [selectedStream], which is where the
     * reader was. Anything else is a stream id, and a stream that has since
     * been deleted falls back to All feeds rather than to an empty screen with
     * a name on it.
     */
    fun openStream(context: Context): Long {
        val stored = of(context).getString(KEY_OPEN_STREAM, OPEN_LAST) ?: OPEN_LAST
        if (stored == OPEN_LAST) return selectedStream(context)
        return stored.toLongOrNull() ?: selectedStream(context)
    }

    fun setSelectedStream(context: Context, id: Long) {
        of(context).edit().putLong(KEY_SELECTED_STREAM, id).apply()
    }

    fun unreadOnly(context: Context): Boolean = of(context).getBoolean(KEY_UNREAD_ONLY, false)

    fun setUnreadOnly(context: Context, value: Boolean) {
        of(context).edit().putBoolean(KEY_UNREAD_ONLY, value).apply()
    }

    /**
     * Whether the bundled feeds have been seeded.
     *
     * Kept separate from "the feed table is empty": a reader who deletes every
     * feed on purpose should not have a hundred of them handed back on the next
     * launch.
     */
    fun seeded(context: Context): Boolean = of(context).getBoolean(KEY_SEEDED, false)

    fun setSeeded(context: Context) {
        of(context).edit().putBoolean(KEY_SEEDED, true).apply()
    }

    /**
     * Whether the "build your first stream" card has been dealt with.
     *
     * Shown only while the reader has no streams at all, and only until they
     * either build one or dismiss it. The bundled hundred are a library, so the
     * one thing a new reader has to be told is where streams come from — and
     * being told twice is nagging.
     */
    fun startCardDone(context: Context): Boolean = of(context).getBoolean(KEY_START_CARD, false)

    fun setStartCardDone(context: Context) {
        of(context).edit().putBoolean(KEY_START_CARD, true).apply()
    }

    /**
     * The text-scale multiplier for the article screen.
     *
     * Free of `android.*` so the mapping is testable, and deliberately narrow:
     * the system font scale already applies, and this multiplies on top of it.
     */
    fun scaleFor(size: String): Float = when (size) {
        "small" -> 0.9f
        "large" -> 1.2f
        "xlarge" -> 1.4f
        else -> 1.0f
    }
}
