package com.ticketapp.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Product master row. Match key is {@code (normalised_name, unit)}
 * so "Bread" by weight is distinct from "Bread" by piece. Tests pin
 * the lower-trim rule, that the unit is a non-null
 * {@link QuantityUnit}, and that a null unit is rejected rather than
 * quietly creating a second match key for the same product.
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
        Product p = new Product(nextId(), "Olive Oil", null, QuantityUnit.KG, null);
        assertEquals("olive oil", p.normalisedName());
        assertEquals(QuantityUnit.KG, p.unit());
        assertNotNull(p.createdAt());
    }

    @Test
    void constructorRejectsNullUnit() {
        // A line that printed no unit is UNIT, not "no unit" — accepting
        // null here would put "Bread" and "Bread (unit)" into two
        // different match keys and split the catalogue in two.
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> new Product(nextId(), "Bread", "bread", null, now));
    }

    @Test
    void constructorRejectsNullName() {
        Instant now = Instant.now();
        assertThrows(NullPointerException.class, () -> new Product(nextId(), null, null, QuantityUnit.UNIT, now));
    }

    /** Sequential stand-in for the former UUID test ids. */
    private static final java.util.concurrent.atomic.AtomicLong IDS = new java.util.concurrent.atomic.AtomicLong(1L);

    private static long nextId() {
        return IDS.incrementAndGet();
    }
}
