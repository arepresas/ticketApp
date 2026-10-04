package com.ticketapp.domain;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Spend attributable to one {@link SpendCategory} for a single owner,
 * in a single currency.
 *
 * <p>{@code amount} is never {@code null}: an adapter that cannot
 * produce a number is a bug, not a zero.
 *
 * <p>{@code amount} is also never negative in practice, because
 * {@code ticket_extractions} carries
 * {@code CHECK (total_amount >= 0)} — so a category total is a sum of
 * non-negative lines. That is why {@link #complete} hands its result
 * straight to a donut chart: pie and doughnut renderers cannot
 * represent a negative slice, and there is no reachable input that
 * would ask them to. A future source of negative amounts (a refund
 * ledger, say) would have to make that decision here rather than
 * discover it in the renderer.
 */
public record CategorySpend(SpendCategory category, BigDecimal amount) {

    public CategorySpend {
        if (category == null) {
            throw new IllegalArgumentException("category must not be null");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
        if (amount.signum() < 0) {
            // Enforced here rather than trusted to the schema, because
            // this is the type that feeds the donut and pie/doughnut
            // renderers cannot represent a negative slice. The
            // `ck_ticket_extractions_total_nonneg` constraint makes it
            // unreachable today; refusing here means a future source of
            // signed amounts fails at the boundary instead of rendering
            // as a broken chart.
            throw new IllegalArgumentException("amount must not be negative: " + amount);
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
        // Streamed in declaration order rather than collected from a
        // loop, so the arc order still follows the enum and the donut's
        // colours stay pinned to the same category.
        return Arrays.stream(SpendCategory.values())
                .map(category -> new CategorySpend(category, totals.get(category)))
                .toList();
    }
}