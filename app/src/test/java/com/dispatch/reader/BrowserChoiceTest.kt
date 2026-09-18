package com.dispatch.reader

import com.dispatch.reader.web.BrowserChoice
import com.dispatch.reader.web.BrowserChoice.Browser
import com.dispatch.reader.web.BrowserChoice.PrivacyNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserChoiceTest {

    private val vanadium = Browser("app.vanadium.browser", "Vanadium")
    private val focus = Browser("org.mozilla.focus", "Firefox Focus")
    private val firefox = Browser("org.mozilla.firefox", "Firefox")
    private val installed = listOf(vanadium, focus, firefox)

    // ------------------------------------------------------------- resolving

    @Test
    fun `an unset preference means the system default`() {
        for (stored in listOf(null, "", "   ", BrowserChoice.SYSTEM_DEFAULT)) {
            val r = BrowserChoice.resolve(stored, installed)
            assertTrue("stored=$stored", r.usingSystemDefault)
            assertNull(r.stalePackage)
        }
    }

    @Test
    fun `a chosen browser that is installed resolves to it`() {
        val r = BrowserChoice.resolve("org.mozilla.focus", installed)
        assertEquals(focus, r.browser)
        assertNull(r.stalePackage)
        assertFalse(r.usingSystemDefault)
    }

    /**
     * The distinction that matters. "Never chose one" needs no comment;
     * "chose one and it is gone" is a silent change of behaviour, and if the
     * missing browser was private-by-design it is a silent privacy change. The
     * caller can only say so if resolve tells it apart from an unset value.
     */
    @Test
    fun `a chosen browser that has been uninstalled is reported, not silently ignored`() {
        val r = BrowserChoice.resolve("org.mozilla.focus", listOf(vanadium))
        assertNull(r.browser)
        assertTrue(r.usingSystemDefault)
        assertEquals("org.mozilla.focus", r.stalePackage)
    }

    @Test
    fun `an unset preference is never reported as stale`() {
        assertNull(BrowserChoice.resolve("", emptyList()).stalePackage)
    }

    // -------------------------------------------------------------- ordering

    @Test
    fun `the list reads alphabetically, case-insensitively`() {
        val out = BrowserChoice.sorted(listOf(vanadium, focus, firefox))
        assertEquals(listOf("Firefox", "Firefox Focus", "Vanadium"), out.map { it.label })
    }

    @Test
    fun `duplicate activities from one package appear once`() {
        val out = BrowserChoice.sorted(
            listOf(firefox, Browser("org.mozilla.firefox", "Firefox (alias)")),
        )
        assertEquals(1, out.size)
    }

    @Test
    fun `identical labels still order stably`() {
        val a = Browser("com.b.browser", "Browser")
        val b = Browser("com.a.browser", "Browser")
        assertEquals(listOf("com.a.browser", "com.b.browser"),
            BrowserChoice.sorted(listOf(a, b)).map { it.packageName })
    }

    // ------------------------------------------------- what the picker claims

    /**
     * These labels are claims made to someone deciding which browser to trust
     * with links they did not choose to follow, so the mapping is asserted
     * rather than assumed. "Always private" must never attach to a browser that
     * merely *can* open a private tab on request.
     */
    @Test
    fun `privacy notes match what each browser can actually do`() {
        assertEquals(PrivacyNote.ALWAYS_PRIVATE, BrowserChoice.noteFor("org.mozilla.focus"))
        assertEquals(PrivacyNote.ALWAYS_PRIVATE, BrowserChoice.noteFor("org.mozilla.klar"))
        assertEquals(PrivacyNote.ALWAYS_PRIVATE,
            BrowserChoice.noteFor("org.torproject.torbrowser"))
        assertEquals(PrivacyNote.CAN_OPEN_PRIVATE, BrowserChoice.noteFor("org.mozilla.firefox"))
        assertEquals(PrivacyNote.CAN_OPEN_PRIVATE, BrowserChoice.noteFor("us.spotco.fennec_dos"))
        assertEquals(PrivacyNote.NO_PRIVATE, BrowserChoice.noteFor("app.vanadium.browser"))
        assertEquals(PrivacyNote.NO_PRIVATE, BrowserChoice.noteFor("com.android.chrome"))
    }

    @Test
    fun `an unknown browser claims nothing`() {
        assertEquals(PrivacyNote.NO_PRIVATE, BrowserChoice.noteFor("com.example.mystery"))
    }

    // ------------------------------------------- the prompt-skipping rule

    /**
     * Choosing Focus is how a reader stops being asked. It has no non-private
     * mode to request or opt out of, so the private-tab preference is moot and
     * the prompt is skipped entirely.
     */
    @Test
    fun `an always-private browser makes the private-tab prompt moot`() {
        assertTrue(BrowserChoice.isInherentlyPrivate(focus))
        assertTrue(BrowserChoice.isInherentlyPrivate(Browser("org.torproject.torbrowser", "Tor")))
    }

    @Test
    fun `a browser that only offers private on request still gets the prompt`() {
        // Firefox can do both, so "never / ask / always" is a real choice there.
        assertFalse(BrowserChoice.isInherentlyPrivate(firefox))
        assertFalse(BrowserChoice.isInherentlyPrivate(vanadium))
    }

    @Test
    fun `the system default is never assumed to be private`() {
        assertFalse(BrowserChoice.isInherentlyPrivate(null))
    }
}
