package com.dispatch.reader

import com.dispatch.reader.web.PrivateTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The honesty rules around private tabs.
 *
 * The failure these guard against is not a crash; it is the app telling a user
 * their browsing is private when it is not. Chromium refuses third-party
 * incognito launches, so a Chromium package appearing in the recipe table would
 * produce exactly that lie — silently, and only for the users who most wanted
 * the feature to work.
 */
class PrivateTabsTest {

    @Test
    fun `no chromium browser is ever treated as private-capable`() {
        for (pkg in listOf(
            "app.vanadium.browser", "com.android.chrome", "com.brave.browser",
            "org.chromium.chrome", "com.microsoft.emmx", "com.opera.browser",
            "com.sec.android.app.sbrowser",
        )) {
            assertNull("$pkg must have no recipe", PrivateTabs.recipeFor(pkg))
            assertTrue("$pkg must be listed as incapable", PrivateTabs.isKnownIncapable(pkg))
        }
    }

    @Test
    fun `vanadium in particular is handled honestly`() {
        // The default browser on the platform this suite targets, and therefore
        // the most important one not to lie about.
        assertNull(PrivateTabs.recipeFor("app.vanadium.browser"))
        assertTrue(PrivateTabs.isKnownIncapable("app.vanadium.browser"))
    }

    @Test
    fun `the firefox family is capable via the documented extra`() {
        for (pkg in listOf("org.mozilla.firefox", "org.mozilla.fenix", "us.spotco.fennec_dos")) {
            val r = PrivateTabs.recipeFor(pkg)
            assertNotNull("$pkg should have a recipe", r)
            assertEquals(PrivateTabs.Basis.EXTRA, r!!.basis)
        }
    }

    /**
     * The browser the picker exists to make convenient: no non-private mode to
     * end up in by accident, which is what makes it a sensible destination for
     * links a reader did not go looking for.
     */
    @Test
    fun `firefox focus is always private, not merely capable`() {
        assertEquals(PrivateTabs.Basis.ALWAYS_PRIVATE,
            PrivateTabs.recipeFor("org.mozilla.focus")!!.basis)
        assertEquals(PrivateTabs.Basis.ALWAYS_PRIVATE,
            PrivateTabs.recipeFor("org.mozilla.klar")!!.basis)
    }

    @Test
    fun `tor browser needs no extra because every window is private`() {
        assertEquals(
            PrivateTabs.Basis.ALWAYS_PRIVATE,
            PrivateTabs.recipeFor("org.torproject.torbrowser")!!.basis,
        )
    }

    @Test
    fun `no package is both capable and incapable`() {
        for (r in PrivateTabs.recipes()) {
            assertFalse(r.packageName, PrivateTabs.isKnownIncapable(r.packageName))
        }
    }

    @Test
    fun `the user's own default browser is preferred when it is capable`() {
        val installed = listOf("org.mozilla.firefox", "us.spotco.fennec_dos")
        assertEquals(
            "us.spotco.fennec_dos",
            PrivateTabs.choose(installed, "us.spotco.fennec_dos")?.packageName,
        )
    }

    /**
     * Preferences are consulted in the order the user expressed them: the
     * browser picked for this app beats their system default, which beats the
     * table's own order.
     */
    @Test
    fun `the browser chosen for this app beats the system default`() {
        val installed = listOf("org.mozilla.firefox", "org.mozilla.focus")
        assertEquals(
            "org.mozilla.focus",
            PrivateTabs.choose(installed, "org.mozilla.focus", "org.mozilla.firefox")?.packageName,
        )
    }

    @Test
    fun `an incapable app choice falls through to the system default`() {
        val installed = listOf("org.mozilla.firefox")
        assertEquals(
            "org.mozilla.firefox",
            PrivateTabs.choose(installed, "app.vanadium.browser", "org.mozilla.firefox")?.packageName,
        )
    }

    @Test
    fun `nulls among the preferences are skipped, not treated as a choice`() {
        val installed = listOf("org.mozilla.firefox")
        assertEquals(
            "org.mozilla.firefox",
            PrivateTabs.choose(installed, null, null)?.packageName,
        )
    }

    @Test
    fun `an incapable default falls through to a capable browser`() {
        assertEquals(
            "org.mozilla.firefox",
            PrivateTabs.choose(listOf("org.mozilla.firefox"), "app.vanadium.browser")?.packageName,
        )
    }

    @Test
    fun `nothing capable installed means nothing is offered`() {
        // The caller must then tell the user, not quietly open a normal tab.
        assertNull(PrivateTabs.choose(listOf("app.vanadium.browser"), "app.vanadium.browser"))
        assertNull(PrivateTabs.choose(emptyList(), null))
    }

    @Test
    fun `a recipe that is not installed is never chosen`() {
        assertNull(PrivateTabs.choose(emptyList(), "org.mozilla.firefox"))
    }

    @Test
    fun `the manifest query list matches the recipe table`() {
        assertEquals(PrivateTabs.recipes().map { it.packageName }, PrivateTabs.queryPackages())
    }

    @Test
    fun `the extra is the one firefox documents`() {
        assertEquals("private_browsing_mode", PrivateTabs.EXTRA_PRIVATE)
    }
}
