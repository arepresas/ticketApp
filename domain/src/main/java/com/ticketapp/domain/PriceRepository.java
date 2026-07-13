package com.ticketapp.domain;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for the {@link Price} per-ticket snapshot.
 *
 * <p>A {@link Product} can have multiple {@code Price} rows — one
 * per ticket where it was observed. Re-validating a ticket with the
 * same {@code amount} reuses the same row (UNIQUE on
 * {@code (product_id, ticket_id, amount)}); a genuinely different
 * amount on the same (product, ticket) tuple creates a second row.
 */
public interface PriceRepository {

    /**
     * Find a price row for the given (product, ticket, amount) tuple.
     * Empty when no such row exists — callers mint a new one with
     * {@link #save(Price)}.
     */
    Optional<Price> findByProductAndTicket(UUID productId, UUID ticketId, BigDecimal amount);

    /**
     * Batch lookup keyed on the price id. Same single-round-trip
     * pattern as {@link ProductRepository#findAllByIds}.
     */
    Map<UUID, Price> findAllByIds(Collection<UUID> ids);

    /**
     * Insert (or refresh) a price row. The {@code (product_id,
     * ticket_id, amount)} UNIQUE index drives the upsert; re-saving
     * the same tuple bumps {@code updated_at} on the existing row
     * but keeps the same id.
     */
    Price save(Price price);
}
