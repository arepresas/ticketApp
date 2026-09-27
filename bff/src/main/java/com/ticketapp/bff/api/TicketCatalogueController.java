package com.ticketapp.bff.api;

import com.ticketapp.bff.api.dto.CatalogueResponse;
import com.ticketapp.domain.identity.AuthenticatedUser;
import com.ticketapp.bff.security.CurrentUser;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST surface for the normalised catalogue view of a ticket.
 *
 * <p>Split from {@code TicketController}: the catalogue read joins
 * four aggregates and deserves its own class instead of sharing one
 * with uploads, status flips and file streaming.
 */
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketCatalogueController {

    private final com.ticketapp.domain.TicketRepository repository;
    private final com.ticketapp.domain.ProductRepository products;
    private final com.ticketapp.domain.PriceRepository prices;
    private final com.ticketapp.domain.LineTicketRepository lineTickets;
    private final com.ticketapp.domain.ShopRepository shops;

    /**
     * Read the normalised view of a ticket. Populated by
     * {@code TicketExtractionNormaliser} when a ticket transitions
     * to {@code DONE}; falls through with 404 when the ticket either
     * doesn't belong to the caller or hasn't been validated yet.
     *
     * <p>Used by the detail screen once a ticket is in {@code DONE}
     * — the JSONB extraction becomes the AI's provenance record
     * at that point, and the catalogue is the source of truth for
     * what the user actually bought. The detail screen freezes
     * editing when this endpoint is in play (see the frontend's
     * ticket-status-driven branch in {@code TicketDetailApp.svelte}).
     *
     * <p>Three round trips total: one for the lines, one each for
     * the joined products and prices via IN-list batch. Acceptable
     * because line counts per ticket are small (single-digit to a
     * few dozen at worst) and the JOIN keeps the SQL understandable
     * without resorting to a wide denormalised read view.
     */
    @GetMapping("/{id}/catalogue")
    public ResponseEntity<CatalogueResponse> catalogue(@PathVariable UUID id) {
        AuthenticatedUser user = CurrentUser.get();
        // V13 refactor: shop_id lives on the ticket (set by the
        // normaliser during the DONE transition). The controller
        // re-reads the ticket so it picks up the freshly-written
        // shop_id without depending on the just-saved snapshot
        // from changeStatus — single round-trip, owner-scoped.
        java.util.Optional<com.ticketapp.domain.Ticket> maybeTicket =
                repository.findById(id, user.id());
        if (maybeTicket.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        com.ticketapp.domain.Ticket ticket = maybeTicket.get();
        if (ticket.shopId() == null) {
            // No normalised shop yet — ticket wasn't validated or
            // the normaliser hasn't run for some reason. Caller
            // treats 404 as "show the JSONB extraction view".
            return ResponseEntity.notFound().build();
        }
        List<com.ticketapp.domain.LineTicket> lines =
                lineTickets.findByTicketId(id);
        if (lines.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Set<UUID> productIds = lines.stream()
                .map(com.ticketapp.domain.LineTicket::productId)
                .collect(Collectors.toSet());
        Set<UUID> priceIds = lines.stream()
                .map(com.ticketapp.domain.LineTicket::priceId)
                .collect(Collectors.toSet());
        Map<UUID, com.ticketapp.domain.Product> productMap =
                products.findAllByIds(productIds);
        Map<UUID, com.ticketapp.domain.Price> priceMap =
                prices.findAllByIds(priceIds);
        // The shop FK now lives on the ticket itself (V13), so the
        // per-line shop_id column is gone — read straight from the
        // ticket. Returning Optional.empty() only happens if a row
        // is deleted out from under us; surface that as a 500-class
        // failure (shouldn't reach prod).
        com.ticketapp.domain.Shop shop = shops.findById(ticket.shopId())
                .orElseThrow(() -> new IllegalStateException(
                        "ticket " + ticket.id()
                                + " references missing shop " + ticket.shopId()));
        List<CatalogueResponse.CatalogueLine> wireLines = lines.stream()
                .map(lt -> {
                    com.ticketapp.domain.Product p = productMap.get(lt.productId());
                    com.ticketapp.domain.Price pr = priceMap.get(lt.priceId());
                    return new CatalogueResponse.CatalogueLine(
                            p != null ? p.name() : null,
                            p != null ? p.unit() : null,
                            lt.quantity(),
                            pr != null ? pr.amount() : null,
                            lt.lineTotal());
                })
                .toList();
        return ResponseEntity.ok(new CatalogueResponse(
                shop.id(), shop.name(), wireLines));
    }
}
