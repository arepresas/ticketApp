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
import java.time.YearMonth;
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
 */
@DisplayName("DashboardService")
class DashboardServiceTest {

    private static final long OWNER = 42L;

    private TicketStatsRepository stats;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        stats = mock(TicketStatsRepository.class);
        service = new DashboardService(stats);
    }

    @Test
    @DisplayName("reports exactly one currency, so amounts are never summed across currencies")
    void reportsSingleCurrency() {
        stubStats();

        DashboardService.DashboardView view = service.load(OWNER);

        assertThat(view.kpis().currency()).isEqualTo("EUR");
        // The same currency is pushed down to every aggregate, so the
        // KPIs and the donut cannot describe different currencies.
        verify(stats).loadStats(OWNER, DashboardService.REPORTING_CURRENCY);
        verify(stats).sumByCategory(OWNER, DashboardService.REPORTING_CURRENCY);
    }

    @Test
    @DisplayName("asks for a window ending on the current month and spanning MONTHS_OF_HISTORY")
    void asksForBoundedWindow() {
        stubStats();

        service.load(OWNER);

        YearMonth current = YearMonth.now();
        YearMonth expectedFrom = current.minusMonths(DashboardService.MONTHS_OF_HISTORY - 1L);
        verify(stats).countByMonth(eq(OWNER), eq(expectedFrom), eq(current));
    }

    @Test
    @DisplayName("returns a gap-free monthly series covering the whole window")
    void returnsGapFreeMonthlySeries() {
        stubStats();
        // Only the current month has data; the rest must still appear as
        // zeros or the chart's x-axis gets holes. Stubbed *after*
        // stubStats() so it is not overwritten by the default empty list.
        when(stats.countByMonth(anyLong(), any(), any())).thenReturn(List.of(
                new MonthlyTicketCount(YearMonth.now(), 4)));

        List<MonthlyTicketCount> series = service.load(OWNER).ticketsPerMonth();

        assertThat(series).hasSize(DashboardService.MONTHS_OF_HISTORY);
        assertThat(series.get(series.size() - 1).count()).isEqualTo(4);
        assertThat(series.subList(0, series.size() - 1))
                .allSatisfy(m -> assertThat(m.count()).isZero());
    }

    @Test
    @DisplayName("returns all four categories regardless of what the adapter returned")
    void returnsAllCategories() {
        when(stats.sumByCategory(anyLong(), anyString())).thenReturn(List.of(
                new CategorySpend(SpendCategory.FOOD, new BigDecimal("12.00"))));
        stubStats();

        List<CategorySpend> series = service.load(OWNER).spendByCategory();

        assertThat(series).extracting(CategorySpend::category)
                .containsExactly(SpendCategory.TRANSPORT, SpendCategory.FOOD,
                        SpendCategory.LODGING, SpendCategory.OTHER);
    }

    @Test
    @DisplayName("returns zeros rather than failing when the owner has no data")
    void handlesEmptyOwner() {
        TicketStats empty = new TicketStats(0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, "EUR");
        when(stats.loadStats(anyLong(), anyString())).thenReturn(empty);
        when(stats.countByMonth(anyLong(), any(), any())).thenReturn(List.of());
        when(stats.sumByCategory(anyLong(), anyString())).thenReturn(List.of());

        DashboardService.DashboardView view = service.load(OWNER);

        assertThat(view.kpis().totalTickets()).isZero();
        assertThat(view.ticketsPerMonth()).hasSize(DashboardService.MONTHS_OF_HISTORY);
        assertThat(view.spendByCategory()).hasSize(SpendCategory.values().length);
    }

    private void stubStats() {
        TicketStats kpis = new TicketStats(3, 1, 2,
                new BigDecimal("40.00"), new BigDecimal("20.00"), "EUR");
        when(stats.loadStats(anyLong(), anyString())).thenReturn(kpis);
        when(stats.countByMonth(anyLong(), any(), any())).thenReturn(List.of());
        when(stats.sumByCategory(anyLong(), anyString())).thenReturn(List.of());
    }
}