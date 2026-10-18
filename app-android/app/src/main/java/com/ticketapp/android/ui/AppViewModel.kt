package com.ticketapp.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ticketapp.android.BuildConfig
import com.ticketapp.android.auth.GoogleSignIn
import com.ticketapp.android.auth.SessionStore
import com.ticketapp.android.auth.SignInFailure
import com.ticketapp.android.net.AuthUser
import com.ticketapp.android.net.BffAuthClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The shell's whole state machine: **signed out → native login →
 * WebView**, plus the reverse path when the SPA signs itself out.
 *
 * Holds no Android framework types beyond the application context, so
 * it survives configuration changes — which matters because the WebView
 * is not recreated cheaply and a rotation must not re-run the Google
 * round-trip.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionStore = SessionStore(application)
    private val authClient = BffAuthClient()
    private val googleSignIn = GoogleSignIn(application, BuildConfig.GOOGLE_SERVER_CLIENT_ID)

    private val _state = MutableStateFlow<AppState>(AppState.Starting)
    val state: StateFlow<AppState> = _state.asStateFlow()

    /**
     * Token whose expiry has been proven server-side. Held separately
     * from [AppState] because the WebView re-reads it on every
     * (re)creation while the state only changes on login/logout.
     */
    @Volatile
    var sessionToken: String? = null
        private set

    init {
        restoreSession()
    }

    /**
     * Cold start: trust the stored JWT only after the BFF confirms the
     * session row is still live.
     *
     * Signature validity is not enough — `auth_sessions.revoked_at`
     * can be set while the token still verifies, and the BFF then
     * answers 401. Validating here is what turns "signed out on another
     * device" into the correct first screen instead of a WebView full
     * of failed API calls.
     */
    private fun restoreSession() {
        viewModelScope.launch {
            val stored = sessionStore.read()
            if (stored == null) {
                _state.value = AppState.SignedOut
                return@launch
            }

            val user = runCatching { authClient.fetchMe(stored) }.getOrNull()
            if (user != null) {
                sessionToken = stored
                _state.value = AppState.SignedIn(user)
            } else {
                // Dead token (expired, revoked, deleted session) — drop
                // it without a logout call: the server already considers
                // it invalid.
                sessionStore.clear()
                sessionToken = null
                _state.value = AppState.SignedOut
            }
        }
    }

    /**
     * Full sign-in: Google credential → BFF session → WebView.
     *
     * The BFF exchange is the security-relevant step: it verifies the
     * Google signature, audience and expiry before minting the session,
     * so a token that fails here never reaches [SessionStore].
     */
    fun signIn() {
        if (_state.value is AppState.SigningIn) return

        _state.value = AppState.SigningIn
        viewModelScope.launch {
            val googleToken = googleSignIn.requestIdToken().getOrElse { failure ->
                _state.value = when (failure) {
                    // Dismissal is not an error state — stay signed out
                    // without a banner.
                    is SignInFailure.Cancelled -> AppState.SignedOut
                    else -> AppState.SignInFailed(describe(failure))
                }
                return@launch
            }

            runCatching { authClient.signInWithGoogle(googleToken) }
                .onSuccess { session ->
                    sessionStore.save(session.token)
                    sessionToken = session.token
                    _state.value = AppState.SignedIn(session.user)
                }
                .onFailure {
                    _state.value = AppState.SignInFailed("Sign-in was rejected by the server.")
                }
        }
    }

    /**
     * Native-initiated sign-out: revoke server-side, then clear local.
     * The WebView is torn down by the state change, so the SPA cannot
     * resurrect the session it still has in `sessionStorage`.
     */
    fun signOut() {
        val token = sessionToken ?: sessionStore.read()
        _state.value = AppState.SignedOut
        sessionToken = null
        sessionStore.clear()

        if (token != null) {
            viewModelScope.launch { authClient.logout(token) }
        }
    }

    /**
     * The SPA cleared its own session (header "Sign out", or a 401
     * bubbling into `auth:expired`). Mirror that locally — without the
     * revoke call, because the SPA has already sent it.
     */
    fun onSessionClearedInWebView() {
        sessionToken = null
        sessionStore.clear()
        _state.value = AppState.SignedOut
    }

    /** Dismiss a sign-in error without leaving the signed-out screen. */
    fun dismissError() {
        if (_state.value is AppState.SignInFailed) {
            _state.value = AppState.SignedOut
        }
    }

    private fun describe(failure: Throwable): String = when (failure) {
        is SignInFailure.NoGoogleAccount -> "No Google account found on this device."
        is SignInFailure.Configuration -> "Google Sign-In is unavailable on this device."
        is IllegalStateException -> failure.message ?: "Google Sign-In is not configured."
        else -> "Google Sign-In failed. Please try again."
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as Application
                AppViewModel(app)
            }
        }

        private val APPLICATION_KEY: CreationExtras.Key<Application> =
            ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY
    }
}

/**
 * Everything the UI can render. Deliberately a closed hierarchy: the
 * WebView, the login screen and the error banner are the only three
 * things this app can be.
 */
sealed interface AppState {

    /** Cold start, validating any stored session. */
    data object Starting : AppState

    data object SignedOut : AppState

    /** Credential request or BFF exchange in flight. */
    data object SigningIn : AppState

    data class SignInFailed(val reason: String) : AppState

    /** Session is live; the WebView takes over the UI. */
    data class SignedIn(val user: AuthUser) : AppState
}