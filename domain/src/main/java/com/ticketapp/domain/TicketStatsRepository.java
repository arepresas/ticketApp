package com.ticketapp.domain;

import java.time.YearMonth;
import java.util.List;

/**
 * Outbound port for the dashboard's reporting aggregates.
 *
 * <p>These are read models, not entities: they have no identity, no
 * lifecycle and are never persisted. That is why they are their own port
 * rather than extra methods on {@link TicketRepository} — the queries
 * behind them group and aggregate across {@code tickets} and
 * {@code ticket_extractions}, which the entity repository deliberately
 * does not model.
 *
 * <p><b>Every method is owner-scoped.</b> There is no variant without
 * an {@code ownerId}: the dashboard is per-user, and an unscoped
 * aggregate would silently leak one user's spending to another.
 *
 * <p><b>All methods return {@code null}-free, immutable collections</b>
 * and {@code ZERO} rather than an empty money total when nothing
 * matches, so callers never have to distinguish "no rows" from "no
 * value".
 */
public interface TicketStatsRepository {

    /**
     * Headline counters for one owner. See {@link TicketStats} for which
     * statuses count as open and how the currency scoping works.
     *
     * @param ownerId  the authenticated user; results are restricted to
     *                 their tickets
     * @param currency ISO 4217 code the money totals are expressed in;
     *                    only extractions in this currency contribute
     * @return the counters, never {@code null}
     */
    TicketStats loadStats(long ownerId, String currency);

    /**
     * Extracted-ticket counts per calendar month, keyed on the receipt's
     * {@code purchase_date}.
     *
     * <p>Returns a <em>sparse</em> series — only months that have at
     * rows. Callers that need a contiguous series run the result through
     * {@link MonthlyTicketCount#complete}, which owns the gap-filling
     * policy.
     *
     * <p>The window is half-open on the adapter side and inclusive here:
     * {@code from} and {@code to} are both included. Cancelled and
     * deleted tickets are excluded, matching
     * {@link TicketStats#totalTickets()}.
     *
     * @param from  first month to report, inclusive
     * @param to    last month to report, inclusive
     */
    List<MonthlyTicketCount> countByMonth(long ownerId, YearMonth from, YearMonth to);

    /**
     * Spend per {@link SpendCategory} for one owner in one currency.
     *
     * <p>Raw {@code category} values are folded onto the
     * {@link SpendCategory} whitelist by
     * {@link SpendCategory#fromExtractionValue(String)}, so an
     * unrecognised model label lands in {@link SpendCategory#OTHER}
     * instead of adding an arc the chart cannot draw. The result is
     * sparse; {@link CategorySpend#complete} pins it to one row per
     * category.
     */
    List<CategorySpend> sumByCategory(long ownerId, String currency);
}