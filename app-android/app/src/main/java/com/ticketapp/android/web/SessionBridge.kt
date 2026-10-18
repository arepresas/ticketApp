package com.ticketapp.android.web

import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * Hands the BFF session JWT to the SPA **before its own JavaScript
 * runs**.
 *
 * ## The problem
 *
 * `front/src/lib/auth/store.svelte.ts` calls `initAuth()` on boot,
 * which reads `sessionStorage['ticketapp.session']` and validates it
 * with `GET /api/auth/me`. Anything that writes that key after the
 * bundle has executed is too late — the store has already decided it
 * is signed out, and the user lands on the landing page with a
 * perfectly valid session sitting in memory.
 *
 * `WebView.evaluateJavascript` cannot fix that: it queues behind
 * document load. So does `onPageFinished`. The only correct hook is a
 * **document-start** script, which AndroidX exposes as
 * [WebViewCompat.addDocumentStartJavaScript] — it runs after the
 * document is created but before any page-authored script.
 *
 * `WebViewFeature.DOCUMENT_START_SCRIPT` covers WebView 105+; on older
 * system WebViews the bridge degrades to the post-load injection in
 * [WebAppScreen], which costs one reload.
 *
 * ## Security
 *
 * The injected script is scoped to the app's own origin
 * ([allowedOriginRule]) so it can never run on a third-party page, and
 * the WebView refuses off-origin navigation (see [WebAppScreen]).
 */
object SessionBridge {

    // Must match `TOKEN_KEY` in `front/src/lib/auth/store.svelte.ts`.
    // `internal` rather than `private` because SessionBridgeCallback
    // (below) builds its observer script against the same key.
    internal const val TOKEN_KEY = "ticketapp.session"

    // JS interface name — see `addJavascriptInterface` in WebAppScreen.
    internal const val INTERFACE_NAME = "TicketAppSession"

    /**
     * The document-start script that seeds the SPA's session storage.
     *
     * [jsQuote] produces a correct JS string literal, so the script
     * stays valid whatever the token contains — a JWT is base64url
     * today, but the token format is not this module's to assume.
     */
    fun seedScript(token: String): String = """
        try {
          window.sessionStorage.setItem('$TOKEN_KEY', ${jsQuote(token)});
        } catch (e) {
          // Storage-disabled WebView: the SPA's own session is equally
          // unavailable, so there is nothing to recover here.
        }
    """.trimIndent()

    /**
     * Fallback seeding for WebViews without document-start support.
     * Executes after load, so the caller must reload once afterwards.
     */
    fun seedAfterLoad(webView: WebView, token: String) {
        webView.evaluateJavascript(seedScript(token), null)
    }

    /**
     * Read back what the SPA currently holds. `null` means the SPA has
     * signed itself out (the header's Sign out, or a 401 clearing the
     * session) — the signal the shell uses to drop its own copy.
     */
    fun readToken(webView: WebView, onResult: (String?) -> Unit) {
        webView.evaluateJavascript("window.sessionStorage.getItem('$TOKEN_KEY')") { value ->
            val token = value?.takeIf { it != "null" }?.trim('"')
            onResult(token?.takeIf { it.isNotEmpty() })
        }
    }
}

/**
 * JS → native callback, invoked by the page-load observer script in
 * [WebAppScreen].
 *
 * The SPA clears `sessionStorage` without telling anyone (it has no
 * native channel — it is a web app first). This hook observes the
 * standard `Storage` mutation API to notice it, so the shell's
 * [com.ticketapp.android.auth.SessionStore] never keeps a token the SPA
 * has already abandoned: the next cold start would otherwise silently
 * re-authenticate a user who asked to sign out.
 */
class SessionBridgeCallback(private val onSessionCleared: () -> Unit) {

    @JavascriptInterface
    fun onSessionCleared() {
        onSessionCleared()
    }

    companion object {
        /**
         * Wrap [Storage.prototype.removeItem] / `clear` so a write that
         * drops the session key notifies the shell.
         *
         * Patching the prototype (rather than the instance) is what
         * makes it work: the SPA reaches the key through a reference it
         * obtained before our script ran.
         */
        val OBSERVER_SCRIPT: String = """
            (function () {
              var KEY = '${SessionBridge.TOKEN_KEY}';
              var proto = window.Storage && window.Storage.prototype;
              if (!proto || proto.__ticketAppObserved) return;
              proto.__ticketAppObserved = true;

              var native = window.${SessionBridge.INTERFACE_NAME};
              var notify = function () {
                try {
                  if (window.sessionStorage.getItem(KEY) === null &&
                      native && native.onSessionCleared) {
                    native.onSessionCleared();
                  }
                } catch (e) { /* storage unavailable */ }
              };

              var removeItem = proto.removeItem;
              proto.removeItem = function (key) {
                var result = removeItem.apply(this, arguments);
                if (key === KEY) notify();
                return result;
              };

              var clear = proto.clear;
              proto.clear = function () {
                var result = clear.apply(this, arguments);
                notify();
                return result;
              };
            })();
        """.trimIndent()
    }
}