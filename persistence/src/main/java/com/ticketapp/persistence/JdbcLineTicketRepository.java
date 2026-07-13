package com.ticketapp.persistence;

import com.ticketapp.domain.LineTicket;
import com.ticketapp.domain.LineTicketRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link LineTicketRepository}. Plain SQL, no ORM.
 *
 * <p>One row per {@code (ticket_id, product_id)}. Re-validating the
 * same ticket with the same product upserts the existing row in
 * place via ON CONFLICT — the previous {@code price_id} reference
 * is overwritten if the latest extraction points at a different
 * snapshot row (e.g. user re-saved the extraction with edited
 * quantities).
 */
@Repository
public class JdbcLineTicketRepository implements LineTicketRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public JdbcLineTicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    private static final String LINE_COLS =
            "id, ticket_id, shop_id, product_id, price_id, " +
            "quantity, line_total, created_at, updated_at";

    private static final String UPSERT_SQL = """
            INSERT INTO line_tickets
                (id, ticket_id, shop_id, product_id, price_id,
                 quantity, line_total, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (ticket_id, product_id) DO UPDATE
            SET shop_id = EXCLUDED.shop_id,
                price_id = EXCLUDED.price_id,
                quantity = EXCLUDED.quantity,
                line_total = EXCLUDED.line_total,
                updated_at = EXCLUDED.updated_at
            """;

    private static final String FIND_SQL =
            "SELECT " + LINE_COLS + " FROM line_tickets " +
            "WHERE ticket_id = :ticket AND product_id = :product";

    @Override
    public Optional<LineTicket> findByTicketAndProduct(UUID ticketId, UUID productId) {
        var params = new MapSqlParameterSource()
                .addValue("ticket", ticketId)
                .addValue("product", productId);
        var rows = namedJdbc.query(FIND_SQL, params, (rs, n) -> mapLine(rs));
        return rows.stream().findFirst();
    }

    /**
     * All lines for one ticket, ordered by {@code created_at} so the
     * catalogue read view preserves the order the user saw on the
     * line items while editing. Uses the index on {@code ticket_id}
     * for the index scan and a sort on the already-indexed column.
     */
    private static final String FIND_BY_TICKET_SQL =
            "SELECT " + LINE_COLS + " FROM line_tickets" +
            " WHERE ticket_id = :ticket ORDER BY created_at ASC, id ASC";

    @Override
    public List<LineTicket> findByTicketId(UUID ticketId) {
        return namedJdbc.query(
                FIND_BY_TICKET_SQL,
                new MapSqlParameterSource("ticket", ticketId),
                (rs, _) -> mapLine(rs));
    }

    @Override
    public LineTicket save(LineTicket line) {
        jdbc.update(con -> {
            var ps = con.prepareStatement(UPSERT_SQL);
            ps.setObject(1, line.id());
            ps.setObject(2, line.ticketId());
            ps.setObject(3, line.shopId());
            ps.setObject(4, line.productId());
            ps.setObject(5, line.priceId());
            ps.setBigDecimal(6, line.quantity());
            ps.setBigDecimal(7, line.lineTotal());
            ps.setTimestamp(8, java.sql.Timestamp.from(line.createdAt()));
            ps.setTimestamp(9, java.sql.Timestamp.from(line.updatedAt()));
            return ps;
        });
        return line;
    }

    private static LineTicket mapLine(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID ticketId = rs.getObject("ticket_id", UUID.class);
        UUID shopId = rs.getObject("shop_id", UUID.class);
        UUID productId = rs.getObject("product_id", UUID.class);
        UUID priceId = rs.getObject("price_id", UUID.class);
        var quantity = rs.getBigDecimal("quantity");
        var lineTotal = rs.getBigDecimal("line_total");
        var createdAtTs = rs.getTimestamp("created_at");
        var updatedAtTs = rs.getTimestamp("updated_at");
        var createdAt = createdAtTs != null ? createdAtTs.toInstant() : java.time.Instant.now();
        var updatedAt = updatedAtTs != null ? updatedAtTs.toInstant() : createdAt;
        return new LineTicket(id, ticketId, shopId, productId, priceId,
                quantity, lineTotal, createdAt, updatedAt);
    }
}
