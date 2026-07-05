package com.ticketapp.bff.security;

import com.ticketapp.bff.auth.AuthenticatedUser;
import com.ticketapp.bff.auth.UserRepository;
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
 * <p>Three checks happen here, all of which would also be enforced by
 * the {@link SessionExistsValidator} but doing them at conversion time
 * gives better error messages and avoids a needless DB round-trip
 * when the token claims are clearly malformed:
 * <ol>
 *   <li>{@code sub} must parse as a UUID.</li>
 *   <li>{@code jti} must parse as a UUID.</li>
 *   <li>The user id ({@code sub}) must resolve to a live row in
 *       {@code app_users}. Stale tokens (user deleted, account
 *       deactivated) are rejected with 401 instead of letting the
 *       controller 500 on a missing principal.</li>
 * </ol>
 *
 * <p>Revocation lives in {@link SessionExistsValidator} because it
 * depends on a session row the conversion layer doesn't have access
 * to in the standard chain (the validator runs first). The user
 * existence check lives here because {@link UserRepository} is the
 * domain-side source of truth.
 */
@Component
@Slf4j
public class JwtToAuthenticatedUserConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    static final String CLAIM_JTI = "jti";

    private final UserRepository users;

    public JwtToAuthenticatedUserConverter(UserRepository users) {
        this.users = users;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId = parseUuidClaim(jwt, "sub", "user");
        UUID jti = parseUuidClaim(jwt, CLAIM_JTI, "session");

        AuthenticatedUser user = users.findById(userId).orElseThrow(() -> {
            log.warn("JWT refers to unknown user {} (jti {})", userId, jti);
            return new InvalidBearerTokenException("user no longer exists");
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