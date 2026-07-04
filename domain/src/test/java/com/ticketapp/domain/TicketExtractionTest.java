package com.ticketapp.domain;

import com.ticketapp.domain.TicketExtraction.ProductLine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TicketExtraction} and its nested
 * {@link ProductLine} record.
 *
 * <p>Pins the constructor-level invariants the persistence layer
 * relies on (non-null fields, non-negative total amount, ISO 4217
 * currency, non-blank merchant/model, non-blank product name,
 * strictly-positive quantity) and exercises the legacy 10-arg
 * constructor that maps a missing {@code extractionPayload} to
 * {@code null} for pre-V7 fixtures.
 */
class TicketExtractionTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate DATE = LocalDate.of(2026, Month.JULY, 4);
    private static final Instant NOW = Instant.parse("2026-07-05T17:00:00Z");
    private static final List<ProductLine> PRODUCTS = List.of(
            new ProductLine("Bread", new BigDecimal("1"), "unit",
                    new BigDecimal("1.20"), new BigDecimal("1.20")));

    @Test
    void canonicalConstructorCarriesAllFields() {
        TicketExtraction e = new TicketExtraction(
                ID, "Mercadona", DATE, "food", PRODUCTS,
                new BigDecimal("12.50"), "EUR", "MiniMax-M3", NOW, "raw", "payload");

        assertEquals(ID, e.ticketId());
        assertEquals("Mercadona", e.merchant());
        assertEquals(DATE, e.purchaseDate());
        assertEquals("food", e.category());
        assertEquals(PRODUCTS, e.products());
        assertEquals(0, e.totalAmount().compareTo(new BigDecimal("12.50")));
        assertEquals("EUR", e.currency());
        assertEquals("MiniMax-M3", e.model());
        assertEquals(NOW, e.extractedAt());
        assertEquals("raw", e.rawResponse());
        assertEquals("payload", e.extractionPayload());
    }

    @Test
    void legacyConstructorMapsMissingExtractionPayloadToNull() {
        // Pre-V7 callers don't know about extractionPayload — the
        // 10-arg overload exists to keep those fixtures and tests
        // compiling. Verify the missing field is null, not a default
        // empty string that would corrupt downstream queries.
        TicketExtraction e = new TicketExtraction(
                ID, "Mercadona", DATE, null, PRODUCTS,
                new BigDecimal("12.50"), "EUR", "MiniMax-M3", NOW, "raw");

        assertNull(e.extractionPayload());
    }

    @Test
    void rejectsNullTicketId() {
        assertThrows(NullPointerException.class,
                () -> new TicketExtraction(
                        null, "X", DATE, null, PRODUCTS, BigDecimal.ZERO, "EUR", "M", NOW, "raw", "p"));
    }

    @Test
    void rejectsBlankMerchant() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new TicketExtraction(
                        ID, "", DATE, null, PRODUCTS, BigDecimal.ZERO, "EUR", "M", NOW, "raw", "p"));
        assertTrue(ex.getMessage().contains("merchant"));
    }

    @Test
    void rejectsNullPurchaseDate() {
        assertThrows(NullPointerException.class,
                () -> new TicketExtraction(
                        ID, "X", null, null, PRODUCTS, BigDecimal.ZERO, "EUR", "M", NOW, "raw", "p"));
    }

    @Test
    void rejectsNullProducts() {
        assertThrows(NullPointerException.class,
                () -> new TicketExtraction(
                        ID, "X", DATE, null, null, BigDecimal.ZERO, "EUR", "M", NOW, "raw", "p"));
    }

    @Test
    void rejectsNullTotalAmount() {
        assertThrows(NullPointerException.class,
                () -> new TicketExtraction(
                        ID, "X", DATE, null, PRODUCTS, null, "EUR", "M", NOW, "raw", "p"));
    }

    @Test
    void rejectsNegativeTotalAmount() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new TicketExtraction(
                        ID, "X", DATE, null, PRODUCTS, new BigDecimal("-0.01"), "EUR", "M", NOW, "raw", "p"));
        assertTrue(ex.getMessage().contains(">= 0"));
    }

    @Test
    void rejectsInvalidCurrencyLength() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new TicketExtraction(
                        ID, "X", DATE, null, PRODUCTS, BigDecimal.ZERO, "EURO", "M", NOW, "raw", "p"));
        assertTrue(ex.getMessage().contains("ISO 4217"));
    }

    @Test
    void rejectsBlankModel() {
        assertThrows(IllegalArgumentException.class,
                () -> new TicketExtraction(
                        ID, "X", DATE, null, PRODUCTS, BigDecimal.ZERO, "EUR", "", NOW, "raw", "p"));
    }

    @Test
    void rejectsNullExtractedAt() {
        assertThrows(NullPointerException.class,
                () -> new TicketExtraction(
                        ID, "X", DATE, null, PRODUCTS, BigDecimal.ZERO, "EUR", "M", null, "raw", "p"));
    }

    @Test
    void rejectsNullRawResponse() {
        assertThrows(NullPointerException.class,
                () -> new TicketExtraction(
                        ID, "X", DATE, null, PRODUCTS, BigDecimal.ZERO, "EUR", "M", NOW, null, "p"));
    }

    @Test
    void productLineRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProductLine("", BigDecimal.ONE, "unit", BigDecimal.ONE, BigDecimal.ONE));
    }

    @Test
    void productLineRejectsNonPositiveQuantity() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ProductLine("X", BigDecimal.ZERO, "unit", BigDecimal.ONE, BigDecimal.ONE));
        assertTrue(ex.getMessage().contains("quantity"));
    }

    @Test
    void productLineRejectsNegativeQuantity() {
        // Quantities must be strictly positive; discounts are modelled
        // as negative pricePerUnit / lineTotal on a quantity=1 line,
        // never as a negative count.
        assertThrows(IllegalArgumentException.class,
                () -> new ProductLine("X", new BigDecimal("-1"), "unit",
                        BigDecimal.ONE, BigDecimal.ONE));
    }

    @Test
    void productLineAcceptsDiscountLineWithNegativePrice() {
        // The ProductLine doc explicitly allows pricePerUnit /
        // lineTotal to go negative to model a discount. Quantity
        // stays 1.
        ProductLine discount = new ProductLine(
                "Remise 6€", BigDecimal.ONE, "unit",
                new BigDecimal("-6.00"), new BigDecimal("-6.00"));

        assertEquals(0, discount.pricePerUnit().compareTo(new BigDecimal("-6.00")));
        assertEquals(0, discount.lineTotal().compareTo(new BigDecimal("-6.00")));
    }
}