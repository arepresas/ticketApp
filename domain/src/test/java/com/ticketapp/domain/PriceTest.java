package com.ticketapp.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Per-ticket price snapshot. Domain-level invariants:
 *   * amount is a non-negative per-unit price; discounts are
 *     modelled as a negative {@code line_total} on the parent
 *     {@link LineTicket}, not a negative amount.
 *   * ids / FK targets are required (the persistence layer relies
 *     on the references to keep the catalogue cross-references
 *     intact).
 */
class PriceTest {

    @Test
    void constructorAcceptsNonNegativeAmount() {
        Price p = new Price(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("1.2345"),
                Instant.now(), Instant.now());
        assertEquals(new BigDecimal("1.2345"), p.amount());
    }

    @Test
    void constructorRejectsNegativeAmount() {
        assertThrows(IllegalArgumentException.class,
                () -> new Price(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        new BigDecimal("-0.01"),
                        Instant.now(), Instant.now()));
    }

    @Test
    void constructorAcceptsZeroAmount() {
        // Free samples (€0.00/ud) are valid: a clerk hands out one
        // and the receipt shows "0.00 €/ud" for that line.
        Price p = new Price(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BigDecimal.ZERO,
                Instant.now(), Instant.now());
        assertEquals(BigDecimal.ZERO, p.amount());
    }

    @Test
    void constructorRejectsNullIdProductTicketAmount() {
        Instant now = Instant.now();
        assertThrows(NullPointerException.class,
                () -> new Price(null, UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE, now, now));
        assertThrows(NullPointerException.class,
                () -> new Price(UUID.randomUUID(), null, UUID.randomUUID(), BigDecimal.ONE, now, now));
        assertThrows(NullPointerException.class,
                () -> new Price(UUID.randomUUID(), UUID.randomUUID(), null, BigDecimal.ONE, now, now));
        assertThrows(NullPointerException.class,
                () -> new Price(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, now, now));
    }

    @Test
    void constructorAutoFillsTimestamps() {
        Price p = new Price(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BigDecimal.ONE, null, null);
        assertNotNull(p.createdAt());
        assertNotNull(p.updatedAt());
    }
}
