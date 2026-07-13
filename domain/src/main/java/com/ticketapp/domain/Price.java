package com.ticketapp.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-ticket price snapshot. A {@link Product} can have many
 * {@code Price} rows over time — one per ticket where the price was
 * observed — so analytics can compute "average price of milk across
 * all my Lidl tickets" without losing per-ticket detail.
 *
 * <p>Persistence uses {@code UNIQUE (product_id, ticket_id, amount)}
 * to make this idempotent: re-validating a ticket with the same
 * amount reuses the existing row. A different amount (e.g. a
 * loyalty-discount variant on the same ticket) creates a new price
 * row, so multiple distinct amounts can coexist for one
 * (product, ticket) pair when the AI legitimately sees them.
 */
public record Price(
        UUID id,
        UUID productId,
        UUID ticketId,
        BigDecimal amount,
        Instant createdAt,
        Instant updatedAt
) {
    public Price {
        if (id == null) throw new NullPointerException("id");
        if (productId == null) throw new NullPointerException("productId");
        if (ticketId == null) throw new NullPointerException("ticketId");
        if (amount == null) throw new NullPointerException("amount");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must be >= 0");
        }
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
    }
}
