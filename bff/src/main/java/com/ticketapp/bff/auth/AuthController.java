package com.ticketapp.bff.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.ticketapp.bff.security.JwtToAuthenticatedUserConverter;
import lombok.extern.slf4j.Slf4j;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/auth")
@Slf4j
public class AuthController {

    /** Mirror of {@code bff.jwt.ttl-hours:24}. Kept on the controller so
     * the test slice can override it without touching properties. */
    private final Duration sessionTtl;
    private final GoogleTokenVerifier google;
    private final UserRepository users;
    private final SessionRepository sessions;
    private final JwtEncoder jwtEncoder;

    public AuthController(
            @Value("${bff.jwt.ttl-hours:24}") long ttlHours,
            GoogleTokenVerifier google,
            UserRepository users,
            SessionRepository sessions,
            JwtEncoder jwtEncoder) {
        this.sessionTtl = Duration.ofHours(ttlHours);
        this.google = google;
        this.users = users;
        this.sessions = sessions;
        this.jwtEncoder = jwtEncoder;
    }

    /**
     * Login: exchange a Google {@code id_token} for a BFF session JWT.
     * Public route (permitAll in {@link com.ticketapp.bff.security.SecurityConfig}).
     */
    @PostMapping("/google")
    public ResponseEntity<?> exchangeGoogleIdToken(@Valid @RequestBody GoogleLoginRequest req) {
        AtomicReference<String> reason = new AtomicReference<>();
        AtomicReference<String> details = new AtomicReference<>();
        Optional<GoogleIdToken.Payload> verified = google.verify(req.idToken(),
                (r, d) -> { reason.set(r); details.set(d); });
        if (verified.isEmpty()) {
            String r = reason.get() == null ? "verify_failed" : reason.get();
            String d = details.get() == null ? "" : details.get();
            log.warn("Google id_token rejected: reason={} details={}", r, d);
            return ResponseEntity.status(401).body(new ErrorBody("invalid_google_token", r, d));
        }
        GoogleIdToken.Payload p = verified.get();
        AuthenticatedUser user = users.upsertFromGoogle(
                p.getSubject(),
                p.getEmail(),
                (String) p.get("name"),
                (String) p.get("picture")
        );
        Issued issued = issue(user);
        return ResponseEntity.ok(new SessionResponse(
                issued.token(),
                new UserDto(user.id(), user.email(), user.name(), user.pictureUrl())
        ));
    }

    /**
     * Returns the currently-authenticated user. Reads the principal
     * straight off Spring Security's
     * {@link org.springframework.security.core.context.SecurityContextHolder}
     * — no more {@code (AuthenticatedUser) req.getAttribute("auth.user")}.
     */
    @GetMapping("/me")
    public ResponseEntity<UserDto> me() {
        AuthenticatedUser user = com.ticketapp.bff.security.CurrentUser.get();
        return ResponseEntity.ok(new UserDto(user.id(), user.email(), user.name(), user.pictureUrl()));
    }

    /**
     * Logout: revoke the session row. The next request carrying
     * this token fails {@link com.ticketapp.bff.security.SessionExistsValidator}
     * with 401.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtToAuthenticatedUserConverter.BearerTokenAuthentication bta) {
            sessions.revoke(bta.jti());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Mint a new session row + JWT.
     *
     * <p>Token shape (matches what the decoder expects — see
     * {@link com.ticketapp.bff.security.JwtToAuthenticatedUserConverter}):
     * <ul>
     *   <li>{@code sub} = userId (UUID string)</li>
     *   <li>{@code jti} = session id (UUID string, matches the row in auth_sessions)</li>
     *   <li>{@code iat}, {@code exp} — standard.</li>
     * </ul>
     */
    private Issued issue(AuthenticatedUser user) {
        Instant now = Instant.now();
        Instant exp = now.plus(sessionTtl);
        UUID jti = UUID.randomUUID();
        sessions.save(new SessionRepository.Session(jti, user.id(), now, exp, null));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ticketapp-bff")
                .issuedAt(now)
                .expiresAt(exp)
                .subject(user.id().toString())
                .id(jti.toString())
                .build();
        JwtEncoderParameters params = JwtEncoderParameters.from(
                JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),
                claims);
        Jwt jwt = jwtEncoder.encode(params);
        return new Issued(jwt.getTokenValue(), jti, exp);
    }

    public record GoogleLoginRequest(@NotBlank String idToken) { }

    public record SessionResponse(String token, UserDto user) { }

    public record UserDto(UUID id, String email, String name, String pictureUrl) { }

    /**
     * Error body. {@code message} is a stable code the front-end can switch on;
     * {@code reason} and {@code details} are diagnostic fields useful in dev.
     */
    public record ErrorBody(String message, String reason, String details) { }

    /** Internal: what {@code POST /api/auth/google} hands back to the SPA. */
    public record Issued(String token, UUID jti, Instant expiresAt) { }
}