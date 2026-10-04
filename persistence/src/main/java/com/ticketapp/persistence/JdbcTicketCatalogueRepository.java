package com.ticketapp.persistence;

import com.ticketapp.domain.Shop;
import com.ticketapp.domain.TicketCatalogueRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;


/**
 * JDBC adapter for {@link TicketCatalogueRepository}.
 *
 * <p>The soft-delete sink ({@code DELETED}) is filtered here rather
 * than by a pre-read in the controller: this port replaced a
 * controller that used to look the ticket up first, and a ticket the
 * caller must not see must not be distinguishable from a missing one
 * at any layer.
 *
 * <p>Three queries instead of the five the controller used to issue:
 * the shop (one row), then the lines with their product and price
 * master rows joined in. The owner predicate is applied to the
 * ticket, so the whole view is tenant-scoped in SQL rather than by
 * a check the caller has to remember.
 *
 * <p>The product and price joins are LEFT joins on purpose: a
 * catalogue row that was deleted after the ticket was validated
 * must not hide the whole line. The projection then reports a null
 * name or amount, which is what the dashboard renders as "unknown".
 */
@Repository
public class JdbcTicketCatalogueRepository implements TicketCatalogueRepository {

    private static final String SHOP_SQL =
            """
            SELECT s.id, s.name, s.normalised_name, s.address_line, s.postal_code,
                   s.city, s.country, s.phone, s.tax_id, s.website, s.created_at
            FROM tickets t
            JOIN shops s ON s.id = t.shop_id
            WHERE t.id = :ticket AND t.owner_id = :owner
              AND t.status <> 'DELETED'
            """;

    private static final String LINES_SQL =
            """
            SELECT p.name AS product_name, p.unit AS product_unit,
                   l.quantity, pr.amount AS price_per_unit, l.line_total
            FROM line_tickets l
            JOIN tickets t ON t.id = l.ticket_id
            LEFT JOIN products p ON p.id = l.product_id
            LEFT JOIN prices pr ON pr.id = l.price_id
            WHERE l.ticket_id = :ticket AND t.owner_id = :owner
              AND t.status <> 'DELETED'
            ORDER BY l.created_at ASC, l.id ASC
            """;

    private final NamedParameterJdbcTemplate namedJdbc;

    public JdbcTicketCatalogueRepository(JdbcTemplate jdbc) {
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public Optional<TicketCatalogue> findByTicketId(long ticketId, long ownerId) {
        Optional<Shop> shop = namedJdbc.query(
                        SHOP_SQL,
                        new MapSqlParameterSource()
                                .addValue("ticket", ticketId)
                                .addValue("owner", ownerId),
                        (rs, n) -> mapShop(rs))
                .stream()
                .findFirst();
        if (shop.isEmpty()) {
            // No ticket, wrong owner, or not normalised yet — the
            // port contract makes the three indistinguishable.
            return Optional.empty();
        }
        List<CatalogueLine> lines = namedJdbc.query(
                LINES_SQL,
                new MapSqlParameterSource()
                        .addValue("ticket", ticketId)
                        .addValue("owner", ownerId),
                (rs, n) -> new CatalogueLine(
                        rs.getString("product_name"),
                        rs.getString("product_unit"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("price_per_unit"),
                        rs.getBigDecimal("line_total")));
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TicketCatalogue(shop.get(), lines));
    }

    private static Shop mapShop(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Shop(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("normalised_name"),
                rs.getString("address_line"),
                rs.getString("postal_code"),
                rs.getString("city"),
                rs.getString("country"),
                rs.getString("phone"),
                rs.getString("tax_id"),
                rs.getString("website"),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant());
    }
}
