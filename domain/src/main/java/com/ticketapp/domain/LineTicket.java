package com.ticketapp.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of a receipt, normalised. A ticket that bought a
 * product at a specific quantity and price-per-unit. Multiple
 * lines per ticket are expected (one per product); multiple lines
 * per product on the same ticket are not (re-validation upserts
 * the existing row).
 *
 * <p>{@code lineTotal} is the invoice line value the AI extracted.
 * It can be negative for discount / credit rows even when
 * {@code quantity} and the linked price's {@code amount} are both
 * strictly positive — discounts model the inverse spend against the
 * ticket total, not against the line's pricePerUnit. The
 * repository doesn't enforce {@code lineTotal = quantity * amount}
 * because the AI doesn't either, and we trust the extraction as
 * the source of truth.
 *
 * <p>The shop the ticket was bought from is NOT on the line — it
 * lives on the ticket itself ({@link Ticket#shopId()}). Every
 * line of a ticket shares the same shop by construction: the
 * normaliser resolves the shop once per ticket and writes it to
 * {@code tickets.shop_id} before persisting any line. A line that
 * carried its own {@code shop_id} would be a schema smell (a
 * single ticket can never have two different shops on its lines)
 * and was removed in the V13 refactor.
 *
 * <p>References:
 * <ul>
 *   <li>{@code product_id} — the catalog row matching the line's name+unit.</li>
 *   <li>{@code price_id}   — the per-ticket price snapshot. Lets a
 *       later feature surface "this product cost X on this ticket"
 *       without re-querying the receipt.</li>
 * </ul>
 */
public record LineTicket(
        UUID id,
        UUID ticketId,
        UUID productId,
        UUID priceId,
        BigDecimal quantity,
        BigDecimal lineTotal,
        Instant createdAt,
        Instant updatedAt
) {
    public LineTicket {
        if (id == null) throw new NullPointerException("id");
        if (ticketId == null) throw new NullPointerException("ticketId");
        if (productId == null) throw new NullPointerException("productId");
        if (priceId == null) throw new NullPointerException("priceId");
        if (quantity == null) throw new NullPointerException("quantity");
        if (lineTotal == null) throw new NullPointerException("lineTotal");
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
    }
}
