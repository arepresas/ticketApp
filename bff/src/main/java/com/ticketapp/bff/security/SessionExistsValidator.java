package com.ticketapp.bff.security;

import com.ticketapp.bff.auth.SessionRepository;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Validates that the JWT's {@code jti} claim points at a row in
 * {@code auth_sessions} that is still active (not revoked, not
 * expired, owner matches the {@code sub} claim).
 *
 * <p>Spring's {@code oauth2ResourceServer().jwt()} chains the
 * configured validators. This one sits alongside Nimbus' defaults
 * (signature + exp) so every protected request hits the DB once per
 * call. The cost is one indexed PK lookup per request — fine at the
 * current scale (single BFF instance, modest RPS). If we ever need to
 * scale horizontally we cache the session existence for a few
 * seconds (Caffeine, keyed on jti) and accept a small revocation
 * lag.
 *
 * <p>Why a server-side session row at all when the JWT already
 * carries its own {@code exp}? Because JWT exp can't be revoked —
 * a stolen token is good until expiry. The session row is the
 * revocation channel: {@code POST /api/auth/logout} flips
 * {@code revoked_at} and the next request from that token fails here.
 */
@Component
@Slf4j
public class SessionExistsValidator implements OAuth2TokenValidator<Jwt> {

    static final String CLAIM_JTI = "jti";
    static final String CLAIM_SUB = "sub";

    private static final OAuth2Error ERROR = new OAuth2Error(
            "invalid_token",
            "session has been revoked, expired, or does not exist",
            null);

    private final SessionRepository sessions;

    public SessionExistsValidator(SessionRepository sessions) {
        this.sessions = sessions;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String jtiRaw = jwt.getClaimAsString(CLAIM_JTI);
        String subRaw = jwt.getClaimAsString(CLAIM_SUB);
        if (jtiRaw == null || subRaw == null) {
            return OAuth2TokenValidatorResult.failure(ERROR);
        }

        UUID jti;
        UUID sub;
        try {
            jti = UUID.fromString(jtiRaw);
            sub = UUID.fromString(subRaw);
        } catch (IllegalArgumentException e) {
            return OAuth2TokenValidatorResult.failure(ERROR);
        }

        return sessions.findByJti(jti)
                .filter(s -> s.revokedAt() == null)
                .filter(s -> s.expiresAt().isAfter(Instant.now()))
                .filter(s -> s.userId().equals(sub))
                .map(s -> OAuth2TokenValidatorResult.success())
                .orElseGet(() -> OAuth2TokenValidatorResult.failure(ERROR));
    }

    /**
     * Convert the validator failure into a {@link InvalidBearerTokenException}
     * so Spring's resource server translates it to a clean 401 instead
     * of a generic 500. Useful in unit tests too — see
     * {@code SecurityConfigIT}.
     */
    public static InvalidBearerTokenException asException() {
        return new InvalidBearerTokenException(ERROR.getDescription());
    }
}