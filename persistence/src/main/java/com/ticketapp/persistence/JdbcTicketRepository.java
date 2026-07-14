package com.ticketapp.persistence;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * JDBC implementation of {@link TicketRepository}. Plain SQL, no ORM.
 *
 * <p>Every user-served query carries an {@code owner_id} predicate.
 * Cross-tenant reads return empty / no-op so the BFF can answer 404
 * without leaking existence. The only path that runs without an
 * owner scope is {@link #findOpenForExtraction(int)}, which the
 * scheduler calls — that path is documented as system-only on the
 * port and must not be reached from the controller layer.
 */
@Repository
public class JdbcTicketRepository implements TicketRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final TicketRowMapper mapper = new TicketRowMapper();

    public JdbcTicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    private static final String SELECT_COLS =
            "id, owner_id, title, description, status, created_at, updated_at, " +
            "content_type, file_name, file_data, error_message, attempts";

    /** Single source of truth for the SELECT prefix used in every read query. */
    private static final String SELECT_PREFIX = "SELECT ";

    private static final String UPSERT_SQL = """
            INSERT INTO tickets
                (id, owner_id, title, description, status, created_at, updated_at,
                 content_type, file_name, file_data, error_message, attempts)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                owner_id = EXCLUDED.owner_id,
                title = EXCLUDED.title,
                description = EXCLUDED.description,
                status = EXCLUDED.status,
                updated_at = EXCLUDED.updated_at,
                content_type = EXCLUDED.content_type,
                file_name = EXCLUDED.file_name,
                file_data = EXCLUDED.file_data,
                error_message = EXCLUDED.error_message,
                attempts = EXCLUDED.attempts
            """;

    @Override
    public Optional<Ticket> findById(UUID id, UUID ownerId) {
        // Single round-trip with both predicates — the row is invisible
        // when either doesn't match. No "exists then check" two-step
        // that would let an attacker probe ticket ids.
        return jdbc.query(
                SELECT_PREFIX + SELECT_COLS
                        + " FROM tickets WHERE id = ? AND owner_id = ?",
                mapper,
                id, ownerId
        ).stream().findFirst();
    }

    @Override
    public List<Ticket> findOpenForExtraction(int limit) {
        // System-scope query — NO owner predicate. Called by the cron
        // scheduler which runs without a user session. Returns
        // OPEN-status tickets ordered oldest-first so the backlog
        // drains FIFO. Limit caps memory and SQL cost on a single tick.
        return jdbc.query(
                SELECT_PREFIX + SELECT_COLS
                        + " FROM tickets WHERE status = 'OPEN'"
                        + " ORDER BY created_at ASC LIMIT ?",
                mapper,
                limit);
    }

    @Override
    public Ticket save(Ticket ticket) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(UPSERT_SQL);
            ps.setObject(1, ticket.id());
            ps.setObject(2, ticket.ownerId());
            ps.setString(3, ticket.title());
            ps.setString(4, ticket.description());
            ps.setString(5, ticket.status().name());
            ps.setObject(6, OffsetDateTime.ofInstant(ticket.createdAt(), ZoneOffset.UTC));
            ps.setObject(7, OffsetDateTime.ofInstant(ticket.updatedAt(), ZoneOffset.UTC));
            // file columns are nullable — setNull keeps existing rows valid
            // while letting uploads populate all three.
            if (ticket.contentType() == null) ps.setNull(8, Types.VARCHAR);
            else ps.setString(8, ticket.contentType());
            if (ticket.fileName() == null) ps.setNull(9, Types.VARCHAR);
            else ps.setString(9, ticket.fileName());
            if (ticket.fileData() == null) ps.setNull(10, Types.BINARY);
            else ps.setBytes(10, ticket.fileData());
            if (ticket.errorMessage() == null) ps.setNull(11, Types.VARCHAR);
            else ps.setString(11, ticket.errorMessage());
            ps.setInt(12, ticket.attempts());
            return ps;
        });
        return ticket;
    }

    @Override
    public boolean deleteById(UUID id, UUID ownerId) {
        int rows = jdbc.update(
                "DELETE FROM tickets WHERE id = ? AND owner_id = ?",
                id, ownerId);
        return rows > 0;
    }

    @Override
    public List<Ticket> findByStatusIn(Set<Ticket.Status> statuses, UUID ownerId) {
        if (statuses == null || statuses.isEmpty() || ownerId == null) {
            return List.of();
        }
        // NamedParameterJdbcTemplate expands the IN-list safely — never
        // concatenate the values into the SQL string (database.md rule).
        String sql = SELECT_PREFIX + SELECT_COLS
                + " FROM tickets WHERE owner_id = :owner AND status IN (:statuses)"
                + " ORDER BY created_at DESC";
        var params = new MapSqlParameterSource()
                .addValue("owner", ownerId)
                .addValue("statuses", statuses.stream().map(Enum::name).toList());
        return namedJdbc.query(sql, params, mapper);
    }

    /** Convenience for callers needing the current instant in UTC. */
    public static Instant nowUtc() {
        return Instant.now();
    }
}
