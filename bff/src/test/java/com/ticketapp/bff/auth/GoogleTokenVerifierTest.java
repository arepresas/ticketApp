package com.ticketapp.bff.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoogleTokenVerifierTest {

    private GoogleIdTokenVerifier delegate;
    private GoogleTokenVerifier verifier;

    @BeforeEach
    void setUp() {
        delegate = mock(GoogleIdTokenVerifier.class);
        verifier = new GoogleTokenVerifier(delegate, "test-client-id");
    }

    @Test
    void returnsEmptyOnBlankToken() {
        assertThat(verifier.verify(null)).isEmpty();
        assertThat(verifier.verify("")).isEmpty();
        assertThat(verifier.verify("   ")).isEmpty();
    }

    @Test
    void returnsEmptyWhenDelegateRejects() throws Exception {
        when(delegate.verify(anyString())).thenReturn(null);
        assertThat(verifier.verify("bogus.token")).isEmpty();
    }

    @Test
    void returnsEmptyWhenIssuerIsNotGoogle() throws Exception {
        GoogleIdToken.Payload p = new GoogleIdToken.Payload();
        p.setIssuer("evil.example.com");
        p.setAudience(List.of("test-client-id"));
        p.setExpirationTimeSeconds(System.currentTimeMillis() / 1000 + 60);
        GoogleIdToken token = mock(GoogleIdToken.class);
        when(token.getPayload()).thenReturn(p);
        when(delegate.verify(anyString())).thenReturn(token);

        assertThat(verifier.verify("any.token")).isEmpty();
    }

    @Test
    void returnsEmptyWhenAudienceMismatches() throws Exception {
        GoogleIdToken.Payload p = new GoogleIdToken.Payload();
        p.setIssuer("accounts.google.com");
        p.setAudience(List.of("other-client-id"));
        p.setExpirationTimeSeconds(System.currentTimeMillis() / 1000 + 60);
        GoogleIdToken token = mock(GoogleIdToken.class);
        when(token.getPayload()).thenReturn(p);
        when(delegate.verify(anyString())).thenReturn(token);

        assertThat(verifier.verify("any.token")).isEmpty();
    }

    @Test
    void returnsPayloadOnValidToken() throws Exception {
        GoogleIdToken.Payload p = new GoogleIdToken.Payload();
        p.setIssuer("https://accounts.google.com");
        p.setAudience(List.of("test-client-id"));
        p.setSubject("google-sub-123");
        p.setEmail("alice@example.com");
        p.setEmailVerified(true);
        p.set("name", "Alice");
        p.setExpirationTimeSeconds(System.currentTimeMillis() / 1000 + 60);
        GoogleIdToken token = mock(GoogleIdToken.class);
        when(token.getPayload()).thenReturn(p);
        when(delegate.verify(anyString())).thenReturn(token);

        var result = verifier.verify("good.token");
        assertThat(result).isPresent();
        assertThat(result.get().getSubject()).isEqualTo("google-sub-123");
        assertThat(result.get().getEmail()).isEqualTo("alice@example.com");
        assertThat(result.get().get("name")).isEqualTo("Alice");
    }

    @Test
    void returnsEmptyWhenDelegateThrows() throws Exception {
        Mockito.doThrow(new GeneralSecurityException("network"))
                .when(delegate).verify(anyString());
        assertThat(verifier.verify("any.token")).isEmpty();

        Mockito.doThrow(new IOException("timeout"))
                .when(delegate).verify(anyString());
        assertThat(verifier.verify("any.token")).isEmpty();

        // google-oauth-client's GoogleIdToken.parse throws IllegalArgumentException
        // on malformed JWTs (e.g. "bogus" or random strings). The verifier must
        // swallow it and return empty, never propagate.
        Mockito.doThrow(new IllegalArgumentException("not a JWT"))
                .when(delegate).verify(anyString());
        assertThat(verifier.verify("bogus.token")).isEmpty();
    }

    @Test
    void sinkReceivesEmptyTokenReason() {
        String[] reason = new String[1];
        String[] details = new String[1];
        verifier.verify(null, (r, d) -> { reason[0] = r; details[0] = d; });
        assertThat(reason[0]).isEqualTo("empty_token");
        assertThat(details[0]).contains("null or blank");
    }

    @Test
    void sinkReceivesAudienceMismatchReason() throws Exception {
        GoogleIdToken.Payload p = new GoogleIdToken.Payload();
        p.setIssuer("https://accounts.google.com");
        p.setAudience(List.of("other-client-id"));
        p.setExpirationTimeSeconds(System.currentTimeMillis() / 1000 + 60);
        GoogleIdToken token = mock(GoogleIdToken.class);
        when(token.getPayload()).thenReturn(p);
        when(delegate.verify(anyString())).thenReturn(token);

        String[] reason = new String[1];
        String[] details = new String[1];
        verifier.verify("good.jwt", (r, d) -> { reason[0] = r; details[0] = d; });

        assertThat(reason[0]).isEqualTo("audience_mismatch");
        assertThat(details[0]).contains("expected=");
        assertThat(details[0]).contains("actual=");
    }

    @Test
    void sinkReceivesBadIssuerReason() throws Exception {
        GoogleIdToken.Payload p = new GoogleIdToken.Payload();
        p.setIssuer("evil.example.com");
        p.setAudience(List.of("test-client-id"));
        p.setExpirationTimeSeconds(System.currentTimeMillis() / 1000 + 60);
        GoogleIdToken token = mock(GoogleIdToken.class);
        when(token.getPayload()).thenReturn(p);
        when(delegate.verify(anyString())).thenReturn(token);

        String[] reason = new String[1];
        verifier.verify("good.jwt", (r, d) -> { reason[0] = r; });

        assertThat(reason[0]).isEqualTo("bad_issuer");
    }

    @Test
    void sinkReceivesVerifyFailedReasonWhenDelegateReturnsNull() throws Exception {
        when(delegate.verify(anyString())).thenReturn(null);

        String[] reason = new String[1];
        verifier.verify("good.jwt", (r, d) -> { reason[0] = r; });

        assertThat(reason[0]).isEqualTo("verify_failed");
    }

    @Test
    void productionConstructorRejectsEmptyAudience() {
        // The @Value default for google.client-id is the empty string. Operators
        // who forget GOOGLE_CLIENT_ID in the BFF's env would otherwise silently
        // 401 every login. Fail fast at boot instead. We exercise the public
        // single-arg production constructor which has the empty-audience check
        // (the test-only two-arg constructor skips it so unit tests can stub).
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new GoogleTokenVerifier(""));
    }
}