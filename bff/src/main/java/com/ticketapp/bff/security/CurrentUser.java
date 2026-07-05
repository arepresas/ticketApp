package com.ticketapp.bff.security;

import com.ticketapp.bff.auth.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Helpers for reading the authenticated principal out of Spring
 * Security's {@link SecurityContextHolder}.
 *
 * <p>Controllers used to do this via
 * {@code (AuthenticatedUser) req.getAttribute("auth.user")} on the
 * {@code SessionFilter} output. After the Spring Security migration
 * the principal lives on the {@code Authentication} object, so a
 * short helper keeps the call sites readable without scattering
 * context-lookup code across every controller.
 */
public final class CurrentUser {

    private CurrentUser() { }

    /**
     * Returns the {@link AuthenticatedUser} stored in the current
     * security context. Throws 401 when no authentication is
     * present — should never happen on a {@code .authenticated()}
     * route because Spring rejects the request before the
     * controller runs, but the cast + null-guard here keep the
     * behaviour explicit if a route is ever misconfigured.
     */
    public static AuthenticatedUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        Object principal = auth.getPrincipal();
        if (!(principal instanceof AuthenticatedUser user)) {
            // Unexpected: an Authentication on a protected route
            // without our principal. Surface as 401 (not 500) so a
            // misconfigured security chain doesn't crash the
            // controller.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "authentication required");
        }
        return user;
    }
}