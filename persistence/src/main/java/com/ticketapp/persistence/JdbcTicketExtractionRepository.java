package com.ticketapp.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketapp.domain.TicketExtraction;
import com.ticketapp.domain.TicketExtraction.ProductLine;
import com.ticketapp.domain.TicketExtractionRepository;
import com.ticketapp.persistence.ExtractionRowMapper.JsonbSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link TicketExtractionRepository}. Plain SQL,
 * no ORM.
 *
 * <p>{@code products} and {@code extraction_payload} are JSONB
 * columns carrying open-ended, structured data we own;
 * {@code raw_response} is moving from JSONB to TEXT additively (V5
 * added {@code raw_response_text TEXT} and dropped the NOT NULL on
 * the legacy JSONB column — see ADR 0006 §D8). New writes go to
 * {@code raw_response_text}; the legacy {@code raw_response} JSONB
 * column is left NULL on insert and stays around until V6 drops it.
 * {@code extraction_payload} carries the parsed canonical object
 * (added in V7) so downstream queries can access discounts,
 * pricePerKg, and full merchant/transaction data without
 * re-parsing {@code raw_response_text}.
 *
 * <p>Reading still prefers {@code raw_response_text} when present and
 * falls back to {@code raw_response} for the small window between V5
 * deploy and V6 ship — see {@link ExtractionRowMapper#mapRow}.
 */
@Repository
public class JdbcTicketExtractionRepository implements TicketExtractionRepository {

    private static final String SELECT_COLS =
            "ticket_id, merchant, purchase_date, category, products, total_amount, " +
            "currency, model, extracted_at, raw_response, raw_response_text, extraction_payload";

    private static final String UPSERT_SQL = """
            INSERT INTO ticket_extractions
                (ticket_id, merchant, purchase_date, category, products,
                 total_amount, currency, model, extracted_at,
                 raw_response, raw_response_text, extraction_payload)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, NULL, ?, ?::jsonb)
            """;

    /**
     * Update path for user-driven edits through the detail screen.
     * Touches only the mutable columns — {@code model},
     * {@code extracted_at}, {@code raw_response_text}, and
     * {@code extraction_payload} keep the AI's audit
     * ("extracted by MiniMax-M3 on …") so the dashboard's audit
     * trail stays truthful after the user corrects a line item.
     * The legacy {@code raw_response} JSONB column is not touched
     * (stays NULL on writes since V5).
     */
    private static final String UPDATE_SQL = """
            UPDATE ticket_extractions SET
                merchant = ?, purchase_date = ?, category = ?,
                products = ?::jsonb, total_amount = ?, currency = ?
            WHERE ticket_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final ExtractionRowMapper mapper;

    public JdbcTicketExtractionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.mapper = new ExtractionRowMapper(objectMapper);
    }

    @Override
    public Optional<TicketExtraction> findByTicketId(UUID ticketId) {
        List<TicketExtraction> rows = jdbc.query(
                "SELECT " + SELECT_COLS + " FROM ticket_extractions WHERE ticket_id = ?",
                (rs, n) -> mapper.mapRow(rs),
                ticketId);
        return rows.stream().findFirst();
    }

    @Override
    public TicketExtraction save(TicketExtraction extraction) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(UPSERT_SQL);
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
            ps.setString(1, extraction.merchant());
            ps.setObject(2, extraction.purchaseDate());
            if (extraction.category() == null) {
                ps.setNull(3, Types.VARCHAR);
            } else {
                ps.setString(3, extraction.category());
            }
            ps.setObject(4, JsonbSupport.toJsonb(mapper.writeProducts(extraction.products())));
            ps.setBigDecimal(5, extraction.totalAmount());
            ps.setString(6, extraction.currency());
            ps.setObject(7, extraction.ticketId());
            return ps;
        });
        if (rows == 0) {
            throw new IllegalStateException(
                    "No extraction row to replace for ticket " + extraction.ticketId());
        }
        return extraction;
    }

    /**
     * Bind all columns for the INSERT.
     *
     * <p>Placeholder count matches {@link #UPSERT_SQL} exactly. The
     * legacy {@code raw_response} column is bound as the {@code NULL}
     * literal in the SQL (no placeholder for it). {@code products},
     * {@code raw_response_text}, and {@code extraction_payload} are
     * the three source-of-truth columns the mapper fills.
     *
     * <ul>
     *   <li>Placeholder 5 ({@code products} JSONB): wrapped via
     *       {@link JsonbSupport#toJsonb} so the Postgres driver accepts
     *       a VARCHAR binding into a JSONB-typed column.</li>
     *   <li>Placeholder 10 ({@code raw_response_text} TEXT): plain
     *       {@code setString} is enough.</li>
     *   <li>Placeholder 11 ({@code extraction_payload} JSONB): same
     *       wrapper as products; nullable so {@code null} bindings
     *       work for legacy rows that don't carry it yet.</li>
     * </ul>
     */
    private void bindInsert(PreparedStatement ps, TicketExtraction e) throws java.sql.SQLException {
        ps.setObject(1, e.ticketId());
        ps.setString(2, e.merchant());
        ps.setObject(3, e.purchaseDate());
        if (e.category() == null) {
            ps.setNull(4, Types.VARCHAR);
        } else {
            ps.setString(4, e.category());
        }
        ps.setObject(5, JsonbSupport.toJsonb(mapper.writeProducts(e.products())));
        ps.setBigDecimal(6, e.totalAmount());
        ps.setString(7, e.currency());
        ps.setString(8, e.model());
        ps.setObject(9, OffsetDateTime.ofInstant(e.extractedAt(), ZoneOffset.UTC));
        // Placeholder 10 is raw_response_text — TEXT, plain VARCHAR
        // binding. The legacy raw_response JSONB column is the NULL
        // literal in UPSERT_SQL and gets no binding. V6 will drop the
        // column and remove the NULL literal in the same migration.
        ps.setString(10, e.rawResponse());
        // Placeholder 11 is the structured payload from the AI
        // provider. Nullable by design — pre-V7 rows did not have
        // this column.
        if (e.extractionPayload() == null) {
            ps.setNull(11, Types.OTHER);
        } else {
            ps.setObject(11, JsonbSupport.toJsonb(e.extractionPayload()));
        }
    }

    @Override
    public List<UUID> findExtractedTicketIds() {
        return jdbc.query(
                "SELECT ticket_id FROM ticket_extractions",
                (rs, n) -> rs.getObject("ticket_id", UUID.class));
    }

    /**
     * Update the bookkeeping column on {@code tickets} that tracks the
     * last attempt at AI extraction. Called by the scheduler both on
     * success and on failure so the next tick has a consistent view of
     * "everything not in ticket_extractions is fair game".
     */
    public void recordAttempt(UUID ticketId) {
        jdbc.update("UPDATE tickets SET last_extraction_attempt_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(java.time.Instant.now(), ZoneOffset.UTC),
                ticketId);
    }
}