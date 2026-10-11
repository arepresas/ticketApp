package com.ticketapp.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketapp.domain.SpendCategory;
import com.ticketapp.domain.TicketExtraction;
import com.ticketapp.domain.TicketExtraction.ProductLine;
import com.ticketapp.domain.TicketExtractionRepository;
import com.ticketapp.domain.exceptions.ResourceNotFoundException;
import com.ticketapp.persistence.ExtractionRowMapper.JsonbSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;


/**
 * JDBC implementation of {@link TicketExtractionRepository}. Plain SQL,
 * no ORM.
 *
 * <p>{@code products} and {@code extraction_payload} are JSONB
 * columns carrying open-ended, structured data we own;
 * {@code raw_response_text} is TEXT carrying the provider's
 * verbatim reply (the legacy {@code raw_response} JSONB column was
 * dropped in V18 — see ADR 0006 §D8).
 * {@code extraction_payload} carries the parsed canonical object
 * (added in V7) so downstream queries can access discounts,
 * pricePerKg, and full merchant/transaction data without
 * re-parsing {@code raw_response_text}.
 */
@Repository
public class JdbcTicketExtractionRepository implements TicketExtractionRepository {

    private static final String SELECT_COLS =
            "ticket_id, merchant, purchase_date, category, products, total_amount, " +
            "currency, model, extracted_at, raw_response_text, extraction_payload";

    private static final String INSERT_SQL = """
            INSERT INTO ticket_extractions
                (ticket_id, merchant, purchase_date, category, products,
                 total_amount, currency, model, extracted_at,
                 raw_response_text, extraction_payload)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (ticket_id) DO NOTHING
            """;

    /**
     * Update path for user-driven edits through the detail screen.
     * Touches only the mutable columns — {@code model},
     * {@code extracted_at}, {@code raw_response_text}, and
     * {@code extraction_payload} keep the AI's audit
     * ("extracted by gpt-4o-mini on …") so the dashboard's audit
     * trail stays truthful after the user corrects a line item.
     */
    private static final String UPDATE_SQL = """
            UPDATE ticket_extractions SET
                merchant = ?, purchase_date = ?, category = ?,
                products = ?::jsonb, total_amount = ?, currency = ?
            WHERE ticket_id = ?
            """;

    /**
     * Column-scoped update for the atomic detail-screen edit: same six
     * columns as {@link #UPDATE_SQL}, but the ownership predicate is
     * joined into the statement so the mutation refuses cross-tenant
     * writes on its own (no pre-check to forget) and the audit columns
     * are never read into memory just to be written back unchanged.
     */
    private static final String UPDATE_EDITABLE_SQL = """
            UPDATE ticket_extractions e SET
                merchant = ?, purchase_date = ?, category = ?,
                products = ?::jsonb, total_amount = ?, currency = ?
            WHERE e.ticket_id = ?
              AND EXISTS (SELECT 1 FROM tickets t
                          WHERE t.id = e.ticket_id AND t.owner_id = ?)
            """;

    private final JdbcTemplate jdbc;
    private final ExtractionRowMapper mapper;

