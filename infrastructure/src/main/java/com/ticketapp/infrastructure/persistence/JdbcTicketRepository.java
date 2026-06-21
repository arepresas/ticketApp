package com.ticketapp.infrastructure.persistence;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link TicketRepository}. Plain SQL, no ORM.
 */
@Repository
public class JdbcTicketRepository implements TicketRepository {

    private final JdbcTemplate jdbc;
    private final TicketRowMapper mapper = new TicketRowMapper();

    public JdbcTicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_COLS =
            "id, title, description, status, created_at, updated_at";

    @Override
    public Optional<Ticket> findById(UUID id) {
        return jdbc.query(
                "SELECT " + SELECT_COLS + " FROM tickets WHERE id = ?",
                mapper,
                id
        ).stream().findFirst();
    }

    @Override
    public List<Ticket> findAll() {
        return jdbc.query(
                "SELECT " + SELECT_COLS + " FROM tickets ORDER BY created_at DESC",
                mapper
        );
    }

    @Override
    public Ticket save(Ticket ticket) {
        int updated = jdbc.update(
                """
                INSERT INTO tickets (id, title, description, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    title = EXCLUDED.title,
                    description = EXCLUDED.description,
                    status = EXCLUDED.status,
                    updated_at = EXCLUDED.updated_at
                """,
                ticket.id(),
                ticket.title(),
                ticket.description(),
                ticket.status().name(),
                OffsetDateTime.ofInstant(ticket.createdAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(ticket.updatedAt(), ZoneOffset.UTC)
        );
        if (updated == 0) {
            throw new IllegalStateException("Failed to upsert ticket " + ticket.id());
        }
        return ticket;
    }

    @Override
    public void deleteById(UUID id) {
        jdbc.update("DELETE FROM tickets WHERE id = ?", id);
    }

    /** Convenience for callers needing the current instant in UTC. */
    public static Instant nowUtc() {
        return Instant.now();
    }
}
