package com.dispatch.reader.web

/**
 * Which browser this app hands external links to.
 *
 * Ported unchanged from Threadbare, where it was written for exactly this
 * situation: a daily-driver browser (Vanadium, on GrapheneOS) plus a disposable
 * one for links the reader did not go looking for. A news reader is the purest
 * form of that case — every article link comes from a publisher, not from the
 * reader, and a hundred feeds is a lot of strangers' links.
 *
 * Separate from the system default on purpose: the alternative is copying a
 * link, switching apps and pasting, which is tedious and leaves the URL sitting
 * in the clipboard.
 *
 * Free of `android.*` so the selection rules are exercised on the JVM; the
 * PackageManager side is [BrowserLauncher].
 */
object BrowserChoice {

    /** Stored value meaning "let Android decide", which is also the default. */
    const val SYSTEM_DEFAULT = ""

    data class Browser(val packageName: String, val label: String)

    /**
     * What a stored preference resolves to right now.
     *
     * The distinction that matters is between *unset* and *gone*. An unset
     * preference is the user's choice and needs no comment; a browser they
     * chose and then uninstalled is a silent change of behaviour, and if the
     * one they chose was private-by-design it is a silent privacy change. So
     * [Resolution.stalePackage] carries the name of the missing browser and the
     * caller says something about it.
     */
    data class Resolution(
        val browser: Browser?,
        val stalePackage: String? = null,
    ) {
        val usingSystemDefault: Boolean get() = browser == null
    }

    fun resolve(stored: String?, installed: List<Browser>): Resolution {
        val want = stored?.trim().orEmpty()
        if (want.isEmpty() || want == SYSTEM_DEFAULT) return Resolution(null)
        val match = installed.firstOrNull { it.packageName == want }
        return if (match != null) Resolution(match) else Resolution(null, stalePackage = want)
    }

    /**
     * Case-insensitive by label, so the list reads the way a person expects
     * rather than in whatever order PackageManager happened to return.
     * Ties broken by package name so the order is stable across calls.
     */
    fun sorted(installed: List<Browser>): List<Browser> =
        installed.distinctBy { it.packageName }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))

    /**
     * How a browser should be described in the picker.
     *
     * The picker is where someone decides which browser to trust with these
     * links, so it says what each one can actually do. These map to the same
     * three states [PrivateTabs] knows about, and they are claims — so
     * [PrivacyNote.ALWAYS_PRIVATE] is only ever attached to a browser whose
     * every session is disposable, never to one that merely *can* open a
     * private tab on request.
     */
    enum class PrivacyNote { ALWAYS_PRIVATE, CAN_OPEN_PRIVATE, NO_PRIVATE }

    fun noteFor(packageName: String): PrivacyNote {
        val recipe = PrivateTabs.recipeFor(packageName)
        return when {
            recipe?.basis == PrivateTabs.Basis.ALWAYS_PRIVATE -> PrivacyNote.ALWAYS_PRIVATE
            recipe?.basis == PrivateTabs.Basis.EXTRA -> PrivacyNote.CAN_OPEN_PRIVATE
            else -> PrivacyNote.NO_PRIVATE
        }
    }

    /**
     * True when the chosen browser makes the private-tab preference moot.
     *
     * A browser that is private throughout has nothing to ask for and nothing
     * to opt out of: "ask each time" would be a prompt with one answer, and
     * "never open privately" cannot make Focus keep history. So the prompt is
     * skipped entirely and the link just opens — which is the point of letting
     * someone choose Focus in the first place.
     */
    fun isInherentlyPrivate(browser: Browser?): Boolean =
        browser != null && noteFor(browser.packageName) == PrivacyNote.ALWAYS_PRIVATE
}
