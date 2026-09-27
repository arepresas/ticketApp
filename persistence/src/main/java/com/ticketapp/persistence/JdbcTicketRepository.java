package com.ticketapp.persistence;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketExtractionQueue;
import com.ticketapp.domain.TicketRepository;
import com.ticketapp.domain.TicketSummary;
import com.ticketapp.domain.exceptions.OptimisticLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
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
public class JdbcTicketRepository implements TicketRepository, TicketExtractionQueue {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final TicketRowMapper mapper = new TicketRowMapper();

    public JdbcTicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    private static final String SELECT_COLS =
            "id, owner_id, title, description, status, created_at, updated_at, " +
            "content_type, file_name, file_data, error_message, attempts, shop_id, " +
            "ocr_text, version";

    /**
     * List-view projection: everything the dashboard renders except
     * the blobs. {@code octet_length(file_data)} travels as
     * {@code size_bytes} so callers never load the attachment. The
     * {@code ::bigint} cast keeps the driver mapping stable
     * (octet_length yields int4, which pgjdbc will not widen to
     * {@code Long} on read).
     */
    private static final String SUMMARY_COLS =
            "id, owner_id, title, description, status, created_at, updated_at, " +
            "content_type, file_name, octet_length(file_data)::bigint AS size_bytes, " +
            "error_message, attempts, shop_id";

    /** Single source of truth for the SELECT prefix used in every read query. */
    private static final String SELECT_PREFIX = "SELECT ";

