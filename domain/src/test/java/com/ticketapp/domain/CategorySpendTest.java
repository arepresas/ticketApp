package com.ticketapp.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("CategorySpend")
class CategorySpendTest {

    @Test
    @DisplayName("rejects a null category")
    void rejectsNullCategory() {
        assertThrows(IllegalArgumentException.class,
                () -> new CategorySpend(null, BigDecimal.ONE));
    }

    @Test
    @DisplayName("rejects a null amount rather than defaulting to zero")
    void rejectsNullAmount() {
        // An adapter that cannot produce a number is a bug. Silently
        // turning it into 0 would understate the user's spending.
        assertThrows(IllegalArgumentException.class,
                () -> new CategorySpend(SpendCategory.FOOD, null));
    }

    @Test
    @DisplayName("rejects a negative amount")
    void rejectsNegativeAmount() {
        // A donut renderer cannot draw a negative slice, so the type
        // that feeds it refuses one at the boundary.
        assertThrows(IllegalArgumentException.class,
                () -> new CategorySpend(SpendCategory.FOOD, new BigDecimal("-1.00")));
    }

    @Test
    @DisplayName("accepts a zero amount")
    void acceptsZeroAmount() {
        assertEquals(BigDecimal.ZERO,
                new CategorySpend(SpendCategory.FOOD, BigDecimal.ZERO).amount());
    }

    @Test
    @DisplayName("complete preserves enum declaration order")
    void completeFollowsEnumOrder() {
        // The donut's colours are keyed by category, so the arc order
        // has to follow the enum rather than whatever order the
        // adapter's rows happened to arrive in.
        List<CategorySpend> complete = CategorySpend.complete(List.of(
                new CategorySpend(SpendCategory.OTHER, new BigDecimal("1.00")),
                new CategorySpend(SpendCategory.LODGING, new BigDecimal("2.00")),
                new CategorySpend(SpendCategory.FOOD, new BigDecimal("3.00")),
                new CategorySpend(SpendCategory.TRANSPORT, new BigDecimal("4.00"))));

        assertEquals(List.of(SpendCategory.TRANSPORT, SpendCategory.FOOD,
                        SpendCategory.LODGING, SpendCategory.OTHER),
                complete.stream().map(CategorySpend::category).toList());
    }

    @Test
    @DisplayName("complete emits one arc per category so the donut legend is stable")
    void completeEmitsEveryCategory() {
        List<CategorySpend> complete = CategorySpend.complete(List.of(
                new CategorySpend(SpendCategory.FOOD, new BigDecimal("10.00"))));

        assertEquals(SpendCategory.values().length, complete.size());
        assertEquals(new BigDecimal("10.00"), amountOf(complete, SpendCategory.FOOD));
        assertEquals(BigDecimal.ZERO, amountOf(complete, SpendCategory.TRANSPORT));
        assertEquals(BigDecimal.ZERO, amountOf(complete, SpendCategory.LODGING));
    }

    @Test
    @DisplayName("complete returns an all-zero series for empty input")
    void completeEmptyInputYieldsZeroSeries() {
        List<CategorySpend> complete = CategorySpend.complete(List.of());

        assertEquals(SpendCategory.values().length, complete.size());
        assertEquals(BigDecimal.ZERO, amountOf(complete, SpendCategory.FOOD));
    }

    @Test
    @DisplayName("complete sums duplicate rows for the same category")
    void completeSumsDuplicates() {
        List<CategorySpend> complete = CategorySpend.complete(List.of(
                new CategorySpend(SpendCategory.FOOD, new BigDecimal("10.00")),
                new CategorySpend(SpendCategory.FOOD, new BigDecimal("2.50"))));

        assertEquals(new BigDecimal("12.50"), amountOf(complete, SpendCategory.FOOD));
    }

    private static BigDecimal amountOf(List<CategorySpend> rows, SpendCategory category) {
        return rows.stream()
                .filter(r -> r.category() == category)
                .map(CategorySpend::amount)
                .findFirst()
                .orElseThrow();
    }
}