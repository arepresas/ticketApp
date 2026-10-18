package com.ticketapp.android.web

import java.net.URI

/**
 * The single-origin rule that keeps the session token inside the app's
 * own pages.
 *
 * The WebView injects the BFF JWT into `sessionStorage` and exposes a
 * JS interface to the page. Both are only safe while the loaded
 * document is the app's own origin — a third-party page rendered in
 * this WebView could read the token out of storage and call the native
 * bridge. Navigation is therefore pinned here and every off-origin
 * link is handed to the system browser.
 *
 * Extracted from `WebAppScreen` so it can be tested on the JVM: the
 * comparison is a security boundary, and the interesting cases are the
 * near-misses (`app.example.com.evil.tld`, a scheme downgrade, a
 * different port), not the happy path.
 *
 * Uses [java.net.URI] rather than `android.net.Uri` so the same code
 * runs under a plain JVM test without an emulator or Robolectric.
 */
internal object OriginPolicy {

    /**
     * True when [target] belongs to exactly [origin] — same scheme,
     * host and port.
     *
     * Compared field-by-field rather than by string prefix, because
     * `https://app.example.com.evil.tld` *starts with* a legitimate
     * origin string while being a different site entirely.
     *
     * A URL that will not parse is treated as foreign: failing closed
     * sends it to the system browser, which is where it belongs.
     */
    fun isSameOrigin(target: String, origin: String): Boolean {
        val a = parse(target) ?: return false
        val b = parse(origin) ?: return false

        return a.scheme.equals(b.scheme, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            a.port == b.port
    }

    private data class Parts(val scheme: String, val host: String, val port: Int)

    private fun parse(url: String): Parts? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        Parts(scheme = scheme, host = host, port = uri.port)
    }.getOrNull()
}