package com.ticketapp.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Master merchant registry. The extraction payload carries the
 * {@code merchant} string verbatim; normalising it against this
 * table lets downstream analytics group spend by shop ("Mercadona"
 * across the lifetime of the project collapses to a single row,
 * regardless of capitalisation or stray spaces).
 *
 * <p>Pure domain value object. Identity is the {@code id} assigned
 * at creation time; {@code normalisedName} is the comparison key
 * used by the ShopRepository's lookup. Trimming + lowercasing
 * happens once via {@link #normalisedNameOf(String)} so all callers
 * compute the same canonical form.
 *
 * <p>The cataloguing of a shop is opportunistic: a row is created
 * the first time any ticket's extraction mentions that merchant,
 * not eagerly ahead of time. Cancellation of the seeding ticket does
 * not delete the shop row — it's left around so subsequent tickets
 * mentioning the same merchant land on the same row.
 *
 * <p><b>Contact info</b> ({@code addressLine}, {@code postalCode},
 * {@code city}, {@code country}, {@code phone}, {@code taxId},
 * {@code website}) is best-effort: the AI populates what the receipt
 * prints, the user can edit the rest via
 * {@code PATCH /api/shops/{id}}. All nullable; the only "must be
 * non-blank" rule lives on {@code name}. {@code country} is the
 * ISO 3166-1 alpha-2 code (ES, FR, PT, ...); {@code taxId} is the
 * merchant's local tax identifier (CIF in ES, SIRET in FR, VAT
 * number elsewhere) — left as a free-form string so we don't have
 * to validate per-country formats in the domain.
 */
public record Shop(
        UUID id,
        String name,
        String normalisedName,
        String addressLine,
        String postalCode,
        String city,
        String country,
        String phone,
        String taxId,
        String website,
        Instant createdAt
) {
    public Shop {
        if (id == null) throw new NullPointerException("id");
        if (name == null) throw new NullPointerException("name");
        if (normalisedName == null) {
            normalisedName = normalisedNameOf(name);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * Convenience constructor for callers that don't have contact
     * info yet (initial seeding by the normaliser, hand-built
     * fixtures). Leaves the contact fields {@code null}.
     */
    public Shop(UUID id, String name, String normalisedName, Instant createdAt) {
        this(id, name, normalisedName, null, null, null, null, null, null, null, createdAt);
    }

    /**
     * Canonical form used as the lookup key. Locale-independent
     * to keep the same merchant mapped to the same row across
     * servers with different default locales.
     */
    public static String normalisedNameOf(String name) {
        if (name == null) throw new NullPointerException("name");
        return name.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Build a copy of this shop with the given contact fields
     * applied. {@code null} values in {@code patch} are skipped so
     * a PATCH can update a single field without erasing the others.
     * Non-null values replace the existing field verbatim.
     */
    public Shop withContact(String addressLine, String postalCode, String city,
                            String country, String phone, String taxId, String website) {
        return new Shop(
                id, name, normalisedName,
                addressLine != null ? addressLine : this.addressLine,
                postalCode  != null ? postalCode  : this.postalCode,
                city        != null ? city        : this.city,
                country     != null ? country     : this.country,
                phone       != null ? phone       : this.phone,
                taxId       != null ? taxId       : this.taxId,
                website     != null ? website     : this.website,
                createdAt);
    }
}