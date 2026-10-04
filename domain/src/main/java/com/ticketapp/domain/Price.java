package com.ticketapp.domain;

import java.math.BigDecimal;
import java.time.Instant;


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
        long id,
        long productId,
        long ticketId,
        BigDecimal amount,
        Instant createdAt,
        Instant updatedAt
) {
    public Price {
        if (amount == null) throw new NullPointerException("amount");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must be >= 0");
        }
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
    }
    /**
     * Attach the id assigned by the database on insert. Ids come from
     * Postgres identity columns, so an entity leaves the domain with
     * {@code id == 0} and gains its real id on the way back from
     * {@code save()}. Only the adapter calls this.
     */
    public Price withId(long newId) {
        return new Price(newId, productId, ticketId, amount, createdAt, updatedAt);
    }
}
