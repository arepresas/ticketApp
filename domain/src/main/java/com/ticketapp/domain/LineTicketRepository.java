package com.ticketapp.domain;

import java.util.List;
import java.util.Optional;


/**
 * Outbound port for the {@link LineTicket} per-ticket line table.
 *
 * <p>One row per {@code (ticket_id, product_id)} pair; re-validating
 * the same ticket with the same product refreshes the existing row
 * in place via ON CONFLICT DO UPDATE. Cancellation does not delete
 * rows (historical pricing stays intact for analytics).
 */
public interface LineTicketRepository {

    Optional<LineTicket> findByTicketAndProduct(long ticketId, long productId);

    /**
     * All line rows for one ticket, ordered by creation time. Used
     * by the catalogue read API to surface a ticket's validated
     * lines back to the detail screen in the order the user saw
     * them. Empty when the ticket has no normalised lines (i.e.
     * has never been validated).
     */
    List<LineTicket> findByTicketId(long ticketId, long ownerId);

    /**
     * Upsert one per-ticket line. Same {@code (ticket_id,
     * product_id)} pair overwrites the existing row's quantity,
     * {@code line_total}, shop/price references and {@code updated_at}.
     */
    LineTicket save(LineTicket line);

    /**
     * Remove every line row for one ticket.
     *
     * <p>Used by the normaliser before re-emitting the catalogue on a
     * re-validation: the upsert only collapses rows that share a
     * {@code product_id}, so when the user renamed a line (new
     * normalised name → new product master row) the stale row for the
     * old product would otherwise survive next to the new one and the
     * DONE view would show both. Deleting first turns re-normalisation
     * into a true replace for this ticket. Cross-ticket history is
     * untouched — only rows of {@code ticketId}.
     */
    void deleteByTicketId(long ticketId);
}
