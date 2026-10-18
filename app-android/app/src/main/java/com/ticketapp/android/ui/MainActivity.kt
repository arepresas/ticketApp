package com.ticketapp.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ticketapp.android.BuildConfig
import com.ticketapp.android.web.WebAppScreen

/**
 * Single-activity shell.
 *
 * Renders exactly one of three things — a progress bar while a stored
 * session is being validated, the native Google sign-in screen, or the
 * WebView running the SPA. Which one is [AppViewModel]'s decision, not
 * the view's, so no navigation component is needed for a two-state
 * machine.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel: AppViewModel = viewModel(factory = AppViewModel.Factory)
            val state by viewModel.state.collectAsStateWithLifecycle()

            TicketAppTheme {
                when (val current = state) {
                    is AppState.Starting -> {
                        // Cold start with a stored token: the WebView must not
                        // mount before the BFF has confirmed the session.
                        SplashScreen(modifier = Modifier.fillMaxSize())
                    }

                    is AppState.SignedOut, is AppState.SignInFailed -> LoginScreen(
                        busy = current is AppState.SigningIn ||
                            current is AppState.Starting,
                        error = (current as? AppState.SignInFailed)?.reason,
                        origin = BuildConfig.WEB_ORIGIN,
                        onSignIn = viewModel::signIn,
                        onDismissError = viewModel::dismissError,
                        modifier = Modifier.fillMaxSize(),
                    )

                    is AppState.SigningIn -> LoginScreen(
                        busy = true,
                        error = null,
                        origin = BuildConfig.WEB_ORIGIN,
                        onSignIn = viewModel::signIn,
                        onDismissError = viewModel::dismissError,
                        modifier = Modifier.fillMaxSize(),
                    )

                    is AppState.SignedIn -> {
                        val token = viewModel.sessionToken
                        if (token == null) {
                            // Defensive: a signed-in state with no token
                            // would mount a WebView with nothing to seed.
                            LoginScreen(
                                busy = false,
                                error = null,
                                origin = BuildConfig.WEB_ORIGIN,
                                onSignIn = viewModel::signIn,
                                onDismissError = viewModel::dismissError,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            WebAppScreen(
                                context = applicationContext,
                                origin = BuildConfig.WEB_ORIGIN,
                                token = token,
                                onSessionCleared = viewModel::onSessionClearedInWebView,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}