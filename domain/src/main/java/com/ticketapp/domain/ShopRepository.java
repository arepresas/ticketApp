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

    Shop save(Shop shop);
}
