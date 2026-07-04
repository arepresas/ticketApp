package com.ticketapp.domain.ai;

import com.ticketapp.domain.TicketExtraction.ProductLine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReceiptExtractionResult} (ADR 0007).
 *
 * <p>Pins the field-level validation that the orchestrator relies
 * on. The record's compact constructor enforces: non-blank
 * merchant, non-null purchaseDate / products / totalAmount, and a
 * 3-letter ISO 4217 currency. {@code category} stays nullable by
 * design.
 */
class ReceiptExtractionResultTest {

    private static final LocalDate DATE = LocalDate.of(2026, Month.JANUARY, 1);
    private static final List<ProductLine> EMPTY_PRODUCTS = List.of();

    @Test
    void carriesAllFields() {
        ReceiptExtractionResult r = new ReceiptExtractionResult(
                "Mercadona", DATE, "food", EMPTY_PRODUCTS,
                new BigDecimal("12.50"), "EUR");

        assertEquals("Mercadona", r.merchant());
        assertEquals(DATE, r.purchaseDate());
        assertEquals("food", r.category());
        assertEquals(EMPTY_PRODUCTS, r.products());
        assertEquals(0, r.totalAmount().compareTo(new BigDecimal("12.50")));
        assertEquals("EUR", r.currency());
    }

    @Test
    void categoryMayBeNull() {
        // Provider did not emit a category — the orchestrator stores
        // the row as-is. This branch is part of the documented
        // contract, not an oversight.
        ReceiptExtractionResult r = new ReceiptExtractionResult(
                "X", DATE, null, EMPTY_PRODUCTS, BigDecimal.ZERO, "EUR");

        assertNull(r.category());
    }

    @Test
    void rejectsBlankMerchant() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ReceiptExtractionResult(
                        "", DATE, null, EMPTY_PRODUCTS, BigDecimal.ZERO, "EUR"));
        assertTrue(ex.getMessage().contains("merchant"));
    }

    @Test
    void rejectsNullPurchaseDate() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new ReceiptExtractionResult(
                        "X", null, null, EMPTY_PRODUCTS, BigDecimal.ZERO, "EUR"));
        assertEquals("purchaseDate", ex.getMessage());
    }

    @Test
    void rejectsNullProducts() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new ReceiptExtractionResult(
                        "X", DATE, null, null, BigDecimal.ZERO, "EUR"));
        assertEquals("products", ex.getMessage());
    }

    @Test
    void rejectsNullTotalAmount() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new ReceiptExtractionResult(
                        "X", DATE, null, EMPTY_PRODUCTS, null, "EUR"));
        assertEquals("totalAmount", ex.getMessage());
    }

    @Test
    void rejectsCurrencyShorterThanThree() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ReceiptExtractionResult(
                        "X", DATE, null, EMPTY_PRODUCTS, BigDecimal.ZERO, "EU"));
        assertTrue(ex.getMessage().contains("ISO 4217"));
    }

    @Test
    void rejectsCurrencyLongerThanThree() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReceiptExtractionResult(
                        "X", DATE, null, EMPTY_PRODUCTS, BigDecimal.ZERO, "EURO"));
    }

    @Test
    void rejectsNullCurrency() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReceiptExtractionResult(
                        "X", DATE, null, EMPTY_PRODUCTS, BigDecimal.ZERO, null));
    }
}