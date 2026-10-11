package com.ticketapp.persistence;

import com.ticketapp.domain.Product;
import com.ticketapp.domain.ProductRepository;
import com.ticketapp.domain.QuantityUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;


/**
 * JDBC implementation of {@link ProductRepository}. Plain SQL, no ORM.
 *
 * <p>Products are upserted on the {@code (normalised_name, COALESCE(unit, ''))}
 * UNIQUE index so two calls with the same match key collapse to one row
 * without losing the original {@code id}. The unit half of that key is a
 * {@link QuantityUnit} and the column is {@code NOT NULL} (V23), so the
 * COALESCE in the index is now an identity function kept only so the
 * index and the queries it serves keep the same expression — rewriting
 * the index in the same release would be a DROP, which database.md
 * forbids alongside a structural change.
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
                (name, normalised_name, unit, created_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (normalised_name, COALESCE(unit, '')) DO UPDATE
            SET name = EXCLUDED.name
            RETURNING id
            """;

    /**
     * Shared {@code SELECT} head. Every read path below needs the same
     * projection and table, so the prefix is stated once and the
     * per-query {@code WHERE}/{@code ORDER BY} clauses are appended —
     * changing the projection then cannot leave one query behind.
     */
    private static final String SELECT_PRODUCTS =
            "SELECT " + PRODUCT_COLS + " FROM products ";

    private static final String FIND_BY_NAME_SQL =
            SELECT_PRODUCTS +
            "WHERE normalised_name = :name " +
            "AND COALESCE(unit, '') = COALESCE(:unit, '')";

    private static final String FIND_BY_IDS_SQL =
            SELECT_PRODUCTS + "WHERE id IN (:ids)";

    /**
     * Prefix lookup against {@code normalised_name}. Uses {@code ILIKE}
     * to keep it case-insensitive regardless of the column collation
     * (the {@code products} table doesn't force a particular
     * collation, and the database may decide one). Limit + order by
     * name keep results stable for the SPA's autocomplete.
     */
    private static final String SEARCH_BY_NAME_SQL =
            SELECT_PRODUCTS +
            "WHERE normalised_name ILIKE :prefix ESCAPE '\\' " +
            "ORDER BY normalised_name ASC " +
            "LIMIT :limit";

    @Override
    public Optional<Product> findByNormalisedName(String normalisedName, QuantityUnit unit) {
        var params = new MapSqlParameterSource()
                .addValue("name", normalisedName)
                .addValue("unit", unit.storageValue());
        var rows = namedJdbc.query(FIND_BY_NAME_SQL, params, (rs, n) -> mapProduct(rs));
        return rows.stream().findFirst();
    }

    @Override
    public java.util.Map<Long, Product> findAllByIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return java.util.Map.of();
        var rows = namedJdbc.query(
                FIND_BY_IDS_SQL,
                new MapSqlParameterSource("ids", ids),
                (rs, n) -> java.util.Map.entry(rs.getLong("id"), mapProduct(rs)));
        // Map.entry isn't supported by collectors; convert here.
        var map = new java.util.HashMap<Long, Product>();
        for (var row : rows) map.put(row.getKey(), row.getValue());
        return map;
    }

    @Override
    public List<Product> searchByNormalisedName(String prefix, int limit) {
        if (prefix == null || prefix.isBlank() || limit <= 0) {
            return List.of();
        }
        // ILIKE pattern: escape any literal '%' / '_' the user may
        // have typed so they don't act as wildcards against the
        // catalogue. The escape character is the backslash — none
        // of the catalogue names currently contain one, but a
        // future shop in another locale might.
        String escaped = prefix.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        var params = new MapSqlParameterSource()
                .addValue("prefix", escaped + "%")
                .addValue("limit", limit);
        return namedJdbc.query(SEARCH_BY_NAME_SQL, params, (rs, n) -> mapProduct(rs));
    }

    @Override
    public Product save(Product product) {
        return jdbc.query(
                con -> {
                    PreparedStatement ps = con.prepareStatement(UPSERT_SQL);
                ps.setString(1, product.name());
                ps.setString(2, product.normalisedName());
                // Never NULL: "no unit" is QuantityUnit.UNIT, stored
                // under its own name. The column is NOT NULL (V23).
                ps.setString(3, product.unit().storageValue());
                ps.setObject(4, java.sql.Timestamp.from(product.createdAt()));
                    return ps;
                },
                (rs, n) -> product.withId(rs.getLong(1))).stream().findFirst().orElse(product);
    }

    /**
     * Reads a row written before {@link QuantityUnit} existed: the
     * column may still hold NULL or a label like {@code "L"} in a
     * database whose backfill has not run, and neither may fail a read.
     */
    private static Product mapProduct(java.sql.ResultSet rs) throws java.sql.SQLException {
        long id = rs.getLong("id");
        String name = rs.getString("name");
        String normalised = rs.getString("normalised_name");
        QuantityUnit unit = QuantityUnit.fromStorage(rs.getString("unit"));
        var odt = rs.getObject("created_at", java.time.OffsetDateTime.class);
        var createdAt = odt != null ? odt.toInstant() : java.time.Instant.now();
        return new Product(id, name, normalised, unit, createdAt);
    }
}
