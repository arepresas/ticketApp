package com.ticketapp.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Headline counters for one owner's dashboard.
 *
 * <h2>Which tickets count</h2>
 * <ul>
 *   <li>{@code totalTickets} counts every ticket the owner can still see
 *       a record of, i.e. everything except {@code DELETED} (soft
 *       delete) and {@code CANCELLED} (dismissed on purpose). Those two
 *       are terminal and non-existent as far as the user is concerned,
 *       so counting them would make the KPI disagree with the tickets
 *       table right below it.</li>
 *   <li>{@code openTickets} counts everything <em>not</em> in a terminal
 *       state — {@code OPEN}, {@code IN_ANALYSIS}, {@code IN_PROGRESS}
 *       and {@code ON_ERROR}. This is deliberately not "just
 *       {@code OPEN}": a ticket mid-extraction still needs attention,
 *       and the pending view the dashboard already shows uses the same
 *       set (see {@code GET /api/tickets/pending}).</li>
 * </ul>
 *
 * <h2>Currency</h2>
 * {@code totalSpent} and {@code averageTicketValue} are sums over a
 * <em>single</em> currency, named by {@link #currency()}. Adding EUR to
 * GBP would produce a meaningless number, so the caller states which
 * currency it wants and the totals describe only that one. The dashboard
 * renders the symbol from {@link #currency()} rather than assuming
 * euros.
 *
 * <p>{@code averageTicketValue} is {@code totalSpent} divided by the
 * number of <em>extracted</em> tickets in that currency, which is not
 * necessarily {@code totalTickets}: a ticket can exist without ever
 * having been extracted, and a zero would be the wrong answer for it.
 */
public record TicketStats(
        long totalTickets,
        long openTickets,
        long extractedTickets,
        BigDecimal totalSpent,
        BigDecimal averageTicketValue,
        String currency) {

    public TicketStats {
        if (totalTickets < 0 || openTickets < 0 || extractedTickets < 0) {
            throw new IllegalArgumentException(
                    "ticket counts must not be negative: total=" + totalTickets
                            + " open=" + openTickets + " extracted=" + extractedTickets);
        }
        if (totalSpent == null || averageTicketValue == null) {
            throw new IllegalArgumentException("amounts must not be null");
        }
        if (openTickets > totalTickets) {
            throw new IllegalArgumentException(
                    "openTickets (" + openTickets + ") cannot exceed totalTickets (" + totalTickets + ")");
        }
        currency = Objects.requireNonNull(currency, "currency must not be null").trim();
        if (currency.isEmpty()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
    }
}