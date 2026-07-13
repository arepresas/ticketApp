package com.ticketapp.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Shop master row. The match key (normalised name) has to dedupe
 * case- and whitespace-only variants so the same merchant across
 * tickets collapses to a single row, regardless of how the AI
 * capitalised the name from receipt OCR.
 */
class ShopTest {

    @Test
    void normalisedNameLowercasesAndTrims() {
        assertEquals("mercadona", Shop.normalisedNameOf("Mercadona"));
        assertEquals("mercadona", Shop.normalisedNameOf(" mercadona "));
        assertEquals("mercadona", Shop.normalisedNameOf("MERCADONA"));
        assertEquals("mercadona", Shop.normalisedNameOf("MerCaDoNa"));
    }

    @Test
    void normalisedNamePreservesInnerWhitespace() {
        // The catalogue uses (trim, lowercase) as the match key —
        // not a stemmer or whitespace-squasher. Inner spaces are
        // preserved so the original name (display) and the lookup
        // key stay close enough that an operator can spot the
        // match by eye.
        assertEquals("el corte inglés",
                Shop.normalisedNameOf("  El Corte Inglés  "));
    }

    @Test
    void constructorAutoFillsNormalisedNameAndCreatedAt() {
        Shop s = new Shop(UUID.randomUUID(), "Carrefour", null, null);
        assertEquals("carrefour", s.normalisedName());
        assertNotNull(s.createdAt());
    }

    @Test
    void constructorRejectsNullIdAndName() {
        Instant now = Instant.now();
        assertThrows(NullPointerException.class,
                () -> new Shop(null, "x", "x", now));
        assertThrows(NullPointerException.class,
                () -> new Shop(UUID.randomUUID(), null, null, now));
    }

    @Test
    void distinctShopsWithDifferentIdsAreNeverEqual() {
        // Equality is identity-based for shops: rows are unique by
        // {@code id}, not by name. The match key lives in the
        // repository's UNIQUE index, not in the record's equals.
        Instant now = Instant.now();
        Shop a = new Shop(UUID.randomUUID(), "Lidl", "lidl", now);
        Shop b = new Shop(UUID.randomUUID(), "Lidl", "lidl", now);
        assertNotEquals(a, b);
    }

    @Test
    void contactFieldsDefaultToNull() {
        // The compact constructor leaves every contact field null
        // when not supplied — that's the "no info yet" signal that
        // the dashboard renders as "—" rather than " ".
        Shop s = new Shop(UUID.randomUUID(), "Dia", "dia", Instant.now());
        assertNull(s.addressLine());
        assertNull(s.postalCode());
        assertNull(s.city());
        assertNull(s.country());
        assertNull(s.phone());
        assertNull(s.taxId());
        assertNull(s.website());
    }

    @Test
    void withContactAppliesNonNullFieldsAndKeepsOthers() {
        Instant now = Instant.now();
        Shop original = new Shop(
                UUID.randomUUID(), "Mercadona", "mercadona",
                "Calle Mayor 1", "28013", "Madrid", "ES",
                "+34 911 22 33 44", "A12345678", "https://mercadona.es",
                now);

        // Single-field patch: only the phone changes, everything
        // else carries over verbatim.
        Shop patched = original.withContact(null, null, null, null,
                "+34 900 00 00 00", null, null);

        assertEquals("Calle Mayor 1", patched.addressLine());
        assertEquals("28013", patched.postalCode());
        assertEquals("Madrid", patched.city());
        assertEquals("ES", patched.country());
        assertEquals("+34 900 00 00 00", patched.phone());
        assertEquals("A12345678", patched.taxId());
        assertEquals("https://mercadona.es", patched.website());
        // Identity, name, normalised name, and createdAt stay put —
        // PATCH is a contact-info patch only.
        assertEquals(original.id(), patched.id());
        assertEquals(original.name(), patched.name());
        assertEquals(original.normalisedName(), patched.normalisedName());
        assertEquals(original.createdAt(), patched.createdAt());
    }

    @Test
    void withContactReplacesAllFieldsWhenAllProvided() {
        Instant now = Instant.now();
        Shop original = new Shop(
                UUID.randomUUID(), "Dia", "dia",
                "Old Street 1", "08001", "Barcelona", "ES",
                "+34 930 00 00 00", "B12345678", "https://dia.es",
                now);

        Shop patched = original.withContact(
                "New Avenue 2", "08002", "Barcelona", "ES",
                "+34 931 00 00 00", "B87654321", "https://dia.com");

        assertEquals("New Avenue 2", patched.addressLine());
        assertEquals("08002", patched.postalCode());
        assertEquals("Barcelona", patched.city());
        assertEquals("ES", patched.country());
        assertEquals("+34 931 00 00 00", patched.phone());
        assertEquals("B87654321", patched.taxId());
        assertEquals("https://dia.com", patched.website());
    }
}