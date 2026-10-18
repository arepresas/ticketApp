package com.ticketapp.android.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the single-origin gate that decides whether a URL may run
 * inside the session-carrying WebView.
 *
 * The happy path is one line; these are the cases that actually decide
 * whether a hostile page can reach the injected token, so each negative
 * case below is a potential token leak if it ever flips to `true`.
 */
class OriginPolicyTest {

    private val prod = "https://ticketapp-front.fly.dev"
    private val local = "http://10.0.2.2:5173"

    @Test
    fun `same origin path and query are allowed`() {
        assertTrue(OriginPolicy.isSameOrigin("https://ticketapp-front.fly.dev", prod))
        assertTrue(OriginPolicy.isSameOrigin("https://ticketapp-front.fly.dev/", prod))
        assertTrue(OriginPolicy.isSameOrigin("https://ticketapp-front.fly.dev/#pending", prod))
        assertTrue(OriginPolicy.isSameOrigin("https://ticketapp-front.fly.dev/api/tickets?x=1", prod))
    }

    @Test
    fun `a lookalike host appended to ours is rejected`() {
        // The prefix match that a `startsWith` implementation would let
        // through — and which hands the token to an attacker's domain.
        assertFalse(
            OriginPolicy.isSameOrigin("https://ticketapp-front.fly.dev.evil.example/", prod),
        )
    }

    @Test
    fun `our host as a subdomain of theirs is rejected`() {
        assertFalse(OriginPolicy.isSameOrigin("https://evil.ticketapp-front.fly.dev/", prod))
    }

    @Test
    fun `scheme downgrade to http is rejected`() {
        assertFalse(OriginPolicy.isSameOrigin("http://ticketapp-front.fly.dev/", prod))
    }

    @Test
    fun `a different port on the same host is rejected`() {
        assertFalse(OriginPolicy.isSameOrigin("http://10.0.2.2:8080", local))
    }

    @Test
    fun `loopback and the emulator alias are different origins`() {
        // Dev origin vs a BFF running on the host: the SPA proxies /api,
        // so the app must never navigate straight to the BFF.
        assertFalse(OriginPolicy.isSameOrigin("http://localhost:5173", local))
        assertFalse(OriginPolicy.isSameOrigin("http://127.0.0.1:5173", local))
    }

    @Test
    fun `a completely different site is rejected`() {
        assertFalse(OriginPolicy.isSameOrigin("https://accounts.google.com/signin", prod))
    }

    @Test
    fun `an unparseable target fails closed`() {
        // Not "same origin until proven otherwise" — anything we cannot
        // parse goes to the system browser.
        assertFalse(OriginPolicy.isSameOrigin("not a url", prod))
        assertFalse(OriginPolicy.isSameOrigin("", prod))
        assertFalse(OriginPolicy.isSameOrigin("javascript:alert(1)", prod))
    }

    @Test
    fun `an unparseable configured origin rejects everything`() {
        assertFalse(OriginPolicy.isSameOrigin("https://anything.example", "garbage"))
    }

    @Test
    fun `host comparison ignores case`() {
        assertTrue(OriginPolicy.isSameOrigin("https://TICKETAPP-FRONT.FLY.DEV/", prod))
    }
}