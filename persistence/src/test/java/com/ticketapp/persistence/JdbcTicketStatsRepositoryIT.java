package com.ticketapp.persistence;

import com.ticketapp.domain.CategorySpend;
import com.ticketapp.domain.MonthlyTicketCount;
import com.ticketapp.domain.SpendCategory;
import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketStats;
import com.ticketapp.support.AbstractPostgresIntegrationTest;
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

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM ticket_extractions");
        jdbc.update("DELETE FROM tickets");
        owner = seedOwner(jdbc, "stats-owner");
        otherOwner = seedOwner(jdbc, "stats-other-owner");
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
            seedTicket("e", Ticket.Status.CANCELLED, withExtraction("99.00", "EUR", "food", date(2026, 1, 6)));
            seedTicket("f", Ticket.Status.DELETED, withExtraction("77.00", "EUR", "food", date(2026, 1, 7)));

            TicketStats stats = repository.loadStats(owner, "EUR");

            // DONE, OPEN, ON_ERROR, IN_PROGRESS — the two terminal
            // ones are excluded so the KPI matches the tickets table.
            assertThat(stats.totalTickets()).isEqualTo(4);
            // Everything not terminal, matching GET /api/tickets/pending.
            assertThat(stats.openTickets()).isEqualTo(3);
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
            assertThat(eur.extractedTickets()).isEqualTo(1);
            assertThat(eur.currency()).isEqualTo("EUR");
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
            assertThat(stats.extractedTickets()).isEqualTo(2);
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