package com.ticketapp.bff.api.dto;

import com.ticketapp.bff.dashboard.DashboardService.DashboardView;

import java.math.BigDecimal;
import java.util.List;

/**
 * Wire response for {@code GET /api/dashboard} — the KPI cards and both
 * charts.
 *
 * <p>Replaces the mock payload the SPA used to ship, so the field names
 * deliberately match what {@code front/src/lib/api/dashboard.ts}
 * already declares. Two of them were renamed on purpose:
 * {@code totalSpentEur} and {@code avgTicketEur} are now
 * {@code totalSpent} and {@code avgTicketValue}, because the money is
 * reported in {@link #currency()} rather than assumed to be euros — the
 * old names baked an assumption in that the schema does not guarantee.
 *
 * <p>{@code currency} travels with the amounts so the UI renders the
 * right symbol instead of hardcoding {@code €}.
 */
public record DashboardResponse(
        Kpi kpis,
        List<MonthlyTicket> ticketsPerMonth,
        List<CategoryAmount> spendByCategory) {

    /**
     * @param totalTickets      tickets the user can still see a record of
     *                          (excludes deleted and cancelled)
     * @param openTickets       tickets not yet in a terminal state
     * @param extractedTickets  tickets the AI has actually read; the
     *                          average is taken over these, not over
     *                          {@code totalTickets}
     * @param totalSpent        sum over {@code extractedTickets} in
     *                          {@code currency}
     */
    public record Kpi(
            long totalTickets,
            long openTickets,
            long extractedTickets,
            BigDecimal totalSpent,
            BigDecimal avgTicketValue,
            String currency) {
    }

    /**
     * One point of the monthly line. {@code month} is an ISO year-month
     * ({@code "2026-03"}) and is always the receipt's purchase month, not
     * its upload date.
     */
    public record MonthlyTicket(String month, long count) {
    }

    /** One slice of the category donut, always all four categories. */
    public record CategoryAmount(String category, BigDecimal amount) {
    }

    public static DashboardResponse of(DashboardView view) {
        return new DashboardResponse(
                new Kpi(
                        view.kpis().totalTickets(),
                        view.kpis().openTickets(),
                        view.kpis().extractedTickets(),
                        view.kpis().totalSpent(),
                        view.kpis().averageTicketValue(),
                        view.kpis().currency()),
                view.ticketsPerMonth().stream()
                        .map(m -> new MonthlyTicket(m.month().toString(), m.count()))
                        .toList(),
                view.spendByCategory().stream()
                        .map(c -> new CategoryAmount(c.category().wireName(), c.amount()))
                        .toList());
    }
}