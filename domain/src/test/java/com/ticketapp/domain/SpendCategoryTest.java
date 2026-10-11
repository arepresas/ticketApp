package com.ticketapp.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("SpendCategory")
class SpendCategoryTest {

    @Test
    @DisplayName("maps the five members")
    void mapsKnownSlugs() {
        for (SpendCategory category : SpendCategory.values()) {
            assertEquals(category, SpendCategory.fromExtractionValue(category.wireName()));
        }
    }

    @Test
    @DisplayName("is case- and whitespace-insensitive")
    void normalisesInput() {
        // The column was filled by a language model before V25
        // constrained it, so casing and stray whitespace are expected,
        // not exceptional.
        assertEquals(SpendCategory.FOOD, SpendCategory.fromExtractionValue("  Food "));
        assertEquals(SpendCategory.TRANSPORT, SpendCategory.fromExtractionValue("TRANSPORT"));
    }

    /**
     * The labels the prompt used to request, mapped onto the set. This
     * is the fold V24 applies to stored rows, so a label that is not
     * listed here would be rewritten to {@code other} by the migration
     * and read as {@code other} at runtime — the two would agree, but
     * the data would be silently coarsened.
     */
    @ParameterizedTest
    @DisplayName("folds the labels the old prompt asked the model for")
    @CsvSource({
            "restaurant, FOOD",
            "fuel, TRANSPORT",
            "gasoline, TRANSPORT",
            "taxi, TRANSPORT",
            "hotel, LODGING",
            "health, PHARMACY",
            "beauty, PHARMACY"
    })
    void foldsLegacyPromptLabels(String legacy, SpendCategory expected) {
        assertEquals(expected, SpendCategory.fromExtractionValue(legacy));
    }

    @ParameterizedTest
    @DisplayName("folds an unrecognised label into OTHER so spend is never dropped")
    @ValueSource(strings = {"gas station", "hardware", "books", "???", "FOOD_"})
    void foldsUnknownIntoOther(String label) {
        assertEquals(SpendCategory.OTHER, SpendCategory.fromExtractionValue(label));
    }

    @ParameterizedTest
    @DisplayName("treats null and blank as OTHER")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void treatsNullAndBlankAsOther(String label) {
        assertEquals(SpendCategory.OTHER, SpendCategory.fromExtractionValue(label));
    }

    @Test
    @DisplayName("exposes the lowercase slug stored in the column and keyed by the SPA")
    void exposesLowercaseWireName() {
        assertEquals("food", SpendCategory.FOOD.wireName());
        assertEquals("transport", SpendCategory.TRANSPORT.wireName());
        assertEquals("lodging", SpendCategory.LODGING.wireName());
        assertEquals("pharmacy", SpendCategory.PHARMACY.wireName());
        assertEquals("other", SpendCategory.OTHER.wireName());
    }

    @Test
    @DisplayName("stores each member under its own name, never NULL")
    void storesEveryMemberByName() {
        // V25 constrains the column to these values, so a member with
        // no storage spelling would be unwritable.
        for (SpendCategory category : SpendCategory.values()) {
            assertEquals(category.wireName(), category.storageValue());
            assertEquals(category, SpendCategory.fromStorage(category.storageValue()));
        }
    }
}