package com.ticketapp.android.net

import com.ticketapp.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP client for the auth slice of the BFF.
 *
 * Deliberately **not** a Retrofit abstraction: this module makes three
 * calls and the rest of the API surface (tickets, dashboard, catalogue)
 * is exercised by the SPA inside the WebView, not by native code. A
 * hand-written OkHttp client keeps that truth visible — when the native
 * surface grows, a real client library earns its place.
 *
 * ## Base URL
 *
 * [BuildConfig.WEB_ORIGIN] is the **front's** origin, never the BFF's.
 * Both the prod nginx (`front/Dockerfile`) and the Vite dev proxy
 * forward the `/api` prefix to the BFF, so the native calls stay
 * same-origin with the WebView and ADR 0008's single-client topology is
 * preserved without a CORS allow-list entry.
 *
 * ## Threading
 *
 * Every call suspends onto [Dispatchers.IO]; callers stay on the main
 * thread.
 */
class BffAuthClient(
    private val webOrigin: String = BuildConfig.WEB_ORIGIN,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Exchange a Google `id_token` for a BFF session.
     *
     * The BFF verifies the Google signature and audience
     * (`GOOGLE_CLIENT_ID`) before minting a session row + JWT, so a
     * token this returns is only obtainable with a credential the BFF
     * accepted.
     *
     * @throws IOException on transport failure or any non-2xx status.
     */
    suspend fun signInWithGoogle(idToken: String): SessionResponse =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(GoogleLoginRequest.serializer(), GoogleLoginRequest(idToken))
                .toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url("$webOrigin$API_AUTH_GOOGLE")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val payload = response.body.string()
                if (!response.isSuccessful) {
                    throw IOException("sign-in failed: HTTP ${response.code}")
                }
                json.decodeFromString(SessionResponse.serializer(), payload)
            }
        }

    /**
     * Validate a stored token and return its owner.
     *
     * Called on every cold start: the JWT's own `exp` is not the only
     * expiry — the `auth_sessions` row can be revoked (logout on
     * another device, admin action) while the token still verifies
     * cryptographically, and the BFF answers 401 in that case.
     *
     * @return the user, or `null` when the token is no longer valid.
     */
    suspend fun fetchMe(token: String): AuthUser? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$webOrigin$API_AUTH_ME")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful ->
                        json.decodeFromString(
                            AuthUser.serializer(),
                            response.body.string(),
                        )
                    // 401/403 mean "this token is dead" — a value the
                    // caller acts on by clearing the session, not an
                    // error worth propagating.
                    response.code == HTTP_UNAUTHORIZED || response.code == HTTP_FORBIDDEN -> null
                    else -> throw IOException("session lookup failed: HTTP ${response.code}")
                }
            }
        }

    /**
     * Revoke the session server-side.
     *
     * Best-effort by contract: the caller clears the local token
     * regardless of the outcome, because a failed revoke must never
     * strand the user in an authenticated-looking shell. Revocation
     * also expires on its own via the `auth_sessions.expires_at`
     * column, so this is an optimisation, not a security boundary.
     */
    suspend fun logout(token: String): Boolean =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$webOrigin$API_AUTH_LOGOUT")
                .header("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody(null))
                .build()

            runCatching { httpClient.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
        }

    companion object {
        private const val API_AUTH_GOOGLE = "/api/auth/google"
        private const val API_AUTH_ME = "/api/auth/me"
        private const val API_AUTH_LOGOUT = "/api/auth/logout"

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}