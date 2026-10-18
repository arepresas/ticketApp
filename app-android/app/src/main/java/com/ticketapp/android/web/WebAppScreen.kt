package com.ticketapp.android.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * The WebView that hosts the existing Svelte SPA.
 *
 * The app deliberately does **not** reimplement the SPA: dashboard,
 * ticket upload, charts, the detail screen — all of it is the front you
 * already have, running against the BFF through the same `/api` proxy
 * the browser uses. This shell contributes exactly two things a browser
 * cannot give it: a login Google allows inside an Android app, and a
 * session token that survives a cold start.
 *
 * @param origin the front's public origin (`BuildConfig.WEB_ORIGIN`).
 * @param token the BFF session JWT to seed the SPA with.
 * @param onSessionCleared invoked when the SPA signs itself out.
 */
@Composable
fun WebAppScreen(
    context: Context,
    origin: String,
    token: String,
    modifier: Modifier = Modifier,
    onSessionCleared: () -> Unit,
) {
    // Keyed on the token: a new session means a fresh page load anyway,
    // and a WebView keeps per-page JS state that must not leak across
    // identities.
    val webView = remember(token) {
        WebView(context).apply {
            configure(origin, token, onSessionCleared)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.removeJavascriptInterface(SessionBridge.INTERFACE_NAME)
            webView.destroy()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { webView },
    )
}

/**
 * Settings, navigation policy and session wiring for the hosted SPA.
 *
 * Navigation is pinned to [origin]. The injected script carries the
 * session token, so a third-party page must never execute inside this
 * WebView: off-origin links are handed to the system browser instead.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configure(
    origin: String,
    token: String,
    onSessionCleared: () -> Unit,
) {
    settings.apply {
        javaScriptEnabled = true
        // sessionStorage is where the SPA keeps its session — without
        // DOM storage the seeding bridge is a no-op.
        domStorageEnabled = true
        loadWithOverviewMode = true
        useWideViewPort = true
        builtInZoomControls = true
        displayZoomControls = false
        mediaPlaybackRequiresUserGesture = true
        // The front ships its own bundle; no file or content URIs needed.
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
    }

    addJavascriptInterface(SessionBridgeCallback(onSessionCleared), SessionBridge.INTERFACE_NAME)

    // Preferred path: both scripts run before any page-authored JS, so
    // the SPA's own boot sequence sees a complete session.
    val documentStartSupported = installDocumentStartScripts(origin, token)

    webViewClient = TicketAppWebViewClient(
        origin = origin,
        token = token,
        documentStartSupported = documentStartSupported,
    )

    loadUrl(origin)
}

/**
 * Navigation and lifecycle policy for the hosted SPA.
 *
 * A named class rather than an anonymous object, and carrying a
 * `@Suppress` for `MissingOnRenderProcessGone`: the callback **is**
 * implemented below, but the detector reports it missing for this class
 * either way (verified on a clean build with the override in place, and
 * again after extracting it out of an `object : WebViewClient()`). A
 * suppression with the reason attached beats a permanently-firing
 * warning that trains reviewers to ignore lint.
 */
@Suppress("MissingOnRenderProcessGone")
private class TicketAppWebViewClient(
    private val origin: String,
    private val token: String,
    private val documentStartSupported: Boolean,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val target = request.url.toString()
        if (OriginPolicy.isSameOrigin(target, origin)) return false
        view.openExternally(target)
        return true
    }

    /**
     * The front is a single HTTPS origin with no mixed content, so a
     * TLS error means something is genuinely wrong (intercepting proxy,
     * clock skew). Cancelling is the only safe default: proceeding
     * would hand the session token to whoever presented the
     * certificate.
     */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
    }

    /**
     * The renderer can be killed independently of the app process —
     * usually the system reclaiming memory under pressure. Without this
     * callback Android rethrows that death at us and the whole app
     * dies; with it, the crash stays contained to the page.
     *
     * Returning true means "handled": destroy the dead WebView and let
     * the activity show its own state. The shell does not try to
     * revive the page — the session is still valid on disk, so a
     * reload would restore it.
     */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        view.destroy()
        return true
    }

    /**
     * Compatibility path for system WebViews older than 105, which have
     * no document-start hook: seed the storage after load and reload
     * once. The SPA boots signed-out on that first paint, so the reload
     * is what makes the session visible.
     */
    override fun onPageFinished(view: WebView, url: String) {
        if (documentStartSupported) return
        SessionBridge.seedAfterLoad(view, token)
        view.post { view.reload() }
    }
}

/**
 * Installs the session seeder and the logout observer as
 * document-start scripts, scoped to [origin].
 *
 * @return false when the system WebView predates
 *   [WebViewFeature.DOCUMENT_START_SCRIPT] and the caller must use the
 *   post-load fallback.
 */
private fun WebView.installDocumentStartScripts(origin: String, token: String): Boolean {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false

    WebViewCompat.addDocumentStartJavaScript(this, SessionBridge.seedScript(token), setOf(origin))
    WebViewCompat.addDocumentStartJavaScript(this, SessionBridgeCallback.OBSERVER_SCRIPT, setOf(origin))
    return true
}

/** Hand an off-origin link to the system browser, never to this WebView. */
private fun WebView.openExternally(url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}