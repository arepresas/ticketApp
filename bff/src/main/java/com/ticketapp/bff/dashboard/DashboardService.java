package com.ticketapp.bff.dashboard;

import com.ticketapp.domain.CategorySpend;
import com.ticketapp.domain.MonthlyTicketCount;
import com.ticketapp.domain.TicketStats;
import com.ticketapp.domain.TicketStatsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
 *       {@link MonthlyTicketCount#complete}.</li>
 *   <li><b>The currency.</b> {@code ticket_extractions.currency} is
 *       per-row, and there is no per-user currency preference in the
 *       model. Summing across currencies would produce a number that
 *       means nothing, so exactly one currency is reported and it is
 *       named in the response for the UI to render. EUR matches the
 *       column default; a future user preference would replace this
 *       constant and nothing else would have to move.</li>
 * </ul>
 *
 * <p>Owner scoping is not this class's job — it is the repository's,
 * and every method there requires an {@code ownerId}. This class only
 * passes the authenticated id through.
 */
@Service
@RequiredArgsConstructor
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

    private final TicketStatsRepository stats;

    /**
     * @param ownerId the authenticated user; every figure is scoped to
     *                their tickets
     */
    public DashboardView load(long ownerId) {
        TicketStats kpis = stats.loadStats(ownerId, REPORTING_CURRENCY);

        // Zone stated explicitly (java:S8688). Leaving it implicit makes
        // the series window depend on whichever zone the JVM happens to
        // run in, so the same deployment would show a different x-axis
        // in Madrid than in a UTC container.
        YearMonth currentMonth = YearMonth.now(ZoneId.systemDefault());
        YearMonth from = currentMonth.minusMonths(MONTHS_OF_HISTORY - 1L);
        List<MonthlyTicketCount> perMonth = MonthlyTicketCount.complete(
                from, currentMonth, stats.countByMonth(ownerId, from, currentMonth));

        List<CategorySpend> perCategory = CategorySpend.complete(
                stats.sumByCategory(ownerId, REPORTING_CURRENCY));

        log.debug("Dashboard for owner {}: {} tickets, {} open, {} extracted",
                ownerId, kpis.totalTickets(), kpis.openTickets(), kpis.extractedTickets());

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