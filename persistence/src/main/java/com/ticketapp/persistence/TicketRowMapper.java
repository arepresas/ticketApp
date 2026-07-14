package com.ticketapp.persistence;

import com.ticketapp.domain.Ticket;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Maps {@code tickets} rows to {@link Ticket} domain objects.
 *
 * <p>Stateless, package-private, Spring-managed. No singleton: JDBC reuses
 * one mapper instance per query, so a per-request bean is sufficient and
 * avoids the static-state antipattern flagged by SonarJS (S6548 / S2885).
 *
 * <p>Timestamps come back from the driver as {@link java.sql.Timestamp};
 * we convert through {@link OffsetDateTime} (S2143) instead of using the
 * deprecated {@code Timestamp.toInstant()} overload directly.
 *
 * <p>File columns are nullable; {@code rs.getBytes("file_data")} returns
 * {@code null} for SQL NULL and is forwarded as-is to the domain.
 */
final class TicketRowMapper implements RowMapper<Ticket> {

    @Override
    public Ticket mapRow(ResultSet rs, int rowNum) throws SQLException {
        // RowMapper contract requires the rowNum param even though we
        // don't use it. The wrapper at the call site
        // (JdbcTemplate.query(sql, mapper, args...)) reads only the
        // ResultSet — rowNum is dropped.
        return mapRow(rs);
    }

    Ticket mapRow(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID ownerId = rs.getObject("owner_id", UUID.class);
        String title = rs.getString("title");
        String description = rs.getString("description");
        Ticket.Status status = Ticket.Status.valueOf(rs.getString("status"));
        Instant createdAt = readInstant(rs, "created_at");
        Instant updatedAt = readInstant(rs, "updated_at");
        String contentType = rs.getString("content_type");
        String fileName = rs.getString("file_name");
        byte[] fileData = rs.getBytes("file_data");
        String errorMessage = rs.getString("error_message");
        int attempts = rs.getInt("attempts");
        // shop_id is nullable — the normaliser writes it during the
        // DONE transition, so most rows (OPEN / IN_PROGRESS /
        // ON_ERROR / CANCELLED) carry NULL here. getObject(..., UUID.class)
        // returns null for SQL NULL and forwards as-is to the domain.
        UUID shopId = rs.getObject("shop_id", UUID.class);
        return new Ticket(id, ownerId, title, description, status, createdAt, updatedAt,
                contentType, fileName, fileData, errorMessage, attempts, shopId);
    }

    /** Treat the stored timestamp as UTC (database columns are timestamptz). */
    private static Instant readInstant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class)
                .withOffsetSameInstant(ZoneOffset.UTC)
                .toInstant();
    }
}