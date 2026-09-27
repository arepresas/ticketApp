package com.ticketapp.domain;

import java.time.Instant;
import java.util.List;

/**
 * System-scope ticket source for the extraction scheduler.
 *
 * <p>Split from {@link TicketRepository} so the ownership bypass is
 * visible in the type system: the cron job injects this port (no
 * user session, cross-owner), while every user-serving path injects
 * {@link TicketRepository} (owner-scoped). A controller can only
 * reach the system query by explicitly depending on this type,
 * which code review will catch. Implemented by the same JDBC class
 * as the owner-scoped port.
 */
public interface TicketExtractionQueue {

    /**
     * Return up to {@code limit} tickets in
     * {@link Ticket.Status#OPEN} that have no extraction row yet,
     * ordered oldest-first so the oldest pending work drains first.
     * The "no extraction yet" filter runs in SQL (anti-join), not
     * in memory. The controller path MUST NOT call this — it
     * bypasses ownership.
     */
    List<Ticket> findOpenForExtraction(int limit);

    /**
     * Move abandoned claims back to {@link Ticket.Status#OPEN} and
     * return them.
     *
     * <p>A ticket is <em>abandoned</em> when it sits in
     * {@link Ticket.Status#IN_ANALYSIS} with no extraction row and its
     * last extraction attempt is older than
     * {@code attemptedBefore}. The orchestrator flips a ticket to
     * {@code IN_ANALYSIS} in its own short transaction and only later
     * writes the extraction row and flips to {@code IN_PROGRESS} in a
     * second one. A crash, an OOM or a deploy in that window would
     * otherwise strand the ticket forever: the scheduler only ever
     * looks at {@code OPEN}, so nothing would ever re-queue it.
     *
     * <p>Re-queuing (rather than deleting the marker) keeps the work
     * idempotent: a slow-but-alive worker that finishes afterwards
     * loses the version-guarded update and the row is re-processed
     * exactly once, because the extraction table has a 1:1 primary
     * key on {@code ticket_id}.
     *
     * <p>The "no extraction row" filter runs in SQL. The controller
     * path MUST NOT call this — it bypasses ownership.
     *
     * @param attemptedBefore exclusive lower bound on the last
     *                        attempt timestamp
     * @param limit           maximum number of tickets to re-queue,
     *                        oldest attempt first
     * @return the tickets that were moved back to {@code OPEN}
     */
    List<Ticket> requeueAbandonedAnalysis(Instant attemptedBefore, int limit);
}
