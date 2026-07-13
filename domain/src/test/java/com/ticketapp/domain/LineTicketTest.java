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
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("1"), new BigDecimal("-3.00"),
                Instant.now(), Instant.now());
        assertEquals(new BigDecimal("-3.00"), lt.lineTotal());
    }

    @Test
    void constructorRejectsZeroQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> new LineTicket(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(),
                        BigDecimal.ZERO, BigDecimal.ONE,
                        Instant.now(), Instant.now()));
    }

    @Test
    void constructorRejectsNegativeQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> new LineTicket(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(),
                        new BigDecimal("-1"), BigDecimal.ONE,
                        Instant.now(), Instant.now()));
    }

    @Test
    void constructorRejectsNullReferencesAndNumericFields() {
        // All four FK targets + quantity + lineTotal are required.
        // Letting any of them be null would let the JDBC layer pass
        // it through and crash on the FK constraint downstream;
        // catching it at the domain boundary keeps the failure
        // local.
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        UUID t = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        UUID p = UUID.randomUUID();
        UUID pr = UUID.randomUUID();
        BigDecimal one = BigDecimal.ONE;

        assertThrows(NullPointerException.class,
                () -> new LineTicket(null, t, s, p, pr, one, one, now, now));
        assertThrows(NullPointerException.class,
                () -> new LineTicket(id, null, s, p, pr, one, one, now, now));
        assertThrows(NullPointerException.class,
                () -> new LineTicket(id, t, null, p, pr, one, one, now, now));
        assertThrows(NullPointerException.class,
                () -> new LineTicket(id, t, s, null, pr, one, one, now, now));
        assertThrows(NullPointerException.class,
                () -> new LineTicket(id, t, s, p, null, one, one, now, now));
        assertThrows(NullPointerException.class,
                () -> new LineTicket(id, t, s, p, pr, null, one, now, now));
        assertThrows(NullPointerException.class,
                () -> new LineTicket(id, t, s, p, pr, one, null, now, now));
    }
}
