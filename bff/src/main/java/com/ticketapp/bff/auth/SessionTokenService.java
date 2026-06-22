package com.ticketapp.bff.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Signs and verifies the BFF-issued session JWT (HS256).
 *
 * Token claims:
 *   sub  = userId (UUID)
 *   jti  = session id (UUID, matches a row in auth_sessions)
 *   exp  = expiry instant
 *   iat  = issued-at instant
 *
 * Verification also enforces a session-row lookup (via {@link SessionRepository})
 * so revoked sessions can be rejected before their exp.
 */
@Service
public class SessionTokenService {

    private final SecretKey signingKey;
    private final Duration ttl;
    private final SessionRepository sessions;

    public SessionTokenService(
            @Value("${bff.jwt.secret}") String secret,
            @Value("${bff.jwt.ttl-hours:24}") long ttlHours,
            SessionRepository sessions) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "bff.jwt.secret must be at least 32 chars (256 bits) for HS256");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofHours(ttlHours);
        this.sessions = sessions;
    }

    /** Issue a new session row + JWT. */
    public Issued issue(AuthenticatedUser user) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        UUID jti = UUID.randomUUID();
        sessions.save(new SessionRepository.Session(jti, user.id(), now, exp, null));
        String token = Jwts.builder()
                .subject(user.id().toString())
                .id(jti.toString())
                .claim("iat", now.getEpochSecond())
                .claim("exp", exp.getEpochSecond())
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
        return new Issued(token, jti, exp);
    }

    /**
     * Verify signature + expiry + revocation. Returns empty if any check fails
     * (callers translate empty into 401).
     */
    public Optional<Verified> verify(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        try {
            Claims c = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            UUID jti = UUID.fromString(c.getId());
            UUID userId = UUID.fromString(c.getSubject());
            return sessions.findByJti(jti)
                    .filter(s -> s.revokedAt() == null)
                    .filter(s -> s.expiresAt().isAfter(Instant.now()))
                    .filter(s -> s.userId().equals(userId))
                    .map(s -> new Verified(userId, jti));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Revoke a session (logout). */
    public void revoke(UUID jti) {
        sessions.revoke(jti);
    }

    public record Issued(String token, UUID jti, Instant expiresAt) { }
    public record Verified(UUID userId, UUID jti) { }
}