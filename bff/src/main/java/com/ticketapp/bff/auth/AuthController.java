package com.ticketapp.bff.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final GoogleTokenVerifier google;
    private final UserRepository users;
    private final SessionTokenService sessions;

    public AuthController(GoogleTokenVerifier google,
                          UserRepository users,
                          SessionTokenService sessions) {
        this.google = google;
        this.users = users;
        this.sessions = sessions;
    }

    @PostMapping("/google")
    public ResponseEntity<?> exchangeGoogleIdToken(@Valid @RequestBody GoogleLoginRequest req) {
        // Capture the rejection reason so the controller can return it on the 401
        // body (helps the front-end user / operator understand why the login failed
        // during dev). It is also logged at WARN.
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
        SessionTokenService.Issued issued = sessions.issue(user);
        return ResponseEntity.ok(new SessionResponse(
                issued.token(),
                new UserDto(user.id(), user.email(), user.name(), user.pictureUrl())
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest req) {
        AuthenticatedUser user = (AuthenticatedUser) req.getAttribute("auth.user");
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(new UserDto(user.id(), user.email(), user.name(), user.pictureUrl()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest req) {
        UUIDPair ids = (UUIDPair) req.getAttribute("auth.session");
        if (ids != null) {
            sessions.revoke(ids.jti());
        }
        return ResponseEntity.noContent().build();
    }

    public record GoogleLoginRequest(@NotBlank String idToken) { }

    public record SessionResponse(String token, UserDto user) { }

    public record UserDto(java.util.UUID id, String email, String name, String pictureUrl) { }

    /**
     * Error body. {@code message} is a stable code the front-end can switch on;
     * {@code reason} and {@code details} are diagnostic fields useful in dev.
     */
    public record ErrorBody(String message, String reason, String details) { }

    /** Internal: (userId, jti) attached by the auth filter. */
    public record UUIDPair(java.util.UUID userId, java.util.UUID jti) { }
}
