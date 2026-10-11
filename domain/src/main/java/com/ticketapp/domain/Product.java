package com.ticketapp.domain;

import java.time.Instant;
import java.util.Locale;


/**
 * Master catalogue entry. The canonical "thing" a receipt line
 * refers to ("Bread", "Milk 1L"). Multiple tickets share the same
 * {@link Product} via different {@link Price} rows — one Price per
 * ticket — so per-ticket spend is preserved even as catalog prices
 * shift over time.
 *
 * <p>Identity is the {@code id} assigned at creation. {@code
 * normalisedName} + {@code unit} together form the match key so
 * "Bread" sold by weight and "Bread" sold by piece stay distinct
 * products. The unit is a {@link QuantityUnit} rather than the free
 * label it used to be, so "no unit" ({@link QuantityUnit#UNIT}) and a
 * genuine weight ({@link QuantityUnit#KG}) are the only two states the
 * match key can take.
 *
 * <p>Created oppotunistically by {@link com.ticketapp.bff.extraction.TicketExtractionNormaliser}
 * the first time the normaliser sees the line, on ticket DONE.
 */
public record Product(
        long id,
        String name,
        String normalisedName,
        QuantityUnit unit,
        Instant createdAt
) {
    public Product {
        if (name == null) throw new NullPointerException("name");
        if (normalisedName == null) {
            normalisedName = normalisedNameOf(name);
        }
        if (unit == null) {
            // Same rule as ProductLine: a product with no unit is a
            // UNIT product. A null here would put "Bread" and "Bread
            // (unit)" into two different match keys.
            throw new IllegalArgumentException("unit must not be null");
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public static String normalisedNameOf(String name) {
        if (name == null) throw new NullPointerException("name");
        return name.trim().toLowerCase(Locale.ROOT);
    }
    /**
     * Attach the id assigned by the database on insert. Ids come from
     * Postgres identity columns, so an entity leaves the domain with
     * {@code id == 0} and gains its real id on the way back from
     * {@code save()}. Only the adapter calls this.
     */
    public Product withId(long newId) {
        return new Product(newId, name, normalisedName, unit, createdAt);
    }
}
