package com.ticketapp.persistence;

import com.ticketapp.domain.Shop;
import com.ticketapp.domain.ShopRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link ShopRepository}. Plain SQL, no ORM.
 * The catalogue is upsert-driven: re-minting the same shop name
 * keeps the same {@code id} so future price/line rows reference the
 * same merchant.
 *
 * <p>The UPSERT only writes contact-info fields on insert; on conflict
 * (re-mint of an existing normalised name) the existing contact info
 * is preserved. Contact fields are populated by either:
 * <ul>
 *   <li>the normaliser (read from the extraction payload on the first
 *       ticket that mentions the merchant), or</li>
 *   <li>a manual {@code PATCH /api/shops/{id}} from the dashboard.</li>
 * </ul>
 * A second ticket that doesn't print the address shouldn't blank out
 * the contact info the first ticket populated — hence the
 * {@code COALESCE(EXCLUDED.<col>, shops.<col>)} pattern in the
 * upsert.
 */
@Repository
public class JdbcShopRepository implements ShopRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public JdbcShopRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    private static final String SHOP_COLS =
            "id, name, normalised_name, address_line, postal_code, "
          + "city, country, phone, tax_id, website, created_at";

    /**
     * ON CONFLICT on the {@code uq_shops_normalised_name} index.
     * {@code name} is refreshed (LLM may correct an OCR misread on a
     * later ticket), but every contact field is preserved via
     * {@code COALESCE(EXCLUDED.<col>, shops.<col>)} — a sparse
     * extraction must not blank out the address a richer one wrote.
     */
    private static final String UPSERT_SQL = """
            INSERT INTO shops
                (id, name, normalised_name,
                 address_line, postal_code, city, country, phone, tax_id, website,
                 created_at)
            VALUES (?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?,
                    ?)
            ON CONFLICT (normalised_name) DO UPDATE
            SET name = EXCLUDED.name,
                address_line = COALESCE(EXCLUDED.address_line, shops.address_line),
                postal_code  = COALESCE(EXCLUDED.postal_code,  shops.postal_code),
                city         = COALESCE(EXCLUDED.city,         shops.city),
                country      = COALESCE(EXCLUDED.country,      shops.country),
                phone        = COALESCE(EXCLUDED.phone,        shops.phone),
                tax_id       = COALESCE(EXCLUDED.tax_id,       shops.tax_id),
                website      = COALESCE(EXCLUDED.website,      shops.website)
            """;

    private static final String FIND_BY_NAME_SQL =
            "SELECT " + SHOP_COLS + " FROM shops WHERE normalised_name = :name";

    private static final String FIND_BY_ID_SQL =
            "SELECT " + SHOP_COLS + " FROM shops WHERE id = :id";

    @Override
    public Optional<Shop> findByNormalisedName(String normalisedName) {
        var rows = namedJdbc.query(
                FIND_BY_NAME_SQL,
                new MapSqlParameterSource("name", normalisedName),
                (rs, n) -> mapShop(rs));
        return rows.stream().findFirst();
    }

    @Override
    public Optional<Shop> findById(UUID id) {
        var rows = namedJdbc.query(
                FIND_BY_ID_SQL,
                new MapSqlParameterSource("id", id),
                (rs, n) -> mapShop(rs));
        return rows.stream().findFirst();
    }

    @Override
    public Shop save(Shop shop) {
        jdbc.update(con -> {
            var ps = con.prepareStatement(UPSERT_SQL);
            ps.setObject(1, shop.id());
            ps.setString(2, shop.name());
            ps.setString(3, shop.normalisedName());
            ps.setString(4, shop.addressLine());
            ps.setString(5, shop.postalCode());
            ps.setString(6, shop.city());
            ps.setString(7, shop.country());
            ps.setString(8, shop.phone());
            ps.setString(9, shop.taxId());
            ps.setString(10, shop.website());
            ps.setObject(11, java.sql.Timestamp.from(shop.createdAt()));
            return ps;
        });
        return shop;
    }

    private static Shop mapShop(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String name = rs.getString("name");
        String normalised = rs.getString("normalised_name");
        String addressLine = rs.getString("address_line");
        String postalCode  = rs.getString("postal_code");
        String city        = rs.getString("city");
        String country     = rs.getString("country");
        String phone       = rs.getString("phone");
        String taxId       = rs.getString("tax_id");
        String website     = rs.getString("website");
        var ts = rs.getTimestamp("created_at");
        var createdAt = ts != null ? ts.toInstant() : java.time.Instant.now();
        return new Shop(id, name, normalised,
                addressLine, postalCode, city, country, phone, taxId, website,
                createdAt);
    }
}