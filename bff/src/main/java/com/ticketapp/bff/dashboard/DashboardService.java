package com.ticketapp.bff.dashboard;

import com.ticketapp.domain.CategorySpend;
import com.ticketapp.domain.MonthlyTicketCount;
import com.ticketapp.domain.TicketStats;
import com.ticketapp.domain.TicketStatsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/**
 * Assembles the dashboard read-model for one owner.
 *
 * <p>Two presentation policies live here rather than in the adapter,
 * because both are decisions about what the user sees rather than
 * about how the rows are stored:
 *
 * <ul>
 *   <li><b>The monthly window.</b> Charts need a fixed-width x-axis, so
 *       the range is derived here (see {@link #MONTHS_OF_HISTORY}) and
 *       the gap-filling that keeps the line continuous lives in
 *       {@link MonthlyTicketCount#complete}. The current month comes
 *       from an injected {@link Clock} rather than a bare
 *       {@code YearMonth.now()} — see {@link #REPORTING_ZONE}.</li>
 *   <li><b>The currency.</b> {@code ticket_extractions.currency} is
 *       per-row and there is no per-user preference in the model, so
 *       exactly one currency is reported and named in the response for
 *       the UI to render.</li>
 * </ul>
 *
 * <p>Owner scoping is not this class's job — it is the repository's,
 * and every method there requires an {@code ownerId}. This class only
 * passes the authenticated id through.
 *
 * <p><b>Snapshot.</b> The payload is assembled from three separate
 * queries. Under PostgreSQL's default {@code READ COMMITTED} they can
 * each see a different database state, so a ticket created or
 * re-extracted between two of them yields a dashboard whose cards
 * disagree with its charts. {@code REPEATABLE_READ} pins all three to
 * one snapshot. The repository declares no isolation of its own, so
 * every call joins this transaction rather than opening its own.
 */
@Service
@Slf4j
public class DashboardService {

    /**
     * How many months of history the "tickets per month" chart shows,
     * including the current one. Six months is what fits the dashboard
     * grid without the x-axis labels colliding, and it keeps the query
     * bounded regardless of how long the account has been in use.
     */
    static final int MONTHS_OF_HISTORY = 6;

    /**
     * Reported currency. Matches the {@code ticket_extractions.currency}
     * column default. See the class Javadoc for why the dashboard
     * commits to one instead of summing them.
     */
    static final String REPORTING_CURRENCY = "EUR";

    /**
     * Business zone the reporting month is decided in.
     *
     * <p>UTC, deliberately. The alternative — {@code systemDefault()} —
     * makes the same deployment report a different current month
     * depending on which node and which container image serves the
     * request, and two nodes disagreeing would produce two different
     * charts for the same user. UTC is arbitrary but stable, and a
     * future per-user preference would replace this single constant.
     */
    static final ZoneId REPORTING_ZONE = ZoneId.of("UTC");

    private final TicketStatsRepository stats;
    private final Clock clock;

    public DashboardService(TicketStatsRepository stats, Clock clock) {
        this.stats = stats;
        this.clock = clock;
    }

    /**
     * @param ownerId the authenticated user; every figure is scoped to
     *                their tickets
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardView load(long ownerId) {
        TicketStats kpis = stats.loadStats(ownerId, REPORTING_CURRENCY);

        // Resolved once and reused: asking the clock twice could straddle
        // a month boundary and produce a window that is seven months
        // long or five.
        YearMonth currentMonth = YearMonth.now(clock.withZone(REPORTING_ZONE));
        YearMonth from = currentMonth.minusMonths(MONTHS_OF_HISTORY - 1L);
        List<MonthlyTicketCount> perMonth = MonthlyTicketCount.complete(
                from, currentMonth, stats.countByMonth(ownerId, from, currentMonth));

        List<CategorySpend> perCategory = CategorySpend.complete(
                stats.sumByCategory(ownerId, REPORTING_CURRENCY));

        log.debug("Dashboard for owner {}: {} tickets, {} open, {} extracted",
                ownerId, kpis.totalTickets(), kpis.openTickets(), kpis.extractedTicketsInCurrency());

        return new DashboardView(kpis, perMonth, perCategory);
    }

    /**
     * The assembled payload. Deliberately not a wire DTO — the
     * controller maps it to {@code DashboardResponse} so the JSON
     * contract stays a separate concern from the domain records.
     */
    public record DashboardView(
            TicketStats kpis,
            List<MonthlyTicketCount> ticketsPerMonth,
            List<CategorySpend> spendByCategory) {
    }
}