package com.ticketapp.domain;

import java.util.Locale;

/**
 * The unit a product line's {@code quantity} is counted in.
 *
 * <p><b>Provisional.</b> Two values is what the domain actually needs
 * today — a weighed good and a countable one. The set grows when the
 * catalogue does (litres, packs), and every addition is additive: the
 * enum never reinterprets an existing value.
 *
 * <h2>Why an enum rather than the free-text label it replaces</h2>
 * The column is {@code VARCHAR(64)} filled by a language model, so it
 * arrives as {@code "kg"}, {@code "L"}, {@code "pack"}, {@code null} or
 * {@code "UNIT"}. That label was half identity ({@link Product}'s match
 * key is {@code normalisedName + unit}) and half decoration. Typing it
 * splits those apart: the match key becomes an enum, and everything
 * that is merely a display label is left to the UI.
 *
 * <h2>Lossy on purpose</h2>
 * {@link #fromLabel(String)} maps every label that is not a weight to
 * {@link #UNIT} rather than rejecting it. Rejecting would fail the whole
 * extraction over a litre of milk, and existing catalogue rows already
 * carry {@code "L"} and {@code null}. So the mapping is lossy, and the
 * cost is named here rather than discovered in a chart: a litre line
 * and a piece read as {@code UNIT} until the enum grows to carry litres.
 *
 * @see Product
 * @see TicketExtraction.ProductLine
 */
public enum QuantityUnit {

    /** Weighed goods — "2 kg of flour". */
    KG("kg"),

    /** Countable goods — "2 bottles", or a line that printed no unit. */
    UNIT("unit");

    private final String wireName;

    QuantityUnit(String wireName) {
        this.wireName = wireName;
    }

    /** Lower-case name as it travels on the wire and in the catalogue column. */
    public String wireName() {
        return wireName;
    }

    /**
     * How this unit is written to {@code products.unit}: always the
     * {@link #wireName()}, never SQL NULL.
     *
     * <p>The column is {@code NOT NULL} since V23 and a native type
     * since V26. That is the point of the enum: "no unit" is
     * {@link #UNIT}, not an absent value, so it is stored like any
     * other member rather than as a hole in the column.
     *
     * <p>Reads stay tolerant — {@link #fromStorage(String)} accepts the
     * NULL and free-text values older rows and older payloads still
     * carry — because a repository must not fail a read on data it did
     * not write.
     */
    public String storageValue() {
        return wireName;
    }

    /**
     * Read a label from any source — the model, the wire, a legacy
     * column — into a unit.
     *
     * <p>Never throws and never returns null: an absent, blank or
     * unrecognised label is {@link #UNIT}. That is the whole point of
     * folding rather than rejecting (see the class Javadoc), and it is
     * what lets a catalogue row written before this enum existed still
     * load.
     *
     * @param label free-text unit label; null tolerated
     */
    public static QuantityUnit fromLabel(String label) {
        if (label == null || label.isBlank()) {
            return UNIT;
        }
        return switch (label.trim().toLowerCase(Locale.ROOT)) {
            case "kg", "kgs", "kilo", "kilos", "kilogram", "kilograms" -> KG;
            default -> UNIT;
        };
    }

    /**
     * Read the {@code products.unit} column, whose rows predate the
     * enum and may hold {@code null}, {@code "kg"}, {@code "L"} or
     * anything else a model once emitted.
     */
    public static QuantityUnit fromStorage(String column) {
        return fromLabel(column);
    }
}