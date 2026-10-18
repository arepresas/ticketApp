package com.ticketapp.android.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Native Google Sign-In, returning the raw `id_token` the BFF verifies.
 *
 * ## Why Credential Manager and not the Google Sign-In button
 *
 * `com.google.android.gms:play-services-auth` (the button API) is
 * deprecated; Credential Manager is Google's replacement and hands back
 * the ID token directly, which is precisely this back end's contract —
 * `POST /api/auth/google` takes a Google `id_token` and mints a BFF
 * session from it.
 *
 * ## Why this has to be native
 *
 * The SPA signs in with Google Identity Services in popup mode, which
 * Android's WebView refuses: Google blocks OAuth flows in embedded
 * user agents (`disallowed_useragent`), so a WebView-only app cannot
 * log in. Doing the credential request natively and handing the
 * resulting token to the WebView (see
 * [com.ticketapp.android.web.WebAppScreen]) keeps one login path for
 * both the shell and the SPA.
 *
 * ## Client id
 *
 * [serverClientId] must be the OAuth **web** client id — the same
 * value the BFF has in `GOOGLE_CLIENT_ID` and the SPA builds as
 * `VITE_GOOGLE_CLIENT_ID`. An Android client id would mint a token the
 * BFF's audience check rejects.
 */
class GoogleSignIn(
    private val context: Context,
    private val serverClientId: String,
) {

    /**
     * Request a Google credential and return its ID token.
     *
     * @return the Google `id_token` (a JWT) or a [SignInFailure]
     * describing why the user cannot be signed in. Cancellation is a
     * failure the UI should stay quiet about — the user backed out.
     */
    suspend fun requestIdToken(): Result<String> {
        if (serverClientId.isBlank() || serverClientId.startsWith(REPLACE_ME_PREFIX)) {
            return Result.failure(
                IllegalStateException("Google server client id is not configured (ticketapp.googleServerClientId)"),
            )
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            // false → the account chooser lists every account on the
            // device. BFF identities are Google accounts by
            // construction (`.rules/security.md`: Google is the only
            // identity provider), so there is no non-Google account
            // worth filtering towards.
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId)
            // Explicit opt-in beats silent auto-select: silently
            // attaching the previously used account would skip the
            // account-consent step the web flow performs.
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val response: GetCredentialResponse =
                CredentialManager.create(context).getCredential(context, request)

            // Validate the credential through the SDK's own parser
            // rather than trusting the type of whatever came back.
            // `createFrom` rejects a malformed response, and pairing it
            // with the request is what lets the SDK check that the
            // token belongs to the account and audience we asked for.
            // (androidx lint's CredentialManagerSignInWithGoogle
            // enforces exactly this pairing.)
            val credential = GoogleIdTokenCredential.createFrom(response.credential.data)
            Result.success(credential.idToken)
        } catch (e: GetCredentialCancellationException) {
            Result.failure(SignInFailure.Cancelled(e))
        } catch (e: NoCredentialException) {
            Result.failure(SignInFailure.NoGoogleAccount(e))
        } catch (e: GetCredentialProviderConfigurationException) {
            Result.failure(SignInFailure.Configuration(e))
        } catch (e: GetCredentialUnknownException) {
            Result.failure(SignInFailure.Unknown(e))
        }
    }

    private companion object {
        const val REPLACE_ME_PREFIX = "REPLACE_ME"
    }
}

/**
 * Typed sign-in failures, so the UI can react to *why* a login stopped
 * instead of showing one generic error string.
 */
sealed class SignInFailure(cause: Throwable) : Exception(cause) {
    /** The user dismissed the account chooser — not an error. */
    class Cancelled(cause: Throwable) : SignInFailure(cause)

    /** The device has no usable Google account. */
    class NoGoogleAccount(cause: Throwable) : SignInFailure(cause)

    /** Credential Manager has no usable provider (no Play Services, misconfigured manifest). */
    class Configuration(cause: Throwable) : SignInFailure(cause)

    class Unknown(cause: Throwable) : SignInFailure(cause)
}