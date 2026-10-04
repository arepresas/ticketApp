package com.ticketapp.domain;

import java.util.Locale;

/**
 * Closed set of spending categories the dashboard reports on.
 *
 * <p>The {@code ticket_extractions.category} column is free text
 * ({@code VARCHAR(64)}) because the model emits whatever label fits the
 * receipt. Reporting needs a closed set instead: the donut chart would
 * grow an unbounded slice set and the palette would run out. This enum
 * is the whitelist, and {@link #fromExtractionValue(String)} is the only
 * sanctioned way to turn a raw column into a reportable category.
 *
 * <p>Unknown or blank values collapse to {@link #OTHER} rather than
 * being rejected: a receipt we could not classify is still money the
 * user spent, and dropping it would make the category total disagree
 * with the KPI total.
 */
public enum SpendCategory {

    TRANSPORT("transport"),
    FOOD("food"),
    LODGING("lodging"),
    OTHER("other");

    private final String wireName;

    SpendCategory(String wireName) {
        this.wireName = wireName;
    }

    /**
     * Lowercase slug sent over the wire and understood by the front.
     * Deliberately not {@link #name()}: the enum constant is
     * {@code TRANSPORT} but the SPA has always keyed charts on
     * {@code 'transport'}, and the mock payload it replaces used the
     * lowercase form.
     */
    public String wireName() {
        return wireName;
    }

    /**
     * Map a raw {@code ticket_extractions.category} value onto the
     * whitelist. Matching is case- and whitespace-insensitive; anything
     * unrecognised (including {@code null} and blank) becomes
     * {@link #OTHER}.
     */
    public static SpendCategory fromExtractionValue(String raw) {
        if (raw == null) {
            return OTHER;
        }
        String normalised = raw.trim().toLowerCase(Locale.ROOT);
        if (normalised.isEmpty()) {
            return OTHER;
        }
        for (SpendCategory candidate : values()) {
            if (candidate.wireName.equals(normalised)) {
                return candidate;
            }
        }
        return OTHER;
    }
}