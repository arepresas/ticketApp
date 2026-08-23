package com.ticketapp.domain;

import java.util.Collection;
import java.util.List;
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
     * Search products whose canonical name starts with the supplied
     * prefix (case-insensitive, after {@link
     * Product#normalisedNameOf(String)}). Used by the ticket-detail
     * autocomplete in the SPA — a user typing in the line-editor
     * sees matching catalogue rows and the editor can render an
     * icon to distinguish "already in DB" from "new".
     *
     * <p>Matches are returned ordered by name (so the most likely
     * candidate is first), capped at {@code limit}. Empty input
     * returns an empty list — the SPA handles the empty-input
     * "show all" itself if it wants.
     *
     * <p>Like {@link #findByNormalisedName(String, String)}, the
     * match key is {@code (normalisedName, unit)}; units are
     * ignored for the SPA's hint-icon purpose — a "Bread" with no
     * unit and a "Bread" with {@code kg} both visually read as the
     * same product to the user, even though they collide in the
     * catalogue. The {@link #findByNormalisedName} follow-up picks
     * the exact row when the user commits the line.
     */
    List<Product> searchByNormalisedName(String prefix, int limit);

    /**
     * Insert (or update) a product row. The match key uniqueness is
     * enforced by the database — re-inserting the same
     * {@code (normalisedName, unit)} tuple replaces the existing row
     * but keeps the original {@code id}, which is the contract that
     * {@code TicketExtractionNormaliser} relies on.
     */
    Product save(Product product);
}
