package com.ticketapp.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("TicketStats")
class TicketStatsTest {

    @Test
    @DisplayName("rejects negative counts")
    void rejectsNegativeCounts() {
        assertThrows(IllegalArgumentException.class, () -> new TicketStats(
                -1, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, "EUR"));
    }

    @Test
    @DisplayName("rejects openTickets greater than totalTickets")
    void rejectsOpenAboveTotal() {
        // Would mean the adapter's status filter disagrees with itself;
        // catching it here beats rendering "12 open of 8 tickets".
        assertThrows(IllegalArgumentException.class, () -> new TicketStats(
                8, 12, 0, BigDecimal.ZERO, BigDecimal.ZERO, "EUR"));
    }

    @Test
    @DisplayName("rejects a null amount")
    void rejectsNullAmount() {
        assertThrows(IllegalArgumentException.class, () -> new TicketStats(
                3, 1, 2, null, BigDecimal.ONE, "EUR"));
    }

    @Test
    @DisplayName("rejects a blank currency")
    void rejectsBlankCurrency() {
        // The dashboard renders the symbol from this value, so it must
        // never arrive empty.
        assertThrows(IllegalArgumentException.class, () -> new TicketStats(
                3, 1, 2, BigDecimal.TEN, BigDecimal.ONE, "  "));
    }

    @Test
    @DisplayName("rejects more extracted tickets than total tickets")
    void rejectsExtractedAboveTotal() {
        // Both counters describe the same population with different
        // filters, so the narrower one cannot be larger.
        assertThrows(IllegalArgumentException.class, () -> new TicketStats(
                3, 1, 4, new BigDecimal("10.00"), new BigDecimal("10.00"), "EUR"));
    }

    @Test
    @DisplayName("accepts an extracted count equal to the total")
    void acceptsExtractedEqualToTotal() {
        assertEquals(3, new TicketStats(3, 0, 3,
                new BigDecimal("10.00"), new BigDecimal("10.00"), "EUR").extractedTicketsInCurrency());
    }

    @Test
    @DisplayName("requires zero totals when nothing was extracted")
    void requiresZeroTotalsWithoutExtractions() {
        // Money summed over nothing is zero, and the mean of no samples
        // has nothing to report.
        assertThrows(IllegalArgumentException.class, () -> new TicketStats(
                3, 1, 0, new BigDecimal("10.00"), BigDecimal.ZERO, "EUR"));
        assertThrows(IllegalArgumentException.class, () -> new TicketStats(
                3, 1, 0, BigDecimal.ZERO, new BigDecimal("5.00"), "EUR"));
    }

    @Test
    @DisplayName("accepts zero totals when nothing was extracted")
    void acceptsZeroTotalsWithoutExtractions() {
        TicketStats stats = new TicketStats(3, 1, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, "EUR");
        assertEquals(0, stats.extractedTicketsInCurrency());
        assertEquals(3, stats.totalTickets());
    }

    @Test
    @DisplayName("trims the currency so the caller cannot smuggle whitespace into the wire")
    void trimsCurrency() {
        assertEquals("EUR",
                new TicketStats(3, 1, 2, BigDecimal.TEN, BigDecimal.ONE, " EUR ").currency());
    }
}