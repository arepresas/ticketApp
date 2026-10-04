package com.ticketapp.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("SpendCategory")
class SpendCategoryTest {

    @Test
    @DisplayName("maps the four known slugs")
    void mapsKnownSlugs() {
        assertEquals(SpendCategory.TRANSPORT, SpendCategory.fromExtractionValue("transport"));
        assertEquals(SpendCategory.FOOD, SpendCategory.fromExtractionValue("food"));
        assertEquals(SpendCategory.LODGING, SpendCategory.fromExtractionValue("lodging"));
        assertEquals(SpendCategory.OTHER, SpendCategory.fromExtractionValue("other"));
    }

    @Test
    @DisplayName("is case- and whitespace-insensitive")
    void normalisesInput() {
        // The column is free text emitted by a model, so casing and
        // stray whitespace are expected, not exceptional.
        assertEquals(SpendCategory.FOOD, SpendCategory.fromExtractionValue("  Food "));
        assertEquals(SpendCategory.TRANSPORT, SpendCategory.fromExtractionValue("TRANSPORT"));
    }

    @Test
    @DisplayName("folds unknown labels into OTHER so spend is never dropped")
    void foldsUnknownIntoOther() {
        assertEquals(SpendCategory.OTHER, SpendCategory.fromExtractionValue("groceries"));
        assertEquals(SpendCategory.OTHER, SpendCategory.fromExtractionValue("gas station"));
    }

    @Test
    @DisplayName("treats null and blank as OTHER")
    void treatsNullAndBlankAsOther() {
        assertEquals(SpendCategory.OTHER, SpendCategory.fromExtractionValue(null));
        assertEquals(SpendCategory.OTHER, SpendCategory.fromExtractionValue(""));
        assertEquals(SpendCategory.OTHER, SpendCategory.fromExtractionValue("   "));
    }

    @Test
    @DisplayName("exposes the lowercase slug the SPA keys its charts on")
    void exposesLowercaseWireName() {
        assertEquals("transport", SpendCategory.TRANSPORT.wireName());
        assertEquals("food", SpendCategory.FOOD.wireName());
        assertEquals("lodging", SpendCategory.LODGING.wireName());
        assertEquals("other", SpendCategory.OTHER.wireName());
    }

    @Test
    @DisplayName("round-trips through fromExtractionValue")
    void roundTrips() {
        for (SpendCategory category : SpendCategory.values()) {
            assertEquals(category, SpendCategory.fromExtractionValue(category.wireName()));
        }
    }
}