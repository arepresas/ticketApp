package com.ticketapp.domain;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Count of extracted tickets for one owner in one calendar month.
 *
 * <p>The month is the receipt's {@code purchase_date} month, not the
 * ticket's {@code created_at} month: users think "what did I buy in
 * March", and a backlog uploaded in October would otherwise pile every
 * receipt into a single October bar.
 */
public record MonthlyTicketCount(YearMonth month, long count) {

    public MonthlyTicketCount {
        if (month == null) {
            throw new IllegalArgumentException("month must not be null");
        }
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative: " + count);
        }
    }

    /**
     * Expand a sparse {@code GROUP BY} result into a gap-free series
     * covering {@code from}..{@code to} inclusive, inserting zero-count
     * months for the gaps.
     *
     * <p>SQL {@code GROUP BY} only emits months that have rows, but a
     * line chart with holes in the x-axis reads as missing data rather
     * than as "nothing happened". Rows outside the window are ignored;
     * a duplicated month is summed, which keeps the caller correct even
     * if an adapter groups differently than expected.
     *
     * @param from  first month of the series
     * @param to    last month of the series, inclusive
     * @param rows  sparse counts, typically straight from the adapter
     */
    public static List<MonthlyTicketCount> complete(YearMonth from, YearMonth to,
                                                    List<MonthlyTicketCount> rows) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to must not be null");
        }
        if (to.isBefore(from)) {
            // An inverted window is a caller bug, not an empty series.
            throw new IllegalArgumentException("to (" + to + ") is before from (" + from + ")");
        }

        Map<YearMonth, Long> totals = new HashMap<>();
        for (MonthlyTicketCount row : rows) {
            if (row.month().isBefore(from) || row.month().isAfter(to)) {
                continue;
            }
            totals.merge(row.month(), row.count(), Long::sum);
        }

        List<MonthlyTicketCount> series = new ArrayList<>();
        for (YearMonth cursor = from; !cursor.isAfter(to); cursor = cursor.plusMonths(1)) {
            series.add(new MonthlyTicketCount(cursor, totals.getOrDefault(cursor, 0L)));
        }
        return List.copyOf(series);
    }
}