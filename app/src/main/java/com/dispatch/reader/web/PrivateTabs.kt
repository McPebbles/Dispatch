package com.dispatch.reader.web

/**
 * Which browsers will actually open a private tab when another app asks, and —
 * just as importantly — which will not.
 *
 * ## The finding that shapes this file
 *
 * **Chromium refuses third-party incognito launches.** `IntentHandler`'s
 * `isAllowedIncognitoIntent()` limits `EXTRA_OPEN_NEW_INCOGNITO_TAB` to Chrome's
 * own intents and to validated Custom Tabs. That extra is passed around widely
 * as "the way to open an incognito tab", and from an app like this one it does
 * nothing: the link opens in an ordinary tab. Chrome, **Vanadium**, Brave and
 * every other Chromium fork inherit that behaviour.
 *
 * That makes it a trap rather than a fallback. A user who turns on "always open
 * privately" and gets a normal tab is worse off than one who was told the
 * feature is unavailable, because they will browse as though they are protected
 * when they are not. **So this app never sends an intent it cannot expect to be
 * honoured, and never describes a launch as private unless it is.**
 *
 * ## What does work
 *
 * Firefox for Android honours a `private_browsing_mode` extra from third-party
 * apps as of version 112 (Bugzilla 1807531, RESOLVED FIXED). Its forks inherit
 * it. Tor Browser is private by construction. Those are the entries below.
 *
 * ## What was considered and left out
 *
 * An *ephemeral Custom Tab* (`androidx.browser.customtabs.extra.ENABLE_EPHEMERAL_BROWSING`)
 * is open to any app and would cover Chromium. It is not used because the
 * androidx API offers **no way to ask whether the browser supports it** — the
 * documentation says only "if the browser supports it". A browser that ignores
 * the extra renders a Custom Tab that looks exactly like one honouring it, which
 * is precisely the silent-failure mode this file exists to avoid.
 *
 * Free of `android.*` so the table and its rules are checked on the JVM.
 */
object PrivateTabs {

    /**
     * Firefox's extra. A boolean on an ordinary ACTION_VIEW, with the package
     * set to the browser.
     */
    const val EXTRA_PRIVATE = "private_browsing_mode"

    /**
     * Why a given browser can be trusted with a private launch. Recorded so the
     * reasoning survives the next person reading the table.
     */
    enum class Basis {
        /** Honours [EXTRA_PRIVATE] from a third-party app. */
        EXTRA,

        /** Every window is private; nothing needs to be requested. */
        ALWAYS_PRIVATE,
    }

    data class Recipe(
        val packageName: String,
        val label: String,
        val basis: Basis,
    )

    /**
     * Ordered by preference. Anything not in this list is treated as incapable,
     * which is the safe default: a browser wrongly listed here produces a false
     * promise, a browser wrongly missing produces an honest "not available".
     */
    private val RECIPES = listOf(
        Recipe("org.mozilla.firefox", "Firefox", Basis.EXTRA),
        Recipe("org.mozilla.firefox_beta", "Firefox Beta", Basis.EXTRA),
        Recipe("org.mozilla.fenix", "Firefox Nightly", Basis.EXTRA),
        // Hardened Firefox forks. Relevant here because this suite targets
        // GrapheneOS, where they are common; both are Fenix-derived and inherit
        // the intent handling.
        Recipe("us.spotco.fennec_dos", "Mull", Basis.EXTRA),
        Recipe("org.ironfoxoss.ironfox", "IronFox", Basis.EXTRA),
        // Private throughout, so nothing has to be requested. Focus keeps no
        // history or cookies between sessions and has an erase control; there
        // is no non-private mode to end up in by accident, which is what makes
        // it a good choice for links a reader did not go looking for. Klar is
        // the same app under its German name.
        Recipe("org.mozilla.focus", "Firefox Focus", Basis.ALWAYS_PRIVATE),
        Recipe("org.mozilla.klar", "Firefox Klar", Basis.ALWAYS_PRIVATE),
        Recipe("org.torproject.torbrowser", "Tor Browser", Basis.ALWAYS_PRIVATE),
    )

    /**
     * Chromium-family packages, listed so the reason for their absence is
     * explicit and so a future edit does not quietly add one.
     *
     * Vanadium is here rather than in [RECIPES] deliberately: it is the default
     * browser on the platform this suite targets, which makes it the most
     * important one to be honest about.
     */
    private val KNOWN_INCAPABLE = setOf(
        "app.vanadium.browser",
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.brave.browser",
        "org.chromium.chrome",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.sec.android.app.sbrowser",
    )

    fun recipes(): List<Recipe> = RECIPES

    fun recipeFor(packageName: String?): Recipe? =
        RECIPES.firstOrNull { it.packageName == packageName }

    fun isKnownIncapable(packageName: String?): Boolean =
        packageName != null && packageName in KNOWN_INCAPABLE

    /**
     * Pick a browser to open privately in, from what is installed.
     *
     * [preferred] is consulted in order before the table's own order, so the
     * browser the user picked for this app wins over their system default,
     * which in turn wins over whatever happens to be listed first here. Their
     * choices are respected in the order they made them.
     */
    fun choose(installed: Collection<String>, vararg preferred: String?): Recipe? {
        for (want in preferred) {
            recipeFor(want)?.let { if (it.packageName in installed) return it }
        }
        return RECIPES.firstOrNull { it.packageName in installed }
    }

    /** Packages worth declaring in the manifest's `<queries>`. */
    fun queryPackages(): List<String> = RECIPES.map { it.packageName }
}
