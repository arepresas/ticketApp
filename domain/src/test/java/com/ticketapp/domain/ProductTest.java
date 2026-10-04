package com.ticketapp.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Product master row. Match key is {@code (normalised_name, unit)}
 * so "Bread" + null unit is distinct from "Bread" + "kg". Tests
 * pin the lower-trim rule + that {@code unit} can be null without
 * the record collapsing on its own.
 */
class ProductTest {

    @Test
    void normalisedNameLowercasesAndTrims() {
        assertEquals("bread", Product.normalisedNameOf("Bread"));
        assertEquals("bread", Product.normalisedNameOf(" bread "));
        assertEquals("bread", Product.normalisedNameOf("BREAD"));
        assertEquals("bread", Product.normalisedNameOf("BrEaD"));
    }

    @Test
    void constructorAutoFillsNormalisedNameAndCreatedAt() {
        Product p = new Product(nextId(), "Olive Oil", null, "L", null);
        assertEquals("olive oil", p.normalisedName());
        assertEquals("L", p.unit());
        assertNotNull(p.createdAt());
    }

    @Test
    void constructorAcceptsNullUnit() {
        // Receipts frequently omit the unit for items like "Bread"
        // — null unit is a valid value, not a bug.
        Product p = new Product(nextId(), "Bread", "bread", null, Instant.now());
        assertEquals(null, p.unit());
    }

    @Test
    void constructorRejectsNullName() {
        Instant now = Instant.now();
        assertThrows(NullPointerException.class,
                () -> new Product(nextId(), null, null, "kg", now));
    }

    /** Sequential stand-in for the former UUID test ids. */
    private static final java.util.concurrent.atomic.AtomicLong IDS =
            new java.util.concurrent.atomic.AtomicLong(1L);

    private static long nextId() {
        return IDS.incrementAndGet();
    }
}
