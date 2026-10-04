package com.ticketapp.persistence;

import com.ticketapp.domain.CategorySpend;
import com.ticketapp.domain.MonthlyTicketCount;
import com.ticketapp.domain.SpendCategory;
import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketStats;
import com.ticketapp.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.Month;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT for {@link JdbcTicketStatsRepository} — the dashboard aggregates.
 *
 * <p>Each test pins one policy the reporting layer makes on the user's
 * behalf, because all three are invisible in the UI and wrong in a way
 * that reads as plausible: which statuses count, which currency is
 * summed, and which axis groups the monthly series.
 *
 * <p>Runs against the shared {@code AbstractPostgresIntegrationTest}
 * Postgres container.
 */
class JdbcTicketStatsRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    JdbcTicketStatsRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    private long owner;
    private long otherOwner;

    /**
     * Fresh owner per test instead of a global wipe.
     *
     * <p>Every repository method is owner-scoped, so a unique owner is
     * all the isolation this suite needs. Deleting every ticket and
     * extraction on the way in was neither isolated nor atomic: it
     * reached into rows another test may have been using, and it fails
     * outright if any other table still references a ticket.
     */
    @BeforeEach
    void freshOwner() {
        String suffix = UUID.randomUUID().toString();
        owner = seedOwner(jdbc, "stats-" + suffix);
        otherOwner = seedOwner(jdbc, "stats-other-" + suffix);
    }

    /**
     * Scoped cleanup so the container does not accumulate rows across
     * the suite. Deletes only this test's owner, and only after the
     * assertions — never before.
     */
    @AfterEach
    void dropFixtures() {
        // Children first: ticket_extractions goes with the tickets via
        // ON DELETE CASCADE. The app_users rows are deleted too —
        // leaving them behind is how a "per test unique owner" fixture
        // quietly fills the shared container with orphans.
        jdbc.update("DELETE FROM tickets WHERE owner_id IN (?, ?)", owner, otherOwner);
        jdbc.update("DELETE FROM app_users WHERE id IN (?, ?)", owner, otherOwner);
    }

    @Nested
    @DisplayName("loadStats")
    class LoadStats {

        @Test
        @DisplayName("counts total and open excluding DELETED and CANCELLED")
        void countsTotalAndOpen() {
            seedTicket("a", Ticket.Status.DONE, withExtraction("10.00", "EUR", "food", date(2026, 1, 5)));
            seedTicket("b", Ticket.Status.OPEN);
            seedTicket("c", Ticket.Status.ON_ERROR);
            seedTicket("d", Ticket.Status.IN_PROGRESS);
            seedTicket("e", Ticket.Status.IN_ANALYSIS);
            seedTicket("f", Ticket.Status.CANCELLED, withExtraction("99.00", "EUR", "food", date(2026, 1, 6)));
            seedTicket("g", Ticket.Status.DELETED, withExtraction("77.00", "EUR", "food", date(2026, 1, 7)));

            TicketStats stats = repository.loadStats(owner, "EUR");

            // DONE, OPEN, ON_ERROR, IN_PROGRESS, IN_ANALYSIS — the two
            // terminal ones are excluded so the KPI matches the
            // tickets table. IN_ANALYSIS matters: countByMonth treats
            // it as non-terminal, so the KPI must agree.
            assertThat(stats.totalTickets()).isEqualTo(5);
            assertThat(stats.openTickets()).isEqualTo(4);
            // Money too: a query that filters the terminal statuses out
            // of the counts but not out of SUM/AVG would pass a
            // count-only assertion while reporting 186.00.
            assertThat(stats.extractedTicketsInCurrency()).isEqualTo(1);
            assertThat(stats.totalSpent()).isEqualByComparingTo("10.00");
            assertThat(stats.averageTicketValue()).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("sums only the requested currency")
        void sumsOnlyRequestedCurrency() {
            seedTicket("eur", Ticket.Status.DONE, withExtraction("10.00", "EUR", "food", date(2026, 1, 5)));
            seedTicket("gbp", Ticket.Status.DONE, withExtraction("20.00", "GBP", "food", date(2026, 1, 6)));

            TicketStats eur = repository.loadStats(owner, "EUR");

            // Adding 10.00 EUR to 20.00 GBP would be a meaningless
            // number, so the GBP row is simply not counted.
            assertThat(eur.totalSpent()).isEqualByComparingTo("10.00");
            assertThat(eur.extractedTicketsInCurrency()).isEqualTo(1);
            assertThat(eur.currency()).isEqualTo("EUR");
            // The average must respect the same filter. An AVG that
            // included the GBP row would read 15.00 while the sum still
            // correctly read 10.00.
            assertThat(eur.averageTicketValue()).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("normalises the currency argument to upper case")
        void normalisesCurrencyArgument() {
            seedTicket("a", Ticket.Status.DONE, withExtraction("10.00", "EUR", "food", date(2026, 1, 5)));

            assertThat(repository.loadStats(owner, "eur").totalSpent())
                    .isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("averages over extracted tickets, not over every ticket")
        void averagesOverExtractedTicketsOnly() {
            seedTicket("a", Ticket.Status.DONE, withExtraction("10.00", "EUR", "food", date(2026, 1, 5)));
            seedTicket("b", Ticket.Status.DONE, withExtraction("30.00", "EUR", "food", date(2026, 1, 6)));
            seedTicket("no-extraction", Ticket.Status.OPEN);

            TicketStats stats = repository.loadStats(owner, "EUR");

            // (10 + 30) / 2, not / 3 — a ticket the AI never read has no
            // amount to average in.
            assertThat(stats.averageTicketValue()).isEqualByComparingTo("20.00");
            assertThat(stats.totalTickets()).isEqualTo(3);
            assertThat(stats.extractedTicketsInCurrency()).isEqualTo(2);
        }

        @Test
        @DisplayName("returns zeros for an owner with no tickets")
        void returnsZerosForEmptyOwner() {
            TicketStats stats = repository.loadStats(owner, "EUR");

            assertThat(stats.totalTickets()).isZero();
            assertThat(stats.openTickets()).isZero();
            assertThat(stats.totalSpent()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(stats.averageTicketValue()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("never reports another owner's spending")
        void isOwnerScoped() {
            seedTicket("mine", Ticket.Status.DONE, withExtraction("10.00", "EUR", "food", date(2026, 1, 5)));
            seedTicketFor(otherOwner, "theirs", Ticket.Status.DONE,
                    withExtraction("999.00", "EUR", "food", date(2026, 1, 6)));

            TicketStats stats = repository.loadStats(owner, "EUR");

            assertThat(stats.totalTickets()).isEqualTo(1);
            assertThat(stats.totalSpent()).isEqualByComparingTo("10.00");
        }
    }

    @Nested
    @DisplayName("countByMonth")
    class CountByMonth {

        @Test
        @DisplayName("groups on purchase_date, not created_at")
        void groupsOnPurchaseDate() {
            // Both uploaded "now" but bought in different months — the
            // whole reason the monthly chart is not keyed on created_at.
            seedTicket("a", Ticket.Status.DONE, withExtraction("10.00", "EUR", "food", date(2026, 1, 5)));
            seedTicket("b", Ticket.Status.DONE, withExtraction("10.00", "EUR", "food", date(2026, 3, 9)));
            seedTicket("c", Ticket.Status.DONE, withExtraction("10.00", "EUR", "food", date(2026, 3, 20)));

            List<MonthlyTicketCount> rows = repository.countByMonth(
                    owner, YearMonth.of(2026, Month.JANUARY), YearMonth.of(2026, Month.MARCH));

            assertThat(rows).extracting(MonthlyTicketCount::month)
                    .containsExactly(YearMonth.of(2026, Month.JANUARY), YearMonth.of(2026, Month.MARCH));
            assertThat(rows).extracting(MonthlyTicketCount::count).containsExactly(1L, 2L);
        }

        @Test
        @DisplayName("omits months with no tickets so complete() can fill the gaps")
        void omitsEmptyMonths() {
            seedTicket("a", Ticket.Status.DONE, withExtraction("10.00", "EUR", "food", date(2026, 1, 5)));

            List<MonthlyTicketCount> rows = repository.countByMonth(
                    owner, YearMonth.of(2026, Month.JANUARY), YearMonth.of(2026, Month.JUNE));

            assertThat(rows).hasSize(1);
        }

        @Test
        @DisplayName("excludes tickets with no extraction — there is no purchase date to report")
        void excludesUnextractedTickets() {
            seedTicket("extracted", Ticket.Status.DONE, withExtraction("10.00", "EUR", "food", date(2026, 2, 1)));
            seedTicket("pending", Ticket.Status.OPEN);

            List<MonthlyTicketCount> rows = repository.countByMonth(
                    owner, YearMonth.of(2026, Month.FEBRUARY), YearMonth.of(2026, Month.FEBRUARY));

            assertThat(rows).extracting(MonthlyTicketCount::count).containsExactly(1L);
        }

        @Test
        @DisplayName("includes both ends of the window")
        void includesBothEndsOfWindow() {
            seedTicket("jan", Ticket.Status.DONE, withExtraction("10.00", "EUR", "food", date(2026, 1, 31)));
            seedTicket("jun", Ticket.Status.DONE, withExtraction("10.00", "EUR", "food", date(2026, 6, 1)));
            seedTicket("out", Ticket.Status.DONE, withExtraction("10.00", "EUR", "food", date(2026, 7, 1)));

            List<MonthlyTicketCount> rows = repository.countByMonth(
                    owner, YearMonth.of(2026, Month.JANUARY), YearMonth.of(2026, Month.JUNE));

            assertThat(rows).extracting(MonthlyTicketCount::month)
                    .containsExactly(YearMonth.of(2026, Month.JANUARY), YearMonth.of(2026, Month.JUNE));
        }

        @Test
        @DisplayName("is owner-scoped")
        void isOwnerScoped() {
            seedTicketFor(otherOwner, "theirs", Ticket.Status.DONE,
                    withExtraction("10.00", "EUR", "food", date(2026, 1, 5)));

            assertThat(repository.countByMonth(owner, YearMonth.of(2026, Month.JANUARY), YearMonth.of(2026, Month.JANUARY)))
                    .isEmpty();
        }

        @Test
        @DisplayName("counts every non-terminal status and excludes both terminal ones")
        void appliesStatusPolicy() {
            // One in-window extraction per status. Without this, a query
            // that quietly dropped OPEN tickets — or let CANCELLED /
            // DELETED through — would still pass every other test here
            // and render a wrong chart.
            seedTicket("done", Ticket.Status.DONE, withExtraction("10.00", "EUR", "food", date(2026, 3, 5)));
            seedTicket("open", Ticket.Status.OPEN, withExtraction("10.00", "EUR", "food", date(2026, 3, 6)));
            seedTicket("in-analysis", Ticket.Status.IN_ANALYSIS, withExtraction("10.00", "EUR", "food", date(2026, 3, 7)));
            seedTicket("in-progress", Ticket.Status.IN_PROGRESS, withExtraction("10.00", "EUR", "food", date(2026, 3, 8)));
            seedTicket("on-error", Ticket.Status.ON_ERROR, withExtraction("10.00", "EUR", "food", date(2026, 3, 9)));
            seedTicket("cancelled", Ticket.Status.CANCELLED, withExtraction("10.00", "EUR", "food", date(2026, 3, 10)));
            seedTicket("deleted", Ticket.Status.DELETED, withExtraction("10.00", "EUR", "food", date(2026, 3, 11)));

            List<MonthlyTicketCount> rows = repository.countByMonth(
                    owner, YearMonth.of(2026, Month.MARCH), YearMonth.of(2026, Month.MARCH));

            // Five non-terminal statuses counted; the two terminal ones
            // excluded, matching the total/open KPI policy.
            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row.month()).isEqualTo(YearMonth.of(2026, Month.MARCH));
                assertThat(row.count()).isEqualTo(5L);
            });
        }
    }

    @Nested
    @DisplayName("sumByCategory")
    class SumByCategory {

        @Test
        @DisplayName("groups spend per category")
        void groupsSpendPerCategory() {
            seedTicket("a", Ticket.Status.DONE, withExtraction("10.00", "EUR", "food", date(2026, 1, 5)));
            seedTicket("b", Ticket.Status.DONE, withExtraction("5.50", "EUR", "food", date(2026, 1, 6)));
            seedTicket("c", Ticket.Status.DONE, withExtraction("30.00", "EUR", "transport", date(2026, 1, 7)));

            List<CategorySpend> rows = repository.sumByCategory(owner, "EUR");

            assertThat(amountFor(rows, SpendCategory.FOOD)).isEqualByComparingTo("15.50");
            assertThat(amountFor(rows, SpendCategory.TRANSPORT)).isEqualByComparingTo("30.00");
            // Sparse by contract — LODGING had no rows, so it is absent
            // rather than zero. CategorySpend.complete() is what pins
            // the arc count for the donut; asserted separately below.
            assertThat(rows).extracting(CategorySpend::category)
                    .doesNotContain(SpendCategory.LODGING);
        }

        @Test
        @DisplayName("complete() turns the sparse result into one row per category")
        void completePinsEveryCategory() {
            seedTicket("a", Ticket.Status.DONE, withExtraction("10.00", "EUR", "food", date(2026, 1, 5)));

            List<CategorySpend> complete = CategorySpend.complete(repository.sumByCategory(owner, "EUR"));

            assertThat(complete).extracting(CategorySpend::category)
                    .containsExactly(SpendCategory.TRANSPORT, SpendCategory.FOOD,
                            SpendCategory.LODGING, SpendCategory.OTHER);
            assertThat(complete).filteredOn(r -> r.category() == SpendCategory.LODGING)
                    .singleElement()
                    .extracting(CategorySpend::amount)
                    .isEqualTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("isolates the requested currency in category totals")
        void isolatesCurrency() {
            // Every other sumByCategory fixture is EUR-only, so
            // dropping the currency predicate entirely would pass them.
            seedTicket("eur", Ticket.Status.DONE, withExtraction("10.00", "EUR", "food", date(2026, 1, 5)));
            seedTicket("gbp", Ticket.Status.DONE, withExtraction("999.00", "GBP", "food", date(2026, 1, 6)));

            assertThat(amountFor(repository.sumByCategory(owner, "EUR"), SpendCategory.FOOD))
                    .isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("aggregates every non-terminal status, not just DONE")
        void aggregatesAllNonTerminalStatuses() {
            // A query hard-coded to `status = 'DONE'` would pass every
            // other category fixture here.
            seedTicket("done", Ticket.Status.DONE, withExtraction("1.00", "EUR", "food", date(2026, 1, 1)));
            seedTicket("open", Ticket.Status.OPEN, withExtraction("2.00", "EUR", "food", date(2026, 1, 2)));
            seedTicket("in-analysis", Ticket.Status.IN_ANALYSIS, withExtraction("4.00", "EUR", "food", date(2026, 1, 3)));
            seedTicket("in-progress", Ticket.Status.IN_PROGRESS, withExtraction("8.00", "EUR", "food", date(2026, 1, 4)));
            seedTicket("on-error", Ticket.Status.ON_ERROR, withExtraction("16.00", "EUR", "food", date(2026, 1, 5)));
            seedTicket("cancelled", Ticket.Status.CANCELLED, withExtraction("32.00", "EUR", "food", date(2026, 1, 6)));
            seedTicket("deleted", Ticket.Status.DELETED, withExtraction("64.00", "EUR", "food", date(2026, 1, 7)));

            assertThat(amountFor(repository.sumByCategory(owner, "EUR"), SpendCategory.FOOD))
                    .isEqualByComparingTo("31.00");
        }

        @Test
        @DisplayName("folds an unrecognised category label into OTHER")
        void foldsUnknownCategoryIntoOther() {
            // The column is free text emitted by a model; the donut only
            // knows four arcs.
            seedTicket("a", Ticket.Status.DONE, withExtraction("12.00", "EUR", "groceries", date(2026, 1, 5)));

            List<CategorySpend> rows = repository.sumByCategory(owner, "EUR");

            assertThat(amountFor(rows, SpendCategory.OTHER)).isEqualByComparingTo("12.00");
        }

        @Test
        @DisplayName("folds a null category into OTHER rather than dropping the money")
        void foldsNullCategoryIntoOther() {
            seedTicket("a", Ticket.Status.DONE, withExtraction("8.00", "EUR", null, date(2026, 1, 5)));

            List<CategorySpend> rows = repository.sumByCategory(owner, "EUR");

            assertThat(amountFor(rows, SpendCategory.OTHER)).isEqualByComparingTo("8.00");
        }

        @Test
        @DisplayName("excludes cancelled and deleted tickets")
        void excludesTerminalTickets() {
            seedTicket("live", Ticket.Status.DONE, withExtraction("10.00", "EUR", "food", date(2026, 1, 5)));
            seedTicket("cancelled", Ticket.Status.CANCELLED, withExtraction("50.00", "EUR", "food", date(2026, 1, 6)));
            seedTicket("deleted", Ticket.Status.DELETED, withExtraction("70.00", "EUR", "food", date(2026, 1, 7)));

            List<CategorySpend> rows = repository.sumByCategory(owner, "EUR");

            assertThat(amountFor(rows, SpendCategory.FOOD)).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("is owner-scoped")
        void isOwnerScoped() {
            seedTicketFor(otherOwner, "theirs", Ticket.Status.DONE,
                    withExtraction("999.00", "EUR", "food", date(2026, 1, 5)));

            assertThat(repository.sumByCategory(owner, "EUR")).isEmpty();
        }

        @Test
        @DisplayName("collapses several unknown labels and NULL into one OTHER row")
        void collapsesUnknownLabelsIntoOneOtherRow() {
            // The raw column is free text, so grouping by it directly
            // would emit one OTHER row per distinct label — three
            // overlapping slices, or two silently overwritten.
            seedTicket("a", Ticket.Status.DONE, withExtraction("10.00", "EUR", "groceries", date(2026, 1, 5)));
            seedTicket("b", Ticket.Status.DONE, withExtraction("20.00", "EUR", "supermarket", date(2026, 1, 6)));
            seedTicket("c", Ticket.Status.DONE, withExtraction("30.00", "EUR", null, date(2026, 1, 7)));

            List<CategorySpend> rows = repository.sumByCategory(owner, "EUR");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).category()).isEqualTo(SpendCategory.OTHER);
            assertThat(rows.get(0).amount()).isEqualByComparingTo("60.00");
        }

        @Test
        @DisplayName("never returns more rows than there are categories")
        void resultCardinalityIsBoundedByTheWhitelist() {
            // Six distinct raw labels must collapse to at most four rows.
            seedTicket("a", Ticket.Status.DONE, withExtraction("1.00", "EUR", "food", date(2026, 1, 1)));
            seedTicket("b", Ticket.Status.DONE, withExtraction("1.00", "EUR", "transport", date(2026, 1, 2)));
            seedTicket("c", Ticket.Status.DONE, withExtraction("1.00", "EUR", "lodging", date(2026, 1, 3)));
            seedTicket("d", Ticket.Status.DONE, withExtraction("1.00", "EUR", "gas station", date(2026, 1, 4)));
            seedTicket("e", Ticket.Status.DONE, withExtraction("1.00", "EUR", "pharmacy", date(2026, 1, 5)));
            seedTicket("f", Ticket.Status.DONE, withExtraction("1.00", "EUR", null, date(2026, 1, 6)));

            assertThat(repository.sumByCategory(owner, "EUR"))
                    .hasSizeLessThanOrEqualTo(SpendCategory.values().length);
        }
    }

    // ------------------------------------------------------------------
    // Seed helpers
    // ------------------------------------------------------------------

    private record ExtractionSeed(String amount, String currency, String category, LocalDate purchaseDate) {
    }

    private static ExtractionSeed withExtraction(String amount, String currency,
                                                 String category, LocalDate purchaseDate) {
        return new ExtractionSeed(amount, currency, category, purchaseDate);
    }

    private static LocalDate date(int year, int month, int day) {
        return LocalDate.of(year, month, day);
    }

    private void seedTicket(String title, Ticket.Status status) {
        seedTicketFor(owner, title, status, null);
    }

    private void seedTicket(String title, Ticket.Status status, ExtractionSeed extraction) {
        seedTicketFor(owner, title, status, extraction);
    }

    private long seedTicketFor(long ownerId, String title, Ticket.Status status, ExtractionSeed extraction) {
        Long id = jdbc.queryForObject("""
                INSERT INTO tickets (owner_id, title, description, status, created_at, updated_at)
                VALUES (?, ?, '', ?, now(), now()) RETURNING id""",
                Long.class, ownerId, title, status.name());
        if (extraction != null) {
            jdbc.update("""
                    INSERT INTO ticket_extractions
                        (ticket_id, merchant, purchase_date, category, products,
                         total_amount, currency, model, extracted_at,
                         raw_response_text, extraction_payload)
                    VALUES (?, 'Test Merchant', ?, ?, '[]'::jsonb, ?, ?, 'test-model', now(),
                            NULL, '{}'::jsonb)""",
                    id,
                    extraction.purchaseDate(),
                    extraction.category(),
                    new BigDecimal(extraction.amount()),
                    extraction.currency());
        }
        return id;
    }

    private static BigDecimal amountFor(List<CategorySpend> rows, SpendCategory category) {
        return rows.stream()
                .filter(r -> r.category() == category)
                .map(CategorySpend::amount)
                .findFirst()
                .orElseThrow();
    }
}