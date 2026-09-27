package com.ticketapp.bff.security;

import com.ticketapp.domain.identity.AuthenticatedUser;
import com.ticketapp.domain.identity.SessionRepository;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Converts a verified Spring Security {@link Jwt} into the application's
 * {@link AbstractAuthenticationToken} whose principal is the
 * {@link AuthenticatedUser} the JWT refers to.
 *
 * <p>Spring's default {@code JwtAuthenticationConverter} produces a
 * token whose principal is the {@code Jwt} itself — useful for the
 * resource-server default but inconvenient here because every
 * controller would have to extract the user id from the claims
 * manually. By doing the conversion eagerly we expose
 * {@code AuthenticatedUser} as the principal, which keeps controllers
 * one-liner-clean: {@code currentUser().id()}.
 *
 * <p>Single round-trip per request: the session liveness check
 * (row exists, not revoked, not expired) and the user lookup run as
 * one join query ({@link SessionRepository#findActiveUserByJti}),
 * so a revoked or deleted session is rejected here even before its
 * JWT expiry — no separate validator filter needed. The {@code sub}
 * claim is re-checked against the joined user id so a token can
 * never ride another user's session row.
 *
 * <p>Three rejections happen here:
 * <ol>
 *   <li>{@code sub} must parse as a UUID.</li>
 *   <li>{@code jti} must parse as a UUID.</li>
 *   <li>The {@code jti} must resolve to a live session whose owner
 *       matches {@code sub}. Stale tokens (session revoked,
 *       expired, deleted — or user deleted, which empties the
 *       join) are rejected with 401 instead of letting the
 *       controller 500 on a missing principal.</li>
 * </ol>
 */
@Component
@Slf4j
public class JwtToAuthenticatedUserConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    static final String CLAIM_JTI = "jti";

    private final SessionRepository sessions;

    public JwtToAuthenticatedUserConverter(SessionRepository sessions) {
        this.sessions = sessions;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId = parseUuidClaim(jwt, "sub", "user");
        UUID jti = parseUuidClaim(jwt, CLAIM_JTI, "session");

        AuthenticatedUser user = sessions.findActiveUserByJti(jti)
                .filter(u -> u.id().equals(userId))
                .orElseThrow(() -> {
                    log.warn("JWT rejected: no live session {} for user {}", jti, userId);
                    return new InvalidBearerTokenException(
                            "session has been revoked, expired, or does not exist");
                });

        Collection<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_USER"));

        // Stamp `authenticatedAt` for downstream audit log lines —
        // not part of the security contract, just a convenience.
        return new BearerTokenAuthentication(jwt, authorities, user, jti);
    }

    private static UUID parseUuidClaim(Jwt jwt, String name, String what) {
        String raw = jwt.getClaimAsString(name);
        if (raw == null) {
            throw new InvalidBearerTokenException("missing " + what + " id claim");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidBearerTokenException(what + " id claim is not a UUID");
        }
    }

    /**
     * Authentication token whose principal is the resolved
     * {@link AuthenticatedUser}. Exposes the {@code jti} so the logout
     * endpoint can revoke the corresponding session row.
     */
    public static final class BearerTokenAuthentication extends AbstractAuthenticationToken {

        private final Jwt jwt;
        private final AuthenticatedUser principal;
        private final UUID jti;

        BearerTokenAuthentication(Jwt jwt,
                                   Collection<? extends GrantedAuthority> authorities,
                                   AuthenticatedUser principal,
                                   UUID jti) {
            super(authorities);
            this.jwt = jwt;
            this.principal = principal;
            this.jti = jti;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            // The token itself — Spring's resource server has already
            // verified signature + exp before this constructor runs,
            // so exposing the raw token here is informational only.
            return jwt.getTokenValue();
        }

        @Override
        public Object getPrincipal() {
            return principal;
        }

        @Override
        public String getName() {
            return principal.id().toString();
        }

        public UUID jti() {
            return jti;
        }

        public Instant expiresAt() {
            return jwt.getExpiresAt();
        }
    }
}
