package com.ticketapp.android.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the two scripts the shell injects into the hosted SPA.
 *
 * These strings are the contract with `front/`: the seeder writes the
 * key the SPA reads on boot, and the observer watches for the SPA
 * clearing it. A typo in either is silent — the SPA simply boots
 * signed out, or never signs out — so the scripts are asserted
 * structurally rather than left to review.
 */
class SessionBridgeScriptTest {

    @Test
    fun `seed script writes the key the SPA reads on boot`() {
        val script = SessionBridge.seedScript("jwt-abc")

        assertTrue(script.contains("sessionStorage.setItem"))
        assertTrue(
            "must write the exact key the SPA reads",
            script.contains("'${SessionBridge.TOKEN_KEY}'"),
        )
        assertTrue(script.contains("\"jwt-abc\""))
    }

    @Test
    fun `seed script never throws out of the injected context`() {
        // Runs before the SPA's own code; an uncaught throw would be
        // reported as a page error and could abort the boot sequence.
        val script = SessionBridge.seedScript("jwt")

        assertTrue("must be wrapped in try/catch", script.contains("try {"))
        assertTrue("must swallow storage failures", script.contains("catch"))
    }

    @Test
    fun `seed script quotes a hostile token instead of executing it`() {
        val hostile = "\"; window.__pwned = true; \""

        val script = SessionBridge.seedScript(hostile)

        assertFalse(script.contains("window.__pwned = true; \""))
        // The payload survives as data, escaped.
        assertTrue(script.contains("\\\""))
    }

    @Test
    fun `the storage key matches the SPA contract`() {
        // Renamed in front/src/lib/auth/store.svelte.ts → this fails,
        // which is the point: silent mobile login breakage is the
        // failure mode ADR 0009 §D3 warns about.
        assertEquals("ticketapp.session", SessionBridge.TOKEN_KEY)
    }

    @Test
    fun `observer watches the same key and reports to the native bridge`() {
        val script = SessionBridgeCallback.OBSERVER_SCRIPT

        assertTrue(
            "observer must watch the SPA's session key",
            script.contains("'${SessionBridge.TOKEN_KEY}'"),
        )
        assertTrue(
            "observer must call the native interface",
            script.contains("window.${SessionBridge.INTERFACE_NAME}"),
        )
        assertTrue("must notify native on removal", script.contains("onSessionCleared"))
    }

    @Test
    fun `observer patches both storage removal paths`() {
        val script = SessionBridgeCallback.OBSERVER_SCRIPT

        // The SPA's logout() calls removeItem; a wholesale clear() must
        // count as a sign-out too, or the shell keeps a dead token.
        assertTrue(script.contains("removeItem"))
        assertTrue(script.contains("proto.clear"))
    }

    @Test
    fun `observer installs only once per document`() {
        // The script is injected as both the seed and the observer;
        // double-patching Storage.prototype would double-notify and
        // recurse on the wrapper itself.
        val script = SessionBridgeCallback.OBSERVER_SCRIPT

        assertTrue(script.contains("__ticketAppObserved"))
    }

    @Test
    fun `observer only notifies when the key is actually gone`() {
        // Removing some other storage key must not sign the user out.
        val script = SessionBridgeCallback.OBSERVER_SCRIPT

        assertTrue(
            "must confirm the key is absent before notifying",
            script.contains("getItem(KEY) === null"),
        )
        assertTrue("must key the notification off the token key", script.contains("key === KEY"))
    }
}