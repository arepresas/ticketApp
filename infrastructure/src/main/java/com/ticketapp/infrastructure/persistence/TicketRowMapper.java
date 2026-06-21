package com.ticketapp.infrastructure.persistence;

import com.ticketapp.domain.Ticket;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code tickets} rows to {@link Ticket} domain objects.
 * Kept package-private to the persistence layer.
 */
final class TicketRowMapper implements RowMapper<Ticket> {

    static final TicketRowMapper INSTANCE = new TicketRowMapper();

    @Override
    public Ticket mapRow(ResultSet rs, int rowNum) throws SQLException {
        UUID id = (UUID) rs.getObject("id");
        String title = rs.getString("title");
        String description = rs.getString("description");
        Ticket.Status status = Ticket.Status.valueOf(rs.getString("status"));
        Instant createdAt = rs.getTimestamp("created_at", UTC_CAL).toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at", UTC_CAL).toInstant();
        return new Ticket(id, title, description, status, createdAt, updatedAt);
    }

    private static final java.util.Calendar UTC_CAL = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));

    private TicketRowMapper() {}
}
