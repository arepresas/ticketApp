package com.ticketapp.persistence;

import com.ticketapp.domain.Product;
import com.ticketapp.domain.ProductRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link ProductRepository}. Plain SQL, no ORM.
 *
 * <p>Products are upserted on the {@code (normalised_name, COALESCE(unit, ''))}
 * UNIQUE index so two calls with the same match key collapse to one row
 * without losing the original {@code id}. NULL units collide with each
 * other through the {@code COALESCE} predicate; "kg" + NULL stay
 * distinct products.
 */
@Repository
public class JdbcProductRepository implements ProductRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public JdbcProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    private static final String PRODUCT_COLS =
            "id, name, normalised_name, unit, created_at";

    /**
     * Upsert by the match key. The COALESCE trick in the conflict
     * target mirrors the index definition so the database matches
     * the lookup key consistently.
     */
    private static final String UPSERT_SQL = """
            INSERT INTO products
                (id, name, normalised_name, unit, created_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (normalised_name, COALESCE(unit, '')) DO UPDATE
            SET name = EXCLUDED.name
            """;

    private static final String FIND_BY_NAME_SQL =
            "SELECT " + PRODUCT_COLS + " FROM products " +
            "WHERE normalised_name = :name " +
            "AND COALESCE(unit, '') = COALESCE(:unit, '')";

    private static final String FIND_BY_IDS_SQL =
            "SELECT " + PRODUCT_COLS + " FROM products WHERE id IN (:ids)";

    @Override
    public Optional<Product> findByNormalisedName(String normalisedName, String unit) {
        var params = new MapSqlParameterSource()
                .addValue("name", normalisedName)
                .addValue("unit", unit);
        var rows = namedJdbc.query(FIND_BY_NAME_SQL, params, (rs, n) -> mapProduct(rs));
        return rows.stream().findFirst();
    }

    @Override
    public java.util.Map<UUID, Product> findAllByIds(java.util.Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return java.util.Map.of();
        var rows = namedJdbc.query(
                FIND_BY_IDS_SQL,
                new MapSqlParameterSource("ids", ids),
                (rs, n) -> java.util.Map.entry(rs.getObject("id", UUID.class), mapProduct(rs)));
        // Map.entry isn't supported by collectors; convert here.
        var map = new java.util.HashMap<UUID, Product>();
        for (var row : rows) map.put(row.getKey(), row.getValue());
        return map;
    }

    @Override
    public Product save(Product product) {
        jdbc.update(con -> {
            var ps = con.prepareStatement(UPSERT_SQL);
            ps.setObject(1, product.id());
            ps.setString(2, product.name());
            ps.setString(3, product.normalisedName());
            if (product.unit() == null) ps.setNull(4, Types.VARCHAR);
            else ps.setString(4, product.unit());
            ps.setObject(5, java.sql.Timestamp.from(product.createdAt()));
            return ps;
        });
        return product;
    }

    private static Product mapProduct(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String name = rs.getString("name");
        String normalised = rs.getString("normalised_name");
        String unit = rs.getString("unit"); // nullable
        var ts = rs.getTimestamp("created_at");
        var createdAt = ts != null ? ts.toInstant() : java.time.Instant.now();
        return new Product(id, name, normalised, unit, createdAt);
    }
}
