package com.ticketapp.domain;

import java.util.Locale;
import java.util.Map;

/**
 * The closed set of spending categories a receipt can carry, from the
 * extraction that writes it to the dashboard that reports it.
 *
 * <p><b>One set, end to end.</b> Before this enum the column was free
 * text ({@code VARCHAR(64)}) and the dashboard reported a different,
 * hand-written whitelist through a SQL {@code CASE} — so the prompt
 * asked the model for {@code fuel}, storage kept {@code fuel}, and the
 * donut showed {@code other}. One enum typed at every layer is the fix:
 * what the model is asked for, what the column holds, what the wire
 * carries and what the chart slices are the same five values.
 *
 * <h2>The set</h2>
 * <ul>
 *   <li>{@link #FOOD} — supermarket and restaurant alike. Splitting
 *       them would give the model a distinction it cannot reliably
 *       make from a receipt header, and both answer "eating".</li>
 *   <li>{@link #TRANSPORT} — fuel, taxi, train, parking.</li>
 *   <li>{@link #LODGING} — hotel, Airbnb.</li>
 *   <li>{@link #PHARMACY} — pharmacy and health/beauty.</li>
 *   <li>{@link #OTHER} — the fold target for everything else,
 *       including {@code null} and blank.</li>
 * </ul>
 *
 * <h2>Lossy on purpose</h2>
 * {@link #fromExtractionValue(String)} folds an unrecognised label into
 * {@link #OTHER} rather than rejecting it, exactly as
 * {@link QuantityUnit#fromLabel(String)} does. A receipt we could not
 * classify is still money the user spent: dropping it would make the
 * category total disagree with the spend total beside it.
 */
public enum SpendCategory {

    FOOD("food"),
    TRANSPORT("transport"),
    LODGING("lodging"),
    PHARMACY("pharmacy"),
    OTHER("other");

    /**
     * Labels the model produced before this enum existed, mapped onto
     * the set above. Kept as data rather than as a chain of {@code if}s
     * so the migration's fold and the runtime fold cannot drift: the
     * backfill that rewrites stored rows applies the same table.
     *
     * <p>Every value here is either a member's {@code wireName} or a
     * label the prompt used to request ({@code food, pharmacy,
     * restaurant, fuel, other}).
     */
    private static final Map<String, SpendCategory> LEGACY_LABELS = Map.ofEntries(
            Map.entry("food", FOOD),
            Map.entry("groceries", FOOD),
            Map.entry("grocery", FOOD),
            Map.entry("supermarket", FOOD),
            Map.entry("restaurant", FOOD),
            Map.entry("restaurants", FOOD),
            Map.entry("transport", TRANSPORT),
            Map.entry("fuel", TRANSPORT),
            Map.entry("gasoline", TRANSPORT),
            Map.entry("petrol", TRANSPORT),
            Map.entry("taxi", TRANSPORT),
            Map.entry("parking", TRANSPORT),
            Map.entry("lodging", LODGING),
            Map.entry("hotel", LODGING),
            Map.entry("pharmacy", PHARMACY),
            Map.entry("health", PHARMACY),
            Map.entry("beauty", PHARMACY),
            Map.entry("other", OTHER));

    private final String wireName;

    SpendCategory(String wireName) {
        this.wireName = wireName;
    }

    /**
     * Lowercase slug sent over the wire, stored in the column and used
     * by the SPA. Deliberately not {@link #name()}: the enum constant
     * is {@code TRANSPORT} but the SPA keys its charts on
     * {@code 'transport'}.
     */
    public String wireName() {
        return wireName;
    }

    /**
     * How the column spells this category — the wire name. There is no
     * NULL spelling: a receipt with no readable category is
     * {@link #OTHER}, stored under its own name like every other
     * member.
     */
    public String storageValue() {
        return wireName;
    }

    /**
     * Map a raw label onto the set: a {@code null}, blank or
     * unrecognised value is {@link #OTHER}. Matching is case- and
     * whitespace-insensitive, and covers both the current members and
     * every legacy label in {@link #LEGACY_LABELS}.
     *
     * <p>Never throws — the fold is what lets a row written before this
     * enum existed still load.
     */
    public static SpendCategory fromExtractionValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return OTHER;
        }
        String normalised = raw.trim().toLowerCase(Locale.ROOT);
        for (SpendCategory candidate : values()) {
            if (candidate.wireName.equals(normalised)) {
                return candidate;
            }
        }
        return LEGACY_LABELS.getOrDefault(normalised, OTHER);
    }

    /** Read the {@code ticket_extractions.category} column. */
    public static SpendCategory fromStorage(String column) {
        return fromExtractionValue(column);
    }
}