package com.ticketapp.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Per-ticket line. Crucially, {@code lineTotal} may be negative
 * (discount / credit row) even when {@code quantity} and the
 * referenced {@link Price#amount()} are both positive — that is the
 * canonical way to record "Remise 3€" against a ticket total. The
 * domain doesn't enforce {@code lineTotal = quantity × amount}
 * because the AI is the source of truth for what the receipt
 * actually shows.
 */
class LineTicketTest {

    @Test
    void constructorAcceptsNegativeLineTotal() {
        // Discount line: €3 store credit on a ticket whose cart
        // contained the product at full price.
        LineTicket lt = new LineTicket(
                nextId(), nextId(), nextId(), nextId(),
                new BigDecimal("1"), new BigDecimal("-3.00"),
                Instant.now(), Instant.now());
        assertEquals(new BigDecimal("-3.00"), lt.lineTotal());
    }

    @Test
    void constructorRejectsZeroQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> new LineTicket(
                        nextId(), nextId(), nextId(), nextId(),
                        BigDecimal.ZERO, BigDecimal.ONE,
                        Instant.now(), Instant.now()));
    }

    @Test
    void constructorRejectsNegativeQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> new LineTicket(
                        nextId(), nextId(), nextId(), nextId(),
                        new BigDecimal("-1"), BigDecimal.ONE,
                        Instant.now(), Instant.now()));
    }

    @Test
    void constructorRejectsNullQuantityAndLineTotal() {
        // quantity + lineTotal are required. Letting either be null
        // would let the JDBC layer pass it through and crash on the
        // NOT NULL constraint downstream; catching it at the domain
        // boundary keeps the failure local. The FK targets are
        // primitives now, so "null reference" cannot be expressed.
        Instant now = Instant.now();
        long t = nextId();
        long p = nextId();
        long pr = nextId();
        BigDecimal one = BigDecimal.ONE;

        assertThrows(NullPointerException.class,
                () -> new LineTicket(nextId(), t, p, pr, null, one, now, now));
        assertThrows(NullPointerException.class,
                () -> new LineTicket(nextId(), t, p, pr, one, null, now, now));
    }

    /** Sequential stand-in for the former UUID test ids. */
    private static final java.util.concurrent.atomic.AtomicLong IDS =
            new java.util.concurrent.atomic.AtomicLong(1L);

    private static long nextId() {
        return IDS.incrementAndGet();
    }
}
