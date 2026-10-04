package com.ticketapp.domain.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Tracks server-side issued sessions so we can revoke them (logout,
 * security incident) even before the JWT expiry.
 *
 * MVP: no pruning job — stale rows are harmless and small.
 */
public interface SessionRepository {

    void save(Session session);

    Optional<Session> findByJti(UUID jti);

    /**
     * Single-round-trip authentication lookup: the user owning a
     * live session (not revoked, not expired), joined through
     * {@code app_users}. Empty when the session is missing,
     * revoked, expired, or its user vanished. The resource-server
     * converter uses this so every protected request costs one
     * indexed lookup instead of two (session row + user row).
     */
    Optional<AuthenticatedUser> findActiveUserByJti(UUID jti);

    /** Returns the live sessions for a user (not revoked, not expired). */
    java.util.List<Session> findActiveByUser(long userId);

    /** Mark a single session revoked. Idempotent. */
    void revoke(UUID jti);

    record Session(UUID jti,
                   long userId,
                   Instant issuedAt,
                   Instant expiresAt,
                   Instant revokedAt) { }
}