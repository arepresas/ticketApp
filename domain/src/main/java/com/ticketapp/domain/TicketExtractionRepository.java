package com.ticketapp.domain;

import java.util.List;
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
    Optional<TicketExtraction> findByTicketId(UUID ticketId);

    /**
     * Persist a new extraction. The primary key is the ticket id, so
     * re-extracting the same ticket is a constraint violation on the
     * caller side — callers must check {@link #findByTicketId} first
     * or accept the exception. We intentionally do not expose an
     * upsert here: re-extraction should be an explicit, observable
     * action (delete then insert), not a silent overwrite.
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
     * that condition. Throws an unchecked exception on the
     * not-found path; the BFF translates to 404.
     */
    TicketExtraction replace(TicketExtraction extraction);

    /**
     * Return the ticket ids of all tickets that already have an
     * extraction. Used by the scheduler to filter its candidate set
     * in a single round-trip instead of N point lookups.
     */
    List<UUID> findExtractedTicketIds();
}