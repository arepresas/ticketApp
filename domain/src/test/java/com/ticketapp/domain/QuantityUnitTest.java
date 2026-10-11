package com.ticketapp.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link QuantityUnit} — the fold that keeps a language model's free
 * text from reaching the catalogue, and the storage convention that
 * keeps pre-enum rows matching.
 *
 * <p>The lossy cases are pinned deliberately: "L" and "pack" becoming
 * {@link QuantityUnit#UNIT} is the documented cost of a two-value enum,
 * not an accident, and a future value added to the enum must come with
 * a test that says what it changes.
 */
@DisplayName("QuantityUnit")
class QuantityUnitTest {

    @ParameterizedTest
    @DisplayName("recognises the weight labels a receipt prints")
    @ValueSource(strings = {"kg", "KG", "Kg", " kgs ", "kilo", "kilos", "kilogram", "kilograms"})
    void recognisesWeightLabels(String label) {
        assertEquals(QuantityUnit.KG, QuantityUnit.fromLabel(label));
    }

    @ParameterizedTest
    @DisplayName("folds every other label to UNIT rather than rejecting it")
    @ValueSource(strings = {"unit", "UNIT", "pcs", "pack", "L", "litre", "bottle", "?"})
    void foldsOtherLabelsToUnit(String label) {
        // Rejecting would fail a whole extraction over a litre of milk,
        // so the fold is the contract — the cost is a litre reading as
        // a piece until the enum grows.
        assertEquals(QuantityUnit.UNIT, QuantityUnit.fromLabel(label));
    }

    @ParameterizedTest
    @DisplayName("treats an absent or blank label as UNIT")
    @NullSource
    @ValueSource(strings = {"", "   "})
    void treatsAbsentLabelAsUnit(String label) {
        assertEquals(QuantityUnit.UNIT, QuantityUnit.fromLabel(label));
    }

    @Test
    @DisplayName("reads a legacy column value without failing")
    void readsLegacyColumnValues() {
        // Rows written before the enum existed hold NULL, "kg" or any
        // label a model once emitted; none of them may fail a read.
        assertEquals(QuantityUnit.UNIT, QuantityUnit.fromStorage(null));
        assertEquals(QuantityUnit.KG, QuantityUnit.fromStorage("kg"));
        assertEquals(QuantityUnit.UNIT, QuantityUnit.fromStorage("L"));
    }

    @Test
    @DisplayName("stores both units under their own names")
    void storesBothUnitsByName() {
        // products.unit is NOT NULL (V23): "no unit" is the literal
        // 'unit', not an absent value. A NULL here would make the
        // repository write a hole the column no longer accepts.
        assertEquals("kg", QuantityUnit.KG.storageValue());
        assertEquals("unit", QuantityUnit.UNIT.storageValue());
    }

    @ParameterizedTest
    @DisplayName("round-trips through the storage value")
    @EnumSource(QuantityUnit.class)
    void roundTripsThroughStorage(QuantityUnit unit) {
        // Whichever way the column is spelled, reading it back must
        // yield the unit that was written.
        assertEquals(unit, QuantityUnit.fromStorage(unit.storageValue()));
    }

    @Test
    @DisplayName("exposes a lower-case wire name")
    void exposesLowerCaseWireName() {
        // The SPA types these as 'kg' | 'unit'; the casing is part of
        // the wire contract, not a display choice.
        assertEquals("kg", QuantityUnit.KG.wireName());
        assertEquals("unit", QuantityUnit.UNIT.wireName());
    }
}