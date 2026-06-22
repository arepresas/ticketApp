package com.ticketapp.bff.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Decodes the BFF session JWT (if any) and attaches the resolved
 * {@link AuthenticatedUser} / session id to the request attributes.
 *
 * Public endpoints (POST /api/auth/google) work without a token.
 * Anything else that needs auth reads {@code auth.user} off the request.
 *
 * Critically: this filter NEVER blocks the request. Endpoints decide
 * whether they require auth. This keeps /api/auth/google reachable
 * while still letting protected routes return 401 cleanly.
 */
@Component
public class SessionFilter extends OncePerRequestFilter {

    static final String ATTR_USER = "auth.user";
    static final String ATTR_SESSION = "auth.session";

    private final SessionTokenService sessions;
    private final UserRepository users;

    public SessionFilter(SessionTokenService sessions, UserRepository users) {
        this.sessions = sessions;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {

        String header = req.getHeader("authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length()).trim();
            Optional<SessionTokenService.Verified> verified = sessions.verify(token);
            verified.ifPresent(v ->
                    users.findById(v.userId()).ifPresent(user -> {
                        req.setAttribute(ATTR_USER, user);
                        req.setAttribute(ATTR_SESSION,
                                new AuthController.UUIDPair(v.userId(), v.jti()));
                    }));
        }
        chain.doFilter(req, res);
    }
}