package com.ticketapp.bff.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reusable {@code @TestConfiguration} that stubs
 * {@link GoogleTokenVerifier} to accept only {@code "valid.id.token"}.
 *
 * <p>Lives in the {@code bff.auth} package so the package-private
 * {@code GoogleTokenVerifier} constructor is reachable. Other ITs
 * {@code @Import} this configuration to bypass the real Google
 * verification path.
 *
 * <p>Reused by:
 * <ul>
 *   <li>{@code AuthControllerIT}</li>
 *   <li>{@code TicketControllerIT}</li>
 * </ul>
 */
@TestConfiguration
public class TestGoogleConfig {

    /** Stable audience used by the stub verifier — anything matches. */
    public static final String AUDIENCE = "ignored-but-checked-locally";

    /** Token string the stub verifier accepts; anything else is rejected. */
    public static final String VALID_TOKEN = "valid.id.token";

    @Bean
    @Primary
    GoogleTokenVerifier googleTokenVerifier() throws Exception {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject("google-sub-stub");
        payload.setEmail("stub@example.com");
        payload.setEmailVerified(true);
        payload.set("name", "Stub User");
        payload.set("picture", "https://example.com/stub.png");
        payload.setIssuer("https://accounts.google.com");
        payload.setAudience(List.of(AUDIENCE));
        payload.setExpirationTimeSeconds(Instant.now().getEpochSecond() + 3600);
        payload.setIssuedAtTimeSeconds(Instant.now().getEpochSecond());

        GoogleIdToken token = mock(GoogleIdToken.class);
        when(token.getPayload()).thenReturn(payload);

        GoogleIdTokenVerifier delegate = mock(GoogleIdTokenVerifier.class);
        when(delegate.verify(anyString())).thenReturn(null);
        when(delegate.verify(VALID_TOKEN)).thenReturn(token);

        return new GoogleTokenVerifier(delegate, AUDIENCE);
    }
}