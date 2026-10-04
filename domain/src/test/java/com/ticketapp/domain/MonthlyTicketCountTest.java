package com.ticketapp.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.Month;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("MonthlyTicketCount")
class MonthlyTicketCountTest {

    @Test
    @DisplayName("rejects a null month")
    void rejectsNullMonth() {
        assertThrows(IllegalArgumentException.class,
                () -> new MonthlyTicketCount(null, 1));
    }

    @Test
    @DisplayName("rejects a negative count")
    void rejectsNegativeCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new MonthlyTicketCount(YearMonth.of(2026, Month.JANUARY), -1));
    }

    @Test
    @DisplayName("inserts zero-count months for gaps")
    void completeFillsGaps() {
        List<MonthlyTicketCount> rows = List.of(
                new MonthlyTicketCount(YearMonth.of(2026, Month.JANUARY), 5),
                new MonthlyTicketCount(YearMonth.of(2026, Month.APRIL), 9));

        List<MonthlyTicketCount> series = MonthlyTicketCount.complete(
                YearMonth.of(2026, Month.JANUARY), YearMonth.of(2026, Month.APRIL), rows);

        assertEquals(4, series.size());
        assertEquals(5, series.get(0).count());
        assertEquals(0, series.get(1).count());
        assertEquals(0, series.get(2).count());
        assertEquals(9, series.get(3).count());
    }

    @Test
    @DisplayName("drops rows outside the requested window")
    void completeDropsRowsOutsideWindow() {
        List<MonthlyTicketCount> rows = List.of(
                new MonthlyTicketCount(YearMonth.of(2025, Month.NOVEMBER), 3),
                new MonthlyTicketCount(YearMonth.of(2026, Month.FEBRUARY), 4),
                new MonthlyTicketCount(YearMonth.of(2026, Month.JUNE), 8));

        List<MonthlyTicketCount> series = MonthlyTicketCount.complete(
                YearMonth.of(2026, Month.JANUARY), YearMonth.of(2026, Month.MARCH), rows);

        assertEquals(3, series.size());
        assertEquals(0, series.get(0).count());
        assertEquals(4, series.get(1).count());
        assertEquals(0, series.get(2).count());
    }

    @Test
    @DisplayName("returns a single zero month for an empty window")
    void completeEmptyWindowIsOneZeroMonth() {
        List<MonthlyTicketCount> series = MonthlyTicketCount.complete(
                YearMonth.of(2026, Month.MAY), YearMonth.of(2026, Month.MAY), List.of());

        assertEquals(1, series.size());
        assertEquals(0, series.get(0).count());
    }

    @Test
    @DisplayName("rejects an inverted window instead of returning an empty series")
    void completeRejectsInvertedWindow() {
        assertThrows(IllegalArgumentException.class, () -> MonthlyTicketCount.complete(
                YearMonth.of(2026, Month.MAY), YearMonth.of(2026, Month.JANUARY), List.of()));
    }

    @Test
    @DisplayName("sums duplicate months rather than letting one win")
    void completeSumsDuplicateMonths() {
        List<MonthlyTicketCount> series = MonthlyTicketCount.complete(
                YearMonth.of(2026, Month.FEBRUARY), YearMonth.of(2026, Month.FEBRUARY),
                List.of(new MonthlyTicketCount(YearMonth.of(2026, Month.FEBRUARY), 4),
                        new MonthlyTicketCount(YearMonth.of(2026, Month.FEBRUARY), 6)));

        assertEquals(10, series.get(0).count());
    }
}