    /**
     * Positional index contract for the id/version predicates below:
     * UPDATE_SQL carries 13 value columns (positions 1-13) then id
     * (14) and version (15); INSERT_SQL carries id (1), the same 13
     * value columns (2-14), then version (15). Adding or reordering a
     * column must update both statements and these indices together.
     */
    private static final String UPDATE_SQL = """
            UPDATE tickets SET
                owner_id = ?, title = ?, description = ?, status = ?,
                created_at = ?, updated_at = ?,
                content_type = ?, file_name = ?, file_data = ?,
                error_message = ?, attempts = ?, shop_id = ?, ocr_text = ?,
                version = version + 1
            WHERE id = ? AND version = ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO tickets
                (id, owner_id, title, description, status, created_at, updated_at,
                 content_type, file_name, file_data, error_message, attempts, shop_id,
                 ocr_text, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    @Override
    public Optional<Ticket> findById(UUID id, UUID ownerId) {
        // Single round-trip with all three predicates — the row is
        // invisible when any doesn't match. Soft-deleted tickets
        // behave as missing on every path (no separate "is
        // deleted" signal leaks existence either). No "exists then
        // check" two-step that would let an attacker probe ids.
        return jdbc.query(
                SELECT_PREFIX + SELECT_COLS
                        + " FROM tickets WHERE id = ? AND owner_id = ?"
                        + " AND status <> 'DELETED'",
                mapper,
                id, ownerId
        ).stream().findFirst();
    }

    @Override
    public List<Ticket> findOpenForExtraction(int limit) {
        // System-scope query — NO owner predicate. Called by the cron
        // scheduler which runs without a user session. Returns
        // OPEN-status tickets ordered oldest-first so the backlog
        // drains FIFO. The anti-join excludes already-extracted
        // tickets in SQL (no in-memory id list). Limit caps memory
        // and SQL cost on a single tick.
        return jdbc.query(
                SELECT_PREFIX + SELECT_COLS
                        + " FROM tickets t WHERE t.status = 'OPEN'"
                        + " AND NOT EXISTS (SELECT 1 FROM ticket_extractions e"
                        + " WHERE e.ticket_id = t.id)"
                        + " ORDER BY t.created_at ASC LIMIT ?",
                mapper,
                limit);
    }

    @Override
    public List<Ticket> requeueAbandonedAnalysis(Instant attemptedBefore, int limit) {
        // System-scope mutation — NO owner predicate, same as the
        // query above. The anti-join keeps the invariant that a
        // ticket with an extraction row is never re-queued (it is
        // already IN_PROGRESS, not abandoned). NULL attempts are
        // excluded: a ticket that reached IN_ANALYSIS always has a
        // timestamp, so a null there means the row predates the
        // bookkeeping column.
        List<Ticket> stuck = jdbc.query(
                SELECT_PREFIX + SELECT_COLS
                        + " FROM tickets t WHERE t.status = 'IN_ANALYSIS'"
                        + " AND t.last_extraction_attempt_at IS NOT NULL"
                        + " AND t.last_extraction_attempt_at < ?"
                        + " AND NOT EXISTS (SELECT 1 FROM ticket_extractions e"
                        + " WHERE e.ticket_id = t.id)"
                        + " ORDER BY t.last_extraction_attempt_at ASC LIMIT ?",
                mapper,
                java.sql.Timestamp.from(attemptedBefore), limit);
        // Re-queue through the version-guarded save so a worker that
        // is still alive and about to write its extraction wins the
        // race instead of being silently overwritten.
        return stuck.stream()
                .map(t -> save(t.withStatus(Ticket.Status.OPEN)))
                .toList();
    }

    /**
     * Guarded write: UPDATE applies only when the row version still
     * matches; a miss falls back to INSERT when the row is new, or
     * throws {@link OptimisticLockException} when another writer won
     * the race. The returned copy carries the bumped version so
     * callers can chain further saves without re-reading.
     */
    @Override
    public Ticket save(Ticket ticket) {
        int updated = jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(UPDATE_SQL);
            bindTicket(ps, ticket, 1);
            ps.setObject(14, ticket.id());
            ps.setLong(15, ticket.version());
            return ps;
        });
        if (updated == 1) {
            return ticket.nextVersion();
        }
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tickets WHERE id = ?)",
                Boolean.class, ticket.id()));
        if (exists) {
            throw new OptimisticLockException(ticket.id(),
                    "ticket " + ticket.id() + " was modified concurrently"
                            + " (expected version " + ticket.version() + ")");
        }
        // New row: the version always starts at 0 regardless of what
        // the caller carries. ON CONFLICT DO NOTHING closes the
        // check-then-act window between the EXISTS probe and this
        // INSERT: a concurrent insert wins and this path surfaces
        // OptimisticLock instead of a raw duplicate-key error. A row
        // deleted between the caller's read and this write still
        // re-inserts (indistinguishable from a create at this
        // level — same as the old upsert); callers that must not
        // resurrect should pre-check existence themselves.
        int inserted = jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(INSERT_SQL + " ON CONFLICT (id) DO NOTHING");
            ps.setObject(1, ticket.id());
            bindTicket(ps, ticket, 2);
            ps.setLong(15, 0);
            return ps;
        });
        if (inserted == 0) {
            throw new OptimisticLockException(ticket.id(),
                    "ticket " + ticket.id() + " was inserted concurrently");
        }
        return ticket.withVersion(0);
    }

    /**
     * Bind the 13 value columns shared by INSERT and UPDATE, starting
     * at {@code base} (1 for UPDATE, 2 for INSERT where position 1 is
     * the id). The id/version predicates are bound by the caller
     * because their positions differ per statement.
     */
    private static void bindTicket(PreparedStatement ps, Ticket ticket, int base) throws SQLException {
            ps.setObject(base, ticket.ownerId());
            ps.setString(base + 1, ticket.title());
            ps.setString(base + 2, ticket.description());
            ps.setString(base + 3, ticket.status().name());
            ps.setObject(base + 4, OffsetDateTime.ofInstant(ticket.createdAt(), ZoneOffset.UTC));
            ps.setObject(base + 5, OffsetDateTime.ofInstant(ticket.updatedAt(), ZoneOffset.UTC));
            // Each nullable column routes through the same
            // bindNullable helpers — the per-column if/else lived
            // inline previously and ballooned the cognitive complexity
            // of this method past Sonar's ceiling. Pulling the
            // null-handling into one-liners keeps the column list
            // scannable and the SQL types centrally typed (changes to
            // a column's SQL type land in one place instead of seven).
            bindStringOrNull(ps, base + 6, ticket.contentType(), Types.VARCHAR);
            bindStringOrNull(ps, base + 7, ticket.fileName(), Types.VARCHAR);
            bindBytesOrNull(ps, base + 8, ticket.fileData(), Types.BINARY);
            bindStringOrNull(ps, base + 9, ticket.errorMessage(), Types.VARCHAR);
            ps.setInt(base + 10, ticket.attempts());
            bindObjectOrNull(ps, base + 11, ticket.shopId(), Types.OTHER);
            bindStringOrNull(ps, base + 12, ticket.ocrText(), Types.LONGVARCHAR);
    }

    /**
     * Set a nullable {@code VARCHAR} parameter: null when
     * {@code value} is null, else the String value with the supplied
     * SQL type. Keeps the {@link #save} method flat without
     * per-column if/else branches.
     */
    private static void bindStringOrNull(PreparedStatement ps, int idx,
                                         String value, int sqlType) throws SQLException {
        if (value == null) ps.setNull(idx, sqlType);
        else ps.setString(idx, value);
    }

    /**
     * Set a nullable byte-array parameter ({@code BINARY}). Mirrors
     * {@link #bindStringOrNull} — the only difference is the typed
     * setter; using two helpers avoids an untyped
     * {@link java.sql.PreparedStatement#setObject} round-trip on
     * every null.
     */
    private static void bindBytesOrNull(PreparedStatement ps, int idx,
                                        byte[] value, int sqlType) throws SQLException {
        if (value == null) ps.setNull(idx, sqlType);
        else ps.setBytes(idx, value);
    }

    /**
     * Set a nullable {@link UUID} parameter (or any other JDBC
     * type the driver knows how to handle with {@code setObject}).
     * Same null-handling as the string and bytes variants — kept as
     * a separate helper so the SQL type for shop_id (UUID column →
     * {@code Types.OTHER}) lives next to the binding it belongs to.
     */
    private static void bindObjectOrNull(PreparedStatement ps, int idx,
                                          Object value, int sqlType) throws SQLException {
        if (value == null) ps.setNull(idx, sqlType);
        else ps.setObject(idx, value);
    }

    @Override
    public boolean deleteById(UUID id, UUID ownerId) {
        // Soft delete: flip to DELETED, keep the row (and its
        // extraction/catalogue history) for audit. Already-deleted
        // rows report false so a repeated DELETE reads as 404.
        int rows = jdbc.update(
                "UPDATE tickets SET status = 'DELETED', updated_at = now()"
                        + " WHERE id = ? AND owner_id = ? AND status <> 'DELETED'",
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
        // DELETED rows are excluded in SQL even when the caller passes
        // every status: soft-deleted tickets never surface in lists.
        String sql = SELECT_PREFIX + SELECT_COLS
                + " FROM tickets WHERE owner_id = :owner AND status IN (:statuses)"
                + " AND status <> 'DELETED'"
                + " ORDER BY created_at DESC";
        var params = new MapSqlParameterSource()
                .addValue("owner", ownerId)
                .addValue("statuses", statuses.stream().map(Enum::name).toList());
        return namedJdbc.query(sql, params, mapper);
    }

    @Override
    public List<TicketSummary> findSummariesByStatusIn(Set<Ticket.Status> statuses, UUID ownerId) {
        if (statuses == null || statuses.isEmpty() || ownerId == null) {
            return List.of();
        }
        String sql = SELECT_PREFIX + SUMMARY_COLS
                + " FROM tickets WHERE owner_id = :owner AND status IN (:statuses)"
                + " AND status <> 'DELETED'"
                + " ORDER BY created_at DESC";
        var params = new MapSqlParameterSource()
                .addValue("owner", ownerId)
                .addValue("statuses", statuses.stream().map(Enum::name).toList());
        return namedJdbc.query(sql, params, (rs, n) -> new TicketSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                Ticket.Status.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getString("content_type"),
                rs.getString("file_name"),
                rs.getObject("size_bytes", Long.class),
                rs.getString("error_message"),
                rs.getInt("attempts"),
                rs.getObject("shop_id", UUID.class)));
    }

    /**
     * Update the bookkeeping column on {@code tickets} that tracks the
     * last attempt at AI extraction. Called by the scheduler both on
     * success and on failure so the next tick has a consistent view of
     * "everything not in ticket_extractions is fair game".
     *
     * <p>Lives here (not on the extraction repository) because it
     * writes the {@code tickets} aggregate.
     */
    public void recordAttempt(UUID ticketId) {
        jdbc.update("UPDATE tickets SET last_extraction_attempt_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(java.time.Instant.now(), ZoneOffset.UTC),
                ticketId);
    }

    /** Convenience for callers needing the current instant in UTC. */
    public static Instant nowUtc() {
        return Instant.now();
    }
}
