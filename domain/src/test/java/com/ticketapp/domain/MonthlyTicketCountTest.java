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

        // Exact size, exact months, exact order. Asserting only on the
        // counts would let an implementation emit duplicate or
        // out-of-order buckets through, which is what produces a chart
        // with repeated or shuffled x-axis points.
        assertEquals(4, series.size());
        assertEquals(List.of(
                YearMonth.of(2026, Month.JANUARY),
                YearMonth.of(2026, Month.FEBRUARY),
                YearMonth.of(2026, Month.MARCH),
                YearMonth.of(2026, Month.APRIL)),
                series.stream().map(MonthlyTicketCount::month).toList());
        assertEquals(List.of(5L, 0L, 0L, 9L),
                series.stream().map(MonthlyTicketCount::count).toList());
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
        assertEquals(List.of(
                YearMonth.of(2026, Month.JANUARY),
                YearMonth.of(2026, Month.FEBRUARY),
                YearMonth.of(2026, Month.MARCH)),
                series.stream().map(MonthlyTicketCount::month).toList());
        assertEquals(List.of(0L, 4L, 0L),
                series.stream().map(MonthlyTicketCount::count).toList());
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

        // The defining invariant: two rows in, one month out. An
        // implementation that echoed both back would still satisfy
        // "the count is 10" and would draw a duplicated chart point.
        assertEquals(1, series.size());
        assertEquals(YearMonth.of(2026, Month.FEBRUARY), series.get(0).month());
        assertEquals(10, series.get(0).count());
    }
}