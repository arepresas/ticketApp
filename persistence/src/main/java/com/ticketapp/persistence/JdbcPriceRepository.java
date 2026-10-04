package com.ticketapp.persistence;

import com.ticketapp.domain.Price;
import com.ticketapp.domain.PriceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.Optional;

/**
 * JDBC implementation of {@link PriceRepository}. Plain SQL, no ORM.
 *
 * <p>Prices are anchored to both a product and a ticket, so the
 * UNIQUE index {@code (product_id, ticket_id, amount)} naturally
 * drives the upsert via {@code ON CONFLICT}. The {@code amount}
 * participates in the conflict key on purpose: a loyalty-discount
 * line on the same ticket at a different amount lives in a
 * separate row, so analytics can see both.
 */
@Repository
public class JdbcPriceRepository implements PriceRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public JdbcPriceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    private static final String PRICE_COLS =
            "id, product_id, ticket_id, amount, created_at, updated_at";

    private static final String UPSERT_SQL = """
            INSERT INTO prices
                (product_id, ticket_id, amount, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (product_id, ticket_id, amount) DO UPDATE
            SET updated_at = EXCLUDED.updated_at
            RETURNING id
            """;

    private static final String FIND_SQL =
            "SELECT " + PRICE_COLS + " FROM prices " +
            "WHERE product_id = :product " +
            "AND ticket_id = :ticket " +
            "AND amount = :amount";

    private static final String FIND_BY_IDS_SQL =
            "SELECT " + PRICE_COLS + " FROM prices WHERE id IN (:ids)";

    @Override
    public Optional<Price> findByProductAndTicket(long productId, long ticketId, BigDecimal amount) {
        var params = new MapSqlParameterSource()
                .addValue("product", productId)
                .addValue("ticket", ticketId)
                .addValue("amount", amount);
        var rows = namedJdbc.query(FIND_SQL, params, (rs, n) -> mapPrice(rs));
        return rows.stream().findFirst();
    }

    @Override
    public java.util.Map<Long, Price> findAllByIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return java.util.Map.of();
        var rows = namedJdbc.query(
                FIND_BY_IDS_SQL,
                new MapSqlParameterSource("ids", ids),
                (rs, n) -> mapPrice(rs));
        var map = new java.util.HashMap<Long, Price>();
        for (var row : rows) map.put(row.id(), row);
        return map;
    }

    @Override
    public Price save(Price price) {
        return jdbc.query(
                con -> {
                    PreparedStatement ps = con.prepareStatement(UPSERT_SQL);
                ps.setLong(1, price.productId());
                ps.setLong(2, price.ticketId());
                ps.setBigDecimal(3, price.amount());
                var ts = java.sql.Timestamp.from(price.createdAt());
                ps.setTimestamp(4, ts);
                ps.setTimestamp(5, java.sql.Timestamp.from(price.updatedAt()));
                    return ps;
                },
                (rs, n) -> price.withId(rs.getLong(1))).stream().findFirst().orElse(price);
    }

    private static Price mapPrice(java.sql.ResultSet rs) throws java.sql.SQLException {
        long id = rs.getLong("id");
        long productId = rs.getLong("product_id");
        long ticketId = rs.getLong("ticket_id");
        BigDecimal amount = rs.getBigDecimal("amount");
        var createdAtOdt = rs.getObject("created_at", java.time.OffsetDateTime.class);
        var updatedAtOdt = rs.getObject("updated_at", java.time.OffsetDateTime.class);
        var createdAt = createdAtOdt != null ? createdAtOdt.toInstant() : java.time.Instant.now();
        var updatedAt = updatedAtOdt != null ? updatedAtOdt.toInstant() : createdAt;
        return new Price(id, productId, ticketId, amount, createdAt, updatedAt);
    }
}
