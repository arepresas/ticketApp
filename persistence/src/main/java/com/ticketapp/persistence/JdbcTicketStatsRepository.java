package com.ticketapp.persistence;

import com.ticketapp.domain.CategorySpend;
import com.ticketapp.domain.MonthlyTicketCount;
import com.ticketapp.domain.SpendCategory;
import com.ticketapp.domain.TicketStats;
import com.ticketapp.domain.TicketStatsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;

/**
 * JDBC implementation of {@link TicketStatsRepository} — the dashboard's
 * reporting aggregates. Plain SQL, no ORM.
 *
 * <h2>Status policy</h2>
 * {@code DELETED} and {@code CANCELLED} are excluded everywhere.
 * {@code DELETED} is the soft delete and {@code CANCELLED} is the
 * user dismissing the ticket; both are terminal, and counting them
 * would put the KPI cards at odds with the tickets table rendered
 * directly underneath them. This matches
 * {@code JdbcTicketRepository.findSummariesByStatusIn}, which already
 * filters {@code DELETED} for that list.
 *
 * <p>Money aggregates additionally filter {@code e.currency = :currency}.
 * {@code currency} is a per-extraction {@code CHAR(3)}, so summing
 * across currencies would produce a number that means nothing. Rows in
 * other currencies are simply not counted; the caller decides which
 * currency to ask for and renders the symbol accordingly.
 *
 * <p>{@code ticket_extractions} is keyed on {@code ticket_id} (1:1), so
 * the {@code LEFT JOIN} never multiplies ticket rows and the
 * conditional aggregates below stay correct.
 */
@Repository
public class JdbcTicketStatsRepository implements TicketStatsRepository {

    /**
     * Statuses that still need attention. Mirrors
     * {@code GET /api/tickets/pending} so the "open" KPI and the pending
     * list can never disagree.
     */
    private static final String OPEN_STATUSES =
            "('OPEN', 'IN_ANALYSIS', 'IN_PROGRESS', 'ON_ERROR')";

    /**
     * Ticket counts and money totals in one round trip. The
     * {@code FILTER} clauses keep each aggregate on its own status
     * predicate instead of needing three separate scans.
     */
    private static final String STATS_SQL = """
            SELECT
                count(*) FILTER (WHERE t.status <> 'CANCELLED')::bigint
                    AS total_tickets,
                count(*) FILTER (WHERE t.status IN %s)::bigint
                    AS open_tickets,
                count(e.ticket_id) FILTER (
                    WHERE e.currency = :currency AND t.status <> 'CANCELLED')::bigint
                    AS extracted_tickets,
                coalesce(sum(e.total_amount) FILTER (
                    WHERE e.currency = :currency AND t.status <> 'CANCELLED'), 0)
                    AS total_spent,
                coalesce(round(avg(e.total_amount) FILTER (
                    WHERE e.currency = :currency AND t.status <> 'CANCELLED'), 2), 0)
                    AS avg_ticket
            FROM tickets t
            LEFT JOIN ticket_extractions e ON e.ticket_id = t.id
            WHERE t.owner_id = :owner
              AND t.status <> 'DELETED'
            """.formatted(OPEN_STATUSES);

    /**
     * Grouped on {@code purchase_date}, not {@code created_at}: a
     * backlog of receipts uploaded together must not collapse into a
     * single bar. Only extracted tickets appear — a ticket with no
     * extraction has no purchase date to report.
     *
     * <p>The window is half-open ({@code >= from} and {@code < toExclusive})
     * so it stays index-friendly on {@code purchase_date}.
     */
    private static final String COUNT_BY_MONTH_SQL = """
            SELECT to_char(e.purchase_date, 'YYYY-MM') AS month,
                   count(*)::bigint AS ticket_count
            FROM tickets t
            JOIN ticket_extractions e ON e.ticket_id = t.id
            WHERE t.owner_id = :owner
              AND t.status NOT IN ('DELETED', 'CANCELLED')
              AND e.purchase_date >= :from
              AND e.purchase_date < :to_exclusive
            GROUP BY to_char(e.purchase_date, 'YYYY-MM')
            ORDER BY 1
            """;

    /**
     * Grouped on the raw {@code category} column and folded onto the
     * {@link SpendCategory} whitelist in {@link #mapCategorySpend}, not
     * in SQL: the mapping is domain policy and {@code CASE} in the query
     * would hide it. A {@code NULL} or unrecognised label lands in
     * {@link SpendCategory#OTHER}.
     */
    /**
     * Groups on the <em>canonicalised</em> category, not the raw column.
     *
 * <p>{@code e.category} is free text emitted by a model, so grouping by
 * it directly yields one row per distinct label: "groceries",
 * "supermarket" and {@code NULL} would each become their own
 * {@code OTHER} row. A consumer keying a map by category would then
 * either draw three overlapping slices or overwrite two of them and
 * understate the spend, and the row count would be bounded by whatever
 * the model happened to emit rather than by the domain whitelist.
 *
 * <p>Mapping the known labels with {@link #CATEGORY_CASE} and everything
 * else to {@code other} collapses that to at most one row per
 * {@link SpendCategory} before aggregation. The whitelist is still owned
 * by the domain — the enum's wire names are read into the CASE at
 * construction rather than duplicated as string literals, so adding a
 * category to the enum is enough.
 */
    private static final String CATEGORY_CASE = buildCategoryCase();

