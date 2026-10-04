package com.ticketapp.bff.dashboard;

import com.ticketapp.domain.CategorySpend;
import com.ticketapp.domain.MonthlyTicketCount;
import com.ticketapp.domain.SpendCategory;
import com.ticketapp.domain.TicketStats;
import com.ticketapp.domain.TicketStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link DashboardService}. The two policies it owns —
 * the monthly window and the reported currency — are invisible in the
 * response shape but decide what the numbers mean, so they are pinned
 * here where no database is needed.
 *
 * <p>The clock is fixed. A test that called {@code YearMonth.now()} to
 * match the production call could fail intermittently whenever a month
 * boundary fell between the two, which is exactly the flakiness this
 * suite must not have.
 */
@DisplayName("DashboardService")
class DashboardServiceTest {

    private static final long OWNER = 42L;
    private static final ZoneId UTC = ZoneId.of("UTC");

    private TicketStatsRepository stats;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        stats = mock(TicketStatsRepository.class);
        service = serviceAt("2026-03-15T10:00:00Z");
    }

    private DashboardService serviceAt(String instant) {
        return new DashboardService(stats, Clock.fixed(Instant.parse(instant), UTC));
    }

    @Test
    @DisplayName("reports exactly one currency, so amounts are never summed across currencies")
    void reportsSingleCurrency() {
        stubStats(List.of());

        DashboardService.DashboardView view = service.load(OWNER);

        assertThat(view.kpis().currency()).isEqualTo("EUR");
        // The same currency is pushed down to every aggregate, so the
        // KPIs and the donut cannot describe different currencies.
        verify(stats).loadStats(OWNER, DashboardService.REPORTING_CURRENCY);
        verify(stats).sumByCategory(OWNER, DashboardService.REPORTING_CURRENCY);
    }

    @Test
    @DisplayName("asks for exactly the six-month window ending on the clock's month")
    void asksForBoundedWindow() {
        stubStats(List.of());

        service.load(OWNER);

        // Clock says March 2026, so the window is October 2025 … March
        // 2026 inclusive — asserted as literals, not recomputed with
        // YearMonth.now(), which is the bug this test used to have.
        verify(stats).countByMonth(OWNER,
                YearMonth.of(2026, Month.FEBRUARY).minusMonths(4),
                YearMonth.of(2026, Month.MARCH));
    }

    @Test
    @DisplayName("returns a gap-free monthly series of exactly MONTHS_OF_HISTORY points")
    void returnsGapFreeMonthlySeries() {
        // Declared after stubStats so the monthly row is the one that
        // survives: the shared helper sets countByMonth to empty.
        stubStats(List.of());
        stubMonths(List.of(new MonthlyTicketCount(YearMonth.of(2026, Month.MARCH), 4)));

        List<MonthlyTicketCount> series = service.load(OWNER).ticketsPerMonth();

        assertThat(series).hasSize(DashboardService.MONTHS_OF_HISTORY);
        assertThat(series.stream().map(MonthlyTicketCount::month).toList())
                .containsExactly(
                        YearMonth.of(2025, Month.OCTOBER),
                        YearMonth.of(2025, Month.NOVEMBER),
                        YearMonth.of(2025, Month.DECEMBER),
                        YearMonth.of(2026, Month.JANUARY),
                        YearMonth.of(2026, Month.FEBRUARY),
                        YearMonth.of(2026, Month.MARCH));
        assertThat(series.stream().map(MonthlyTicketCount::count).toList())
                .containsExactly(0L, 0L, 0L, 0L, 0L, 4L);
    }

    @Test
    @DisplayName("reports the month the clock says even on the first day of a month")
    void handlesFirstDayOfMonth() {
        stubStats(List.of());
        service = serviceAt("2026-04-01T00:30:00Z");

        service.load(OWNER);

        verify(stats).countByMonth(OWNER,
                YearMonth.of(2025, Month.NOVEMBER), YearMonth.of(2026, Month.APRIL));
    }

    @Test
    @DisplayName("reports the previous month on the last instant before a rollover")
    void handlesLastInstantBeforeRollover() {
        stubStats(List.of());
        service = serviceAt("2026-03-31T23:59:59Z");

        service.load(OWNER);

        verify(stats).countByMonth(OWNER,
                YearMonth.of(2025, Month.OCTOBER), YearMonth.of(2026, Month.MARCH));
    }

    @Test
    @DisplayName("resolves the current month once per request")
    void resolvesMonthOnce() {
        stubStats(List.of());
        service = serviceAt("2026-03-31T23:59:59.999Z");

        // Both bounds must come from the same read. Asking twice could
        // straddle a boundary and yield a five- or seven-month window.
        service.load(OWNER);

        verify(stats).countByMonth(eq(OWNER),
                eq(YearMonth.of(2025, Month.OCTOBER)), eq(YearMonth.of(2026, Month.MARCH)));
    }

    @Test
    @DisplayName("returns all four categories regardless of what the adapter returned")
    void returnsAllCategories() {
        stubStats(List.of(new CategorySpend(SpendCategory.FOOD, new BigDecimal("12.00"))));

        List<CategorySpend> series = service.load(OWNER).spendByCategory();

        assertThat(series).extracting(CategorySpend::category)
                .containsExactly(SpendCategory.TRANSPORT, SpendCategory.FOOD,
                        SpendCategory.LODGING, SpendCategory.OTHER);
        // The amount must survive: an earlier version of this test
        // stubbed sumByCategory before the shared helper and so
        // asserted against an empty list, passing for the wrong reason.
        assertThat(series).filteredOn(r -> r.category() == SpendCategory.FOOD)
                .singleElement()
                .satisfies(row -> assertThat(row.amount()).isEqualByComparingTo("12.00"));
    }

    @Test
    @DisplayName("returns zeros rather than failing when the owner has no data")
    void handlesEmptyOwner() {
        when(stats.loadStats(anyLong(), anyString())).thenReturn(new TicketStats(
                0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, "EUR"));
        when(stats.countByMonth(anyLong(), any(), any())).thenReturn(List.of());
        when(stats.sumByCategory(anyLong(), anyString())).thenReturn(List.of());

        DashboardService.DashboardView view = service.load(OWNER);

        assertThat(view.kpis().totalTickets()).isZero();
        assertThat(view.ticketsPerMonth()).hasSize(DashboardService.MONTHS_OF_HISTORY);
        assertThat(view.spendByCategory()).hasSize(SpendCategory.values().length);
    }

    @Test
    @DisplayName("passes the authenticated owner id to every query")
    void scopesEveryQueryToTheOwner() {
        stubStats(List.of());

        service.load(OWNER);

        verify(stats).loadStats(OWNER, DashboardService.REPORTING_CURRENCY);
        verify(stats).countByMonth(eq(OWNER), any(), any());
        verify(stats).sumByCategory(OWNER, DashboardService.REPORTING_CURRENCY);
    }

    /**
     * Single place the defaults are declared. Ordering matters: these
     * stubs are declared in one helper so a test cannot accidentally
     * register its own stub first and have it overwritten here, which is
     * how {@code returnsAllCategories} came to assert nothing.
     */
    private void stubStats(List<CategorySpend> categories) {
        when(stats.loadStats(anyLong(), anyString())).thenReturn(new TicketStats(
                3, 1, 2, new BigDecimal("40.00"), new BigDecimal("20.00"), "EUR"));
        when(stats.countByMonth(anyLong(), any(), any())).thenReturn(List.of());
        when(stats.sumByCategory(anyLong(), anyString())).thenReturn(categories);
    }

    /** One row per month the fixture knows about, keyed to the fixed clock. */
    private void stubMonths(List<MonthlyTicketCount> months) {
        when(stats.countByMonth(anyLong(), any(), any())).thenReturn(months);
    }

    private static CategorySpend categorySpend(SpendCategory category, String amount) {
        return new CategorySpend(category, new BigDecimal(amount));
    }
}