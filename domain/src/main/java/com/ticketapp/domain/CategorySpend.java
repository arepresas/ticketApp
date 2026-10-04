package com.ticketapp.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Spend attributable to one {@link SpendCategory} for a single owner,
 * in a single currency.
 *
 * <p>{@code amount} is never {@code null}: an adapter that cannot
 * produce a number is a bug, not a zero. Negative values are possible
 * in principle (a refund modelled as a negative line) and are left for
 * the adapter to defend against, because a refund legitimately reduces
 * the category total.
 */
public record CategorySpend(SpendCategory category, BigDecimal amount) {

    public CategorySpend {
        if (category == null) {
            throw new IllegalArgumentException("category must not be null");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
    }

    /**
     * Expand a sparse result into a full four-slice series, one arc per
     * {@link SpendCategory}, filling the categories that had no rows
     * with {@link BigDecimal#ZERO}.
     *
     * <p>{@code GROUP BY} only emits categories that occur, which would
     * make the donut's legend grow and shrink as data arrives. Pinning
     * the arc count keeps the palette and the legend stable. Rows for
     * the same category are summed rather than overwritten.
     */
    public static List<CategorySpend> complete(List<CategorySpend> rows) {
        Map<SpendCategory, BigDecimal> totals = new EnumMap<>(SpendCategory.class);
        for (SpendCategory category : SpendCategory.values()) {
            totals.put(category, BigDecimal.ZERO);
        }
        for (CategorySpend row : rows) {
            totals.merge(row.category(), row.amount(), BigDecimal::add);
        }
        List<CategorySpend> series = new ArrayList<>();
        for (SpendCategory category : SpendCategory.values()) {
            series.add(new CategorySpend(category, totals.get(category)));
        }
        return List.copyOf(series);
    }
}