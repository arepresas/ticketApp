package com.ticketapp.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Outbound port defined by domain. Infrastructure implements it.
 *
 * <p><b>Ownership scoping.</b> Every read/write path that serves a
 * user request takes the caller's {@code ownerId} so the
 * implementation can refuse cross-tenant access at the SQL layer.
 * Returning an empty {@link Optional} / list when the owner does
 * not match is the contract — the BFF translates that to a 404, so
 * existence itself is not leaked across tenants.
 *
 * <p>The single unscoped read is
 * {@link #findOpenForExtraction(int)}, which exists for the
 * system-level scheduler (it processes tickets from any owner and
 * runs without a user session). The controller path MUST NOT call
 * it.
 */
public interface TicketRepository {

    /**
     * Owner-scoped lookup. Returns {@link Optional#empty()} when the
     * ticket does not exist <em>or</em> when it exists but is owned
     * by a different user — the two cases are indistinguishable from
     * the BFF's perspective to avoid leaking existence.
     */
    Optional<Ticket> findById(UUID id, UUID ownerId);

    /**
     * System-scope query: return up to {@code limit} tickets in
     * {@link Ticket.Status#OPEN} that have no extraction row yet,
     * ordered oldest-first so the oldest pending work drains first.
     * Called by {@code TicketExtractionJob} (cron-driven, no user
     * session). The controller path MUST NOT call this — it bypasses
     * ownership.
     */
    List<Ticket> findOpenForExtraction(int limit);

    /**
     * Persist (insert on conflict update). The owner comes from the
     * entity, so the caller's identity must already be encoded in
     * {@link Ticket#ownerId()}.
     */
    Ticket save(Ticket ticket);

    /**
     * Owner-scoped delete. Returns {@code true} when a row was
     * removed, {@code false} when the ticket does not exist or is
     * owned by someone else. Controller translates {@code false} to
     * 404.
     */
    boolean deleteById(UUID id, UUID ownerId);

    /**
     * Owner-scoped status filter. Used by the dashboard to surface
     * "pending" tickets (anything that hasn't reached a terminal
     * state). Result order is implementation-defined but stable
     * enough for the UI to rely on it (the JDBC impl returns newest
     * first by {@code created_at}).
     *
     * @param statuses non-empty set of statuses to match. Passing an
     *                 empty set is allowed and returns an empty list.
     */
    List<Ticket> findByStatusIn(Set<Ticket.Status> statuses, UUID ownerId);
}
