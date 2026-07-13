package com.ticketapp.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for the {@link LineTicket} per-ticket line table.
 *
 * <p>One row per {@code (ticket_id, product_id)} pair; re-validating
 * the same ticket with the same product refreshes the existing row
 * in place via ON CONFLICT DO UPDATE. Cancellation does not delete
 * rows (historical pricing stays intact for analytics).
 */
public interface LineTicketRepository {

    Optional<LineTicket> findByTicketAndProduct(UUID ticketId, UUID productId);

    /**
     * All line rows for one ticket, ordered by creation time. Used
     * by the catalogue read API to surface a ticket's validated
     * lines back to the detail screen in the order the user saw
     * them. Empty when the ticket has no normalised lines (i.e.
     * has never been validated).
     */
    List<LineTicket> findByTicketId(UUID ticketId);

    /**
     * Upsert one per-ticket line. Same {@code (ticket_id,
     * product_id)} pair overwrites the existing row's quantity,
     * {@code line_total}, shop/price references and {@code updated_at}.
     */
    LineTicket save(LineTicket line);
}
