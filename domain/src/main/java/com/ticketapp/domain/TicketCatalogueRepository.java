package com.ticketapp.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for the normalised catalogue view of a ticket: the
 * shop it was validated against plus one line per catalogue row.
 *
 * <p><b>Why this exists instead of four repository calls in the
 * controller.</b> Assembling the view needs the ticket, its lines,
 * the product master rows, the price rows and the shop. Doing that
 * in the HTTP layer spread the read fan-out, the "no catalogue yet"
 * 404 rules and the null-coalescing policy across a controller,
 * where none of it was unit-testable. Here the fan-out, the
 * ordering and the "absent master row means null field, not zero"
 * policy are one contract with one owner.
 *
 * <p><b>Owner-scoped by construction.</b> The only way to obtain a
 * view is to present the owner's id, and the adapter applies it to
 * the ticket — so a caller cannot read another user's catalogue even
 * by forgetting a check that lived one layer up.
 */
public interface TicketCatalogueRepository {

    /**
     * Read the catalogue view of one ticket owned by
     * {@code ownerId}.
     *
     * @return the view, or empty when the ticket does not exist, is
     *         owned by somebody else, has not been normalised yet
     *         (no {@code shop_id}), or has no catalogue lines. The
     *         four cases are deliberately indistinguishable: the
     *         caller answers 404 for all of them and must not leak
     *         which one it was.
     */
    Optional<TicketCatalogue> findByTicketId(UUID ticketId, UUID ownerId);

    /**
     * A validated ticket's catalogue: the shop it anchors to, and
     * the lines in receipt order.
     *
     * @param shop the shop the ticket is anchored on; never null
     * @param lines one entry per {@code line_tickets} row, in
     *              receipt order; never empty
     */
    record TicketCatalogue(Shop shop, List<CatalogueLine> lines) {
        public TicketCatalogue {
            if (shop == null) throw new NullPointerException("shop");
            if (lines == null || lines.isEmpty()) {
                throw new IllegalArgumentException("lines must not be empty");
            }
            lines = List.copyOf(lines);
        }
    }

    /**
     * One catalogue line, projected for display.
     *
     * <p>A {@code null} product or price name/amount means the
     * master row is missing (a catalogue row deleted out from under
     * the ticket). It stays null rather than becoming an empty
     * string or a zero — the dashboard renders "unknown" and the
     * difference between "no price recorded" and "priced at 0" is
     * worth preserving.
     */
    record CatalogueLine(
            String productName,
            String unit,
            java.math.BigDecimal quantity,
            BigDecimal pricePerUnit,
            BigDecimal lineTotal
    ) {
        public CatalogueLine {
            if (quantity == null) throw new NullPointerException("quantity");
            if (lineTotal == null) throw new NullPointerException("lineTotal");
        }
    }
}