    public JdbcTicketExtractionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.mapper = new ExtractionRowMapper(objectMapper);
    }

    @Override
    public Optional<TicketExtraction> findByTicketId(long ticketId, long ownerId) {
        List<TicketExtraction> rows = jdbc.query(
                "SELECT " + SELECT_COLS + " FROM ticket_extractions e"
                        + " JOIN tickets t ON t.id = e.ticket_id"
                        + " WHERE e.ticket_id = ? AND t.owner_id = ?",
                (rs, n) -> mapper.mapRow(rs),
                ticketId, ownerId);
        return rows.stream().findFirst();
    }

    @Override
    public TicketExtraction save(TicketExtraction extraction) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(INSERT_SQL);
            bindInsert(ps, extraction);
            return ps;
        });
        return extraction;
    }

    @Override
    public TicketExtraction replace(TicketExtraction extraction) {
        // Caller is expected to load the existing row first (the
        // detail screen does); on the empty-row path an UPDATE
        // quietly updates 0 rows. We surface that as a 404 at the
        // BFF layer rather than turning it into a silent insert,
        // so the operator can spot "edit before AI finished" cases
        // instead of overwriting the genuine extraction flow.
        int rows = jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(UPDATE_SQL);
            bindEditableFields(ps, 1, extraction.merchant(),
                    extraction.purchaseDate(), extraction.category(),
                    extraction.products(), extraction.totalAmount(),
                    extraction.currency());
            ps.setLong(7, extraction.ticketId());
            return ps;
        });
        if (rows == 0) {
            throw new ResourceNotFoundException("extraction for ticket",
                    extraction.ticketId());
        }
        return extraction;
    }

    @Override
    public boolean updateEditableFields(long ticketId, long ownerId,
                                        TicketExtraction.EditableFields fields) {
        int rows = jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(UPDATE_EDITABLE_SQL);
            bindEditableFields(ps, 1, fields.merchant(), fields.purchaseDate(),
                    fields.category(), fields.products(), fields.totalAmount(),
                    fields.currency());
            ps.setLong(7, ticketId);
            ps.setLong(8, ownerId);
            return ps;
        });
        return rows == 1;
    }

    /**
     * Bind the six editable columns starting at {@code base} (1 for
     * both update statements). Shared so the two write paths cannot
     * drift on null handling or JSONB wrapping.
     */
    /**
     * Binds a {@link SpendCategory} into the native {@code spend_category}
     * column.
     *
     * <p>{@code setString} cannot be used: the driver would send the value
     * as {@code character varying} and Postgres rejects it with "column
     * category is of type spend_category but expression is of type
     * character varying" — the same reason {@link JsonbSupport} exists for
     * the JSONB columns. {@link Types#OTHER} sends it untyped, which the
     * server coerces against the enum, and a value that is not a member
     * fails here rather than after the row is written.
     */
    private static void bindCategory(PreparedStatement ps, int index, SpendCategory category)
            throws java.sql.SQLException {
        // Never NULL: the column is NOT NULL and "unclassifiable" is
        // SpendCategory.OTHER.
        ps.setObject(index, category.storageValue(), Types.OTHER);
    }

    private void bindEditableFields(PreparedStatement ps, int base,
                                    String merchant,
                                    java.time.LocalDate purchaseDate,
                                    SpendCategory category,
                                    List<ProductLine> products,
                                    java.math.BigDecimal totalAmount,
                                    String currency) throws java.sql.SQLException {
        ps.setString(base, merchant);
        ps.setObject(base + 1, purchaseDate);
        bindCategory(ps, base + 2, category);
        ps.setObject(base + 3, JsonbSupport.toJsonb(mapper.writeProducts(products)));
        ps.setBigDecimal(base + 4, totalAmount);
        ps.setString(base + 5, currency);
    }

    /**
     * Bind all columns for the INSERT.
     *
     * <p>Placeholder count matches {@link #INSERT_SQL} exactly.
     * {@code products} and {@code extraction_payload} are JSONB
     * (wrapped via {@link JsonbSupport#toJsonb} so the Postgres
     * driver sends the right wire type); {@code raw_response_text}
     * is plain TEXT. A duplicate {@code ticket_id} is ignored by
     * the {@code ON CONFLICT DO NOTHING} clause (see the port
     * contract for why re-save is a no-op).
     */
    private void bindInsert(PreparedStatement ps, TicketExtraction e) throws java.sql.SQLException {
        ps.setLong(1, e.ticketId());
        ps.setString(2, e.merchant());
        ps.setObject(3, e.purchaseDate());
        bindCategory(ps, 4, e.category());
        ps.setObject(5, JsonbSupport.toJsonb(mapper.writeProducts(e.products())));
        ps.setBigDecimal(6, e.totalAmount());
        ps.setString(7, e.currency());
        ps.setString(8, e.model());
        ps.setObject(9, OffsetDateTime.ofInstant(e.extractedAt(), ZoneOffset.UTC));
        ps.setString(10, e.rawResponse());
        if (e.extractionPayload() == null) {
            ps.setNull(11, Types.OTHER);
        } else {
            ps.setObject(11, JsonbSupport.toJsonb(e.extractionPayload()));
        }
    }
}