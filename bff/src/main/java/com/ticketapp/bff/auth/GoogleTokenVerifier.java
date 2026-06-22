package com.ticketapp.bff.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Optional;

/**
 * Verifies Google-issued id_tokens by checking:
 *   1. signature against Google's published JWKs,
 *   2. {@code aud} matches our OAuth client id,
 *   3. {@code iss} is accounts.google.com,
 *   4. {@code exp} is in the future (60s clock skew tolerance).
 *
 * <p>Exposes a single seam ({@link #verify(String, VerifyResultSink)}) that
 * reports the rejection reason on the provided sink, so the controller
 * can surface it in the 401 response and the operator can see it in the
 * server log. The {@link #verify(String)} overload preserves the old
 * fire-and-forget contract for tests and other call sites.
 */
@Service
public class GoogleTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenVerifier.class);

    private final GoogleIdTokenVerifier delegate;
    private final String expectedAudience;

    @Autowired
    public GoogleTokenVerifier(@Value("${google.client-id}") String expectedAudience) {
        this.expectedAudience = expectedAudience;
        // Sanity-check at boot: empty audience means every token will be rejected
        // because the audience check compares against expectedAudience.equals(...).
        // The {@code @Value} default in application.yml is the empty string; if
        // the operator forgot to set GOOGLE_CLIENT_ID, fail fast with a clear
        // message rather than silently 401-ing every login.
        if (expectedAudience == null || expectedAudience.isBlank()) {
            throw new IllegalStateException(
                    "google.client-id is not set. Define GOOGLE_CLIENT_ID in the BFF's environment "
                            + "(see .env.example). Without it, every id_token will be rejected as invalid.");
        }
        try {
            this.delegate = new GoogleIdTokenVerifier.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance())
                    .setAudience(Collections.singletonList(expectedAudience))
                    .build();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException(
                    "Cannot initialise GoogleIdTokenVerifier", e);
        }
        log.info("GoogleTokenVerifier ready (audience={}…)", mask(expectedAudience));
    }

    /** Test-only constructor: injects a mock verifier. */
    GoogleTokenVerifier(GoogleIdTokenVerifier delegate, String expectedAudience) {
        this.delegate = delegate;
        this.expectedAudience = expectedAudience == null ? "" : expectedAudience;
    }

    /**
     * @return the verified {@link GoogleIdToken.Payload}, or empty if the
     *         token is malformed, expired, has a wrong audience, or fails
     *         Google's signature check.
     */
    public Optional<GoogleIdToken.Payload> verify(String idToken) {
        VerifyResultSink sink = (reason, details) -> {};
        return verify(idToken, sink);
    }

    /**
     * Same as {@link #verify(String)} but reports the rejection reason to
     * {@code sink} so the caller can log it or return it to the user. Useful
     * for debugging 401s in development.
     */
    public Optional<GoogleIdToken.Payload> verify(String idToken, VerifyResultSink sink) {
        if (idToken == null || idToken.isBlank()) {
            sink.onReject("empty_token", "idToken is null or blank");
            return Optional.empty();
        }
        try {
            GoogleIdToken parsed = delegate.verify(idToken);
            if (parsed == null) {
                sink.onReject("verify_failed", "GoogleIdTokenVerifier returned null (bad signature, wrong audience, or expired)");
                return Optional.empty();
            }
            GoogleIdToken.Payload p = parsed.getPayload();
            String actualAudience = p.getAudienceAsList() == null
                    ? null
                    : p.getAudienceAsList().stream().findFirst().orElse(null);
            if (!expectedAudience.equals(actualAudience)) {
                sink.onReject("audience_mismatch",
                        "expected=" + mask(expectedAudience) + " actual=" + mask(actualAudience));
                return Optional.empty();
            }
            String issuer = p.getIssuer();
            if (!"accounts.google.com".equals(issuer) && !"https://accounts.google.com".equals(issuer)) {
                sink.onReject("bad_issuer", "issuer=" + issuer);
                return Optional.empty();
            }
            return Optional.of(p);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            sink.onReject("exception", e.getClass().getSimpleName() + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Callback used by the controller to receive rejection details for diagnostics. */
    @FunctionalInterface
    public interface VerifyResultSink {
        void onReject(String reason, String details);
    }

    /** First 12 chars of the audience, rest masked. Safe to log. */
    private static String mask(String s) {
        if (s == null) return "<null>";
        if (s.length() <= 12) return s + "…";
        return s.substring(0, 12) + "…";
    }
}
