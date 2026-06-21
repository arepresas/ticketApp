package com.ticketapp.infrastructure.persistence;

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
 */
final class TicketRowMapper implements RowMapper<Ticket> {

    @Override
    public Ticket mapRow(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String title = rs.getString("title");
        String description = rs.getString("description");
        Ticket.Status status = Ticket.Status.valueOf(rs.getString("status"));
        Instant createdAt = readInstant(rs, "created_at");
        Instant updatedAt = readInstant(rs, "updated_at");
        return new Ticket(id, title, description, status, createdAt, updatedAt);
    }

    /** Treat the stored timestamp as UTC (database columns are timestamptz). */
    private static Instant readInstant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, java.time.OffsetDateTime.class)
                .withOffsetSameInstant(ZoneOffset.UTC)
                .toInstant();
    }
}
