package com.ticketapp.bff.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionTokenServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-32+";
    private SessionRepository sessions;
    private SessionTokenService service;

    @BeforeEach
    void setUp() {
        sessions = mock(SessionRepository.class);
        service = new SessionTokenService(SECRET, 1, sessions);
    }

    @Test
    void rejectsSecretShorterThan32Chars() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> new SessionTokenService("short", 1, sessions)
        );
    }

    @Test
    void issuePersistsSessionRowAndReturnsJwt() {
        UUID userId = UUID.randomUUID();
        AuthenticatedUser user = new AuthenticatedUser(
                userId, "sub", "a@b.c", "Alice", null, Instant.now(), Instant.now());

        SessionTokenService.Issued issued = service.issue(user);

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.expiresAt()).isAfter(Instant.now());
        assertThat(issued.jti()).isNotNull();
        verify(sessions).save(any(SessionRepository.Session.class));
    }

    @Test
    void verifyAcceptsFreshNonRevokedSession() {
        UUID userId = UUID.randomUUID();
        Instant exp = Instant.now().plus(Duration.ofHours(1));

        SessionTokenService.Issued issued = service.issue(
                new AuthenticatedUser(userId, "sub", "a@b.c", "Alice", null, Instant.now(), Instant.now()));

        when(sessions.findByJti(any())).thenReturn(Optional.of(
                new SessionRepository.Session(issued.jti(), userId, Instant.now(), exp, null)));

        Optional<SessionTokenService.Verified> v = service.verify(issued.token());
        assertThat(v).isPresent();
        assertThat(v.get().userId()).isEqualTo(userId);
        assertThat(v.get().jti()).isEqualTo(issued.jti());
    }

    @Test
    void verifyRejectsRevokedSession() {
        SessionTokenService.Issued issued = service.issue(
                new AuthenticatedUser(UUID.randomUUID(), "sub", "a@b.c", "A", null, Instant.now(), Instant.now()));

        when(sessions.findByJti(any())).thenReturn(Optional.of(
                new SessionRepository.Session(issued.jti(), UUID.randomUUID(),
                        Instant.now(), Instant.now().plusSeconds(60), Instant.now())));

        assertThat(service.verify(issued.token())).isEmpty();
    }

    @Test
    void verifyRejectsTokenSignedWithDifferentKey() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "different-secret-different-secret-32+".getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String foreign = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(otherKey, Jwts.SIG.HS256)
                .compact();

        assertThat(service.verify(foreign)).isEmpty();
        verify(sessions, never()).findByJti(any());
    }

    @Test
    void verifyRejectsExpiredToken() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String expired = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now.minusSeconds(3600)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThat(service.verify(expired)).isEmpty();
    }

    @Test
    void verifyRejectsBlankToken() {
        assertThat(service.verify(null)).isEmpty();
        assertThat(service.verify("")).isEmpty();
        assertThat(service.verify("not.a.jwt")).isEmpty();
    }

    @Test
    void revokeDelegatesToRepository() {
        UUID jti = UUID.randomUUID();
        service.revoke(jti);
        verify(sessions).revoke(jti);
    }
}