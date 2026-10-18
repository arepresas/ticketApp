package com.ticketapp.android.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-side holder for the BFF session JWT.
 *
 * ## Why native storage at all when the SPA keeps the token in
 * sessionStorage
 *
 * The WebView's `sessionStorage` dies with the WebView process, so a
 * token that only lived there would force a Google round-trip on every
 * cold start. The native shell owns the login, so it owns the token:
 * the WebView receives it through a document-start script
 * ([com.ticketapp.android.web.SessionBridge]) and never becomes the
 * source of truth.
 *
 * ## Why the ciphertext scheme instead of EncryptedSharedPreferences
 *
 * `androidx.security.crypto` is in maintenance: `MasterKey` and
 * `EncryptedSharedPreferences` are deprecated in 1.1.0 and the whole
 * artifact no longer receives features. The replacement is a keystore
 * key plus an app-private file, which is what this class does — roughly
 * a hundred lines instead of a deprecated dependency, with no
 * behavioural loss:
 *
 *  - the AES-256 key is generated **inside** the Android Keystore and
 *    is non-exportable, so it never exists in the app's process memory
 *    and cannot be read off a rooted device or out of a backup;
 *  - the JWT is encrypted with AES-GCM, so a tampered ciphertext fails
 *    the authentication tag instead of decrypting to attacker-chosen
 *    bytes;
 *  - nothing sensitive lands in SharedPreferences, so the file needs
 *    no backup exclusion to stay safe.
 *
 * ## Failure behaviour
 *
 * A key invalidated by a biometric-enrollment change or a restored
 * backup makes decryption throw. That is treated as "no session"
 * rather than a crash: the user re-authenticates and the session heals
 * itself. Corrupt or absent files behave the same way.
 */
class SessionStore(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Decrypt and return the BFF session JWT, or `null` when signed
     * out (or the stored value can no longer be read).
     */
    fun read(): String? {
        val payload = runCatching {
            val file = file()
            if (!file.exists()) return null
            val bytes = file.readBytes()
            if (bytes.size <= IV_LENGTH) return null
            decipher(bytes)
        }.getOrNull()

        // Unreadable ciphertext is indistinguishable from "signed out"
        // for this app's purposes, so drop it rather than retrying on
        // every launch.
        if (payload == null) clear()
        return payload
    }

    /** Persist a freshly minted session, replacing any previous one. */
    fun save(token: String) {
        runCatching {
            val sealed = seal(token.encodeToByteArray())
            file().writeBytes(sealed)
        }
    }

    /**
     * Drop the local token. The caller is responsible for having
     * already attempted the server-side revocation
     * (`POST /api/auth/logout`) — clearing must work even when that
     * network call failed, which is exactly the expired-session case.
     */
    fun clear() {
        runCatching { file().delete() }
    }

    private fun file() = java.io.File(appContext.filesDir, FILE_NAME)

    /** AES-GCM encrypt; layout is `IV ‖ ciphertext+tag`. */
    private fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plaintext)
    }

    private fun decipher(sealed: ByteArray): String? {
        val iv = sealed.copyOfRange(0, IV_LENGTH)
        val body = sealed.copyOfRange(IV_LENGTH, sealed.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(body))
    }

    /**
     * Fetch — creating on first use — the non-exportable keystore key.
     *
     * `setUserAuthenticationRequired(false)` is deliberate: the token
     * unlocks the whole account, and gating it behind a device
     * credential would mean a biometric prompt (or a device-lockout
     * lockout) on every cold start. The threat this defends against is
     * a stolen, unlocked, non-rooted device, where Keystore already
     * blocks extraction.
     */
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.secretKey
            ?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // Deliberately unset: see the KDoc above.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE_NAME = "session.enc"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "ticketapp.session.key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128

        /** Standard GCM nonce length for a 12-byte IV. */
        val IV_LENGTH = 12
    }
}