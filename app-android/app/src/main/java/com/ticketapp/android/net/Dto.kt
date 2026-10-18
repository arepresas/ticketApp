package com.ticketapp.android.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for the three BFF endpoints the native shell calls.
 *
 * These mirror `bff/.../auth/AuthController` records and
 * `front/src/lib/auth/types.ts`:
 *  - `POST /api/auth/google` → [SessionResponse]
 *  - `GET  /api/auth/me`     → [AuthUser]
 *
 * Kept as `@Serializable` data classes rather than hand-rolled JSON so
 * a field rename on the BFF side becomes a compile-time failure instead
 * of a silent `null`.
 */

/** Request body of `POST /api/auth/google`. */
@Serializable
data class GoogleLoginRequest(val idToken: String)

/**
 * Response of `POST /api/auth/google`.
 *
 * `token` is the **BFF session JWT**, not the Google id_token — the
 * Google credential is single-use and audience-bound, this one is what
 * every later call sends as `Authorization: Bearer`.
 */
@Serializable
data class SessionResponse(
    val token: String,
    val user: AuthUser,
)

/**
 * The authenticated user, as returned by the BFF.
 *
 * `id` is the BFF's numeric `app_users.id` (the SPA maps its own view
 * of this onto Google's `sub` — either way it is opaque to the client;
 * ownership is enforced server-side from the JWT).
 */
@Serializable
data class AuthUser(
    val id: Long,
    val email: String,
    val name: String,
    @SerialName("pictureUrl") val pictureUrl: String? = null,
)