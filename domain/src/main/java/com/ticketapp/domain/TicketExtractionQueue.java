package com.ticketapp.domain;

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
}
