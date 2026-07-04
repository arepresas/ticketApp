package com.ticketapp.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketapp.domain.TicketExtraction;
import com.ticketapp.domain.TicketExtraction.ProductLine;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import org.postgresql.util.PGobject;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Maps {@code ticket_extractions} rows to {@link TicketExtraction}
 * domain objects. Stateless; the JSONB ↔ {@code List<ProductLine>}
 * round-trip uses an injected {@link ObjectMapper} so we don't carry
 * a static field.
 *
 * <p>The {@code raw_response} column is JSONB (legacy, V4) and
 * {@code raw_response_text} is TEXT (V5). During the V5 → V6
 * transition window both columns coexist: {@code raw_response_text}
 * is the source of truth for new rows, {@code raw_response} is
 * still populated for the pre-V5 backfill. The mapper prefers the
 * TEXT column and falls back to the JSONB one when the TEXT column
 * is SQL NULL (e.g. a row written before V5 ran the backfill — not
 * expected, but cheap to guard against).
 *
 * <p>If a future read path needs to query into the response, a
 * dedicated view can use Postgres {@code jsonb_path_query} without
 * touching the mapper.
 */
@RequiredArgsConstructor
final class ExtractionRowMapper {

    private static final TypeReference<List<ProductLine>> PRODUCTS_TYPE = new TypeReference<>() {};

    @NonNull
    private final ObjectMapper objectMapper;

    TicketExtraction mapRow(ResultSet rs) throws SQLException {
        UUID ticketId = rs.getObject("ticket_id", UUID.class);
        String merchant = rs.getString("merchant");
        LocalDate purchaseDate = rs.getObject("purchase_date", LocalDate.class);
        String category = rs.getString("category");
        String productsJson = rs.getString("products");
        BigDecimal totalAmount = rs.getBigDecimal("total_amount");
        String currency = rs.getString("currency");
        String model = rs.getString("model");
        Instant extractedAt = readInstant(rs, "extracted_at");
        // Prefer the TEXT column (V5+); fall back to the legacy JSONB
        // column for any pre-V5 row that somehow slipped past the
        // backfill. Both reads go through getString — the JSONB column
        // yields its canonical text representation, which is fine
        // because the domain only carries it as an opaque string.
        String rawResponse = rs.getString("raw_response_text");
        if (rawResponse == null) {
            rawResponse = rs.getString("raw_response");
        }
        // extraction_payload (V7+) — JSONB or null for pre-V7 rows.
        String extractionPayload = rs.getString("extraction_payload");

        List<ProductLine> products;
        try {
            products = objectMapper.readValue(productsJson, PRODUCTS_TYPE);
        } catch (Exception e) {
            // Don't swallow — surface the broken JSON as a SQLException so
            // the caller logs the row id and moves on. Backend.md: never
            // catch-and-log without rethrow.
            throw new SQLException("Failed to parse products JSONB for extraction "
                    + ticketId, e);
        }

        return new TicketExtraction(ticketId, merchant, purchaseDate, category,
                products, totalAmount, currency, model, extractedAt, rawResponse,
                extractionPayload);
    }

    /**
     * Serialise the products list to a JSON string. Visible to the
     * repository's INSERT path so the JSONB column can be bound
     * correctly (see {@link JsonbSupport#toJsonb(String)}).
     */
    String writeProducts(List<ProductLine> products) throws SQLException {
        try {
            return objectMapper.writeValueAsString(products);
        } catch (Exception ex) {
            throw new SQLException("Failed to serialise products to JSONB", ex);
        }
    }

    private static Instant readInstant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    /**
     * Helpers for binding JSONB columns. The Postgres JDBC driver
     * refuses plain VARCHAR values into JSONB columns with a
     * "column … is of type jsonb but expression is of type character
     * varying" error — wrapping the JSON string in a {@link PGobject}
     * of type {@code jsonb} tells the driver to send it as the right
     * wire type.
     *
     * <p>Held as nested static so the repository can use it without
     * pulling Postgres types into its public surface.
     */
    @UtilityClass
    static final class JsonbSupport {

        PGobject toJsonb(String json) throws SQLException {
            PGobject obj = new PGobject();
            obj.setType("jsonb");
            obj.setValue(json);
            return obj;
        }
    }
}