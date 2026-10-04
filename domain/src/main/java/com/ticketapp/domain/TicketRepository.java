package com.ticketapp.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;


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
 * <p>The single unscoped read used to live here as
 * {@code findOpenForExtraction} — it now lives on
 * {@link TicketExtractionQueue} so the ownership bypass is visible
 * in the type system. The controller path MUST NOT depend on that
 * port.
 */
public interface TicketRepository {

    /**
     * Owner-scoped lookup. Returns {@link Optional#empty()} when the
     * ticket does not exist, <em>or</em> when it exists but is owned
     * by a different user, <em>or</em> when it is soft-deleted — the
     * three cases are indistinguishable from the BFF's perspective
     * to avoid leaking existence.
     */
    Optional<Ticket> findById(long id, long ownerId);

    /**
     * Persist (insert or guarded update). The owner comes from the
     * entity, so the caller's identity must already be encoded in
     * {@link Ticket#ownerId()}.
     *
     * <p>Optimistic locking: the write only applies when the row's
     * {@code version} still matches the entity's; on success the
     * returned copy carries the bumped version. A stale copy throws
     * {@link com.ticketapp.domain.exceptions.OptimisticLockException}
     * instead of silently overwriting the winner — callers decide
     * whether to re-read and retry or to let the other writer win.
     * A missing row is inserted with version 0 enforced (the
     * caller's version is ignored on this path) and the returned
     * copy reflects the stored row.
     */
    Ticket save(Ticket ticket);

    /**
     * Owner-scoped soft delete. Flips the row to
     * {@link Ticket.Status#DELETED} instead of removing it, so the
     * audit trail (extractions, catalogue lines) stays intact.
     * Deleted rows are invisible to every other port method.
     * Returns {@code true} when a row was flipped, {@code false}
     * when the ticket does not exist, is owned by someone else, or
     * is already deleted. Controller translates {@code false} to
     * 404.
     */
    boolean deleteById(long id, long ownerId);

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
    List<Ticket> findByStatusIn(Set<Ticket.Status> statuses, long ownerId);

    /**
     * Owner-scoped status filter returning lightweight
     * {@link TicketSummary} rows — same filter as
     * {@link #findByStatusIn} but without the receipt blobs (no
     * {@code file_data}, no {@code ocr_text}; the size travels as
     * {@code sizeBytes}). Used by the dashboard list views so a
     * backlog of tickets never loads megabytes of attachments into
     * memory. Single-ticket paths ({@link #findById}) keep
     * returning the full {@link Ticket}.
     *
     * @param statuses non-empty set of statuses to match. Passing an
     *                 empty set is allowed and returns an empty list.
     */
    List<TicketSummary> findSummariesByStatusIn(Set<Ticket.Status> statuses, long ownerId);
}
