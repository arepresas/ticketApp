package com.ticketapp.infrastructure.persistence;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Types;
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
            "id, title, description, status, created_at, updated_at, " +
            "content_type, file_name, file_data";

    private static final String UPSERT_SQL = """
            INSERT INTO tickets
                (id, title, description, status, created_at, updated_at,
                 content_type, file_name, file_data)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                title = EXCLUDED.title,
                description = EXCLUDED.description,
                status = EXCLUDED.status,
                updated_at = EXCLUDED.updated_at,
                content_type = EXCLUDED.content_type,
                file_name = EXCLUDED.file_name,
                file_data = EXCLUDED.file_data
            """;

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
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(UPSERT_SQL);
            ps.setObject(1, ticket.id());
            ps.setString(2, ticket.title());
            ps.setString(3, ticket.description());
            ps.setString(4, ticket.status().name());
            ps.setObject(5, OffsetDateTime.ofInstant(ticket.createdAt(), ZoneOffset.UTC));
            ps.setObject(6, OffsetDateTime.ofInstant(ticket.updatedAt(), ZoneOffset.UTC));
            // file columns are nullable — setNull keeps existing rows valid
            // while letting uploads populate all three.
            if (ticket.contentType() == null) ps.setNull(7, Types.VARCHAR);
            else ps.setString(7, ticket.contentType());
            if (ticket.fileName() == null) ps.setNull(8, Types.VARCHAR);
            else ps.setString(8, ticket.fileName());
            if (ticket.fileData() == null) ps.setNull(9, Types.BINARY);
            else ps.setBytes(9, ticket.fileData());
            return ps;
        });
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