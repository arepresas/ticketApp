package com.ticketapp.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for the {@link Shop} master registry. The shop is
 * the merchant the receipt came from; the lookup key is the
 * normalised merchant name (lower-trim) so the same chain across
 * tickets collapses to one row regardless of capitalisation or
 * stray whitespace.
 */
public interface ShopRepository {

    Optional<Shop> findByNormalisedName(String normalisedName);

    Optional<Shop> findById(UUID id);

    /**
     * Insert or update a shop row, keyed on the normalised merchant
     * name as enforced by the database. Returns the row
     * <em>as stored</em> — on conflict that is the existing row, so
     * callers must use the returned id for {@code tickets.shop_id}.
     */
    Shop save(Shop shop);
}
