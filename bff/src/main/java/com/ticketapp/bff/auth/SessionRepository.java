package com.ticketapp.bff.auth;

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

    /** Returns the live sessions for a user (not revoked, not expired). */
    java.util.List<Session> findActiveByUser(UUID userId);

    /** Mark a single session revoked. Idempotent. */
    void revoke(UUID jti);

    record Session(UUID jti,
                   UUID userId,
                   Instant issuedAt,
                   Instant expiresAt,
                   Instant revokedAt) { }
}