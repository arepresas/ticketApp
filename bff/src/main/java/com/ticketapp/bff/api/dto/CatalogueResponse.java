package com.ticketapp.bff.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Wire response for {@code GET /api/tickets/{id}/catalogue}.
 *
 * <p>The catalogue view is what the user sees after
 * {@code DONE}: shop + lines joined from {@code shops},
 * {@code products} and {@code prices}, fanned out through
 * {@code line_tickets}. The endpoint is a read-only mirror of
 * what the normaliser wrote; there's intentionally no edit
 * surface — validated tickets are immutable.
 *
 * <p>{@code lineTotal} comes from {@code line_tickets.line_total}
 * (the exact value the AI or the user finalised). The
 * {@code pricePerUnit} comes from the price snapshot the
 * normaliser captured; a ticket with two distinct amounts for
 * the same product (e.g. loyalty discount variant) keeps both
 * rows here.
 */
public record CatalogueResponse(
        UUID shopId,
        String shopName,
        List<CatalogueLine> lines) {

    public record CatalogueLine(
            String productName,
            String unit,
            BigDecimal quantity,
            BigDecimal pricePerUnit,
            BigDecimal lineTotal) { }
}
