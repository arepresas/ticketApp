package com.ticketapp.domain;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for the {@link Product} catalogue. The product is the
 * master entity referenced by {@link Price} (per-ticket amount) and
 * {@link LineTicket} (per-ticket line).
 *
 * <p>Match contract: same {@code (normalisedName, unit)} yields the
 * same {@link Product}. Callers that need to associate a ticket's
 * line with an existing product use {@link #findByNormalisedName}
 * first and {@link #save(Product)} the new row when no match exists.
 * Both paths return a {@link Product}; the rest of the orchestrator
 * only sees the resulting id.
 */
public interface ProductRepository {

    /**
     * Find an existing product by its match key. Empty when no such
     * product exists.
     *
     * @param normalisedName canonical name (use
     *                       {@link Product#normalisedNameOf(String)});
     *                       case- and whitespace-insensitive lookup.
     * @param unit          unit label, nullable. NULL matches other
     *                      NULL rows; "kg" and NULL are distinct
     *                      products.
     */
    Optional<Product> findByNormalisedName(String normalisedName, String unit);

    /**
     * Batch lookup for the catalogue read API — fans out via an
     * {@code IN (?, ?, ...)} query so a single round-trip serves the
     * joined product view for a whole ticket.
     *
     * <p>The returned map covers every id present in {@code ids};
     * Ids that don't match a row are simply absent from the map (the
     * caller must tolerate the partial result — never silently
     * invent rows).
     */
    Map<UUID, Product> findAllByIds(Collection<UUID> ids);

    /**
     * Insert (or update) a product row. The match key uniqueness is
     * enforced by the database — re-inserting the same
     * {@code (normalisedName, unit)} tuple replaces the existing row
     * but keeps the original {@code id}, which is the contract that
     * {@code TicketExtractionNormaliser} relies on.
     */
    Product save(Product product);
}
