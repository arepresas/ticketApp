package com.ticketapp.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for {@link TicketExtraction} persistence. Defined by
 * the domain; infrastructure implements it with JDBC. The contract is
 * deliberately small — only the operations the extraction scheduler
 * and future read paths need.
 */
public interface TicketExtractionRepository {

    /**
     * Look up the extraction for a given ticket. Empty when the ticket
     * has never been processed (or has been deleted — the FK is
     * {@code ON DELETE CASCADE}).
     */
    Optional<TicketExtraction> findByTicketId(UUID ticketId, UUID ownerId);

    /**
     * Persist a new extraction. The primary key is the ticket id;
     * re-saving an already-extracted ticket is a no-op (the
     * implementation ignores the duplicate) so a scheduler retry
     * racing the first write cannot fail the tick. Callers keep the
     * {@link #findByTicketId} pre-check as the fast path — the
     * no-op is the safety net for the race window, not the primary
     * guard.
     */
    TicketExtraction save(TicketExtraction extraction);

    /**
     * User-driven edit through the detail screen. The caller supplies
     * the new mutable fields ({@code merchant}, {@code purchaseDate},
     * {@code category}, {@code products}, {@code totalAmount},
     * {@code currency}); the AI's audit fields ({@code model},
     * {@code extractedAt}, {@code rawResponse},
     * {@code extractionPayload}) are preserved server-side so the
     * "extracted by X on Y" attribution stays accurate even when the
     * user corrects a line item or price.
     *
     * <p>Refuses when no row exists for the ticket — the detail
     * screen disables edit when the AI hasn't run yet, and
     * silently turning a missing extraction into one would mask
     * that condition. Throws
     * {@link com.ticketapp.domain.exceptions.ResourceNotFoundException}
     * on the not-found path; the BFF translates it to 404.
     */
    TicketExtraction replace(TicketExtraction extraction);
}