    private static final String SUM_BY_CATEGORY_SQL = """
            SELECT %s AS category,
                   sum(e.total_amount) AS amount
            FROM tickets t
            JOIN ticket_extractions e ON e.ticket_id = t.id
            WHERE t.owner_id = :owner
              AND t.status NOT IN ('DELETED', 'CANCELLED')
              AND e.currency = :currency
            GROUP BY %s
            """.formatted(CATEGORY_CASE, CATEGORY_CASE);

    /**
     * Derives the SQL CASE from {@link SpendCategory}'s own wire names,
     * so the whitelist has exactly one definition. {@code NULL} and
     * anything unrecognised fall through to {@code other} — an
     * unclassifiable receipt is still money the user spent, and dropping
     * it would make this total disagree with the KPI total.
     */
    private static String buildCategoryCase() {
        StringBuilder branches = new StringBuilder("CASE");
        for (SpendCategory category : SpendCategory.values()) {
            if (category == SpendCategory.OTHER) {
                continue;
            }
            branches.append(" WHEN lower(btrim(e.category)) = '")
                    .append(category.wireName())
                    .append("' THEN '")
                    .append(category.wireName())
                    .append('\'');
        }
        return branches.append(" ELSE 'other' END").toString();
    }

    /**
     * Named-parameter key for the owner scope. Every aggregate here is
     * filtered by it, so it is stated once rather than repeated inline
     * (java:S1192) — and, more importantly, a typo in one of the three
     * would silently turn an owner-scoped read into a global one.
     */
    private static final String PARAM_OWNER = "owner";

    private final NamedParameterJdbcTemplate namedJdbc;

    public JdbcTicketStatsRepository(JdbcTemplate jdbc) {
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public TicketStats loadStats(long ownerId, String currency) {
        String code = normaliseCurrency(currency);
        var params = new MapSqlParameterSource()
                .addValue(PARAM_OWNER, ownerId)
                .addValue("currency", code);

        // The currency is an input, not a column on the row: it comes
        // back on the record so the dashboard renders the symbol it was
        // actually summed in.
        return namedJdbc.queryForObject(STATS_SQL, params, (rs, rowNum) -> mapStats(rs, code));
    }

    @Override
    public List<MonthlyTicketCount> countByMonth(long ownerId, YearMonth from, YearMonth to) {
        var params = new MapSqlParameterSource()
                .addValue(PARAM_OWNER, ownerId)
                // First day of `from`, and first day of the month after
                // `to`, so `to` itself stays inside the window.
                .addValue("from", LocalDate.of(from.getYear(), from.getMonthValue(), 1))
                .addValue("to_exclusive", LocalDate.of(to.plusMonths(1).getYear(),
                        to.plusMonths(1).getMonthValue(), 1));

        return namedJdbc.query(COUNT_BY_MONTH_SQL, params, JdbcTicketStatsRepository::mapMonth);
    }

    @Override
    public List<CategorySpend> sumByCategory(long ownerId, String currency) {
        var params = new MapSqlParameterSource()
                .addValue(PARAM_OWNER, ownerId)
                .addValue("currency", normaliseCurrency(currency));

        return namedJdbc.query(SUM_BY_CATEGORY_SQL, params, JdbcTicketStatsRepository::mapCategorySpend);
    }

    /**
     * {@code CHAR(3)} is blank-padded by Postgres, and a caller passing
     * {@code "eur"} would simply match nothing. Normalising once here
     * keeps every query comparing like with like.
     */
    private static String normaliseCurrency(String currency) {
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    private static TicketStats mapStats(ResultSet rs, String currency) throws SQLException {
        return new TicketStats(
                rs.getLong("total_tickets"),
                rs.getLong("open_tickets"),
                rs.getLong("extracted_tickets"),
                rs.getBigDecimal("total_spent"),
                rs.getBigDecimal("avg_ticket"),
                currency);
    }

    private static MonthlyTicketCount mapMonth(ResultSet rs, int rowNum) throws SQLException {
        return new MonthlyTicketCount(YearMonth.parse(rs.getString("month")), rs.getLong("ticket_count"));
    }

    private static CategorySpend mapCategorySpend(ResultSet rs, int rowNum) throws SQLException {
        return new CategorySpend(
                SpendCategory.fromExtractionValue(rs.getString("category")),
                rs.getBigDecimal("amount"));
    }
}