package com.ticketapp.bff.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcSessionRepository implements SessionRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Session> MAPPER = (rs, rowNum) -> new Session(
            (UUID) rs.getObject("jti"),
            (UUID) rs.getObject("user_id"),
            toInstant(rs, "issued_at"),
            toInstant(rs, "expires_at"),
            toInstant(rs, "revoked_at")
    );

    /** {@code getObject(name, OffsetDateTime.class)} avoids the legacy {@code java.sql.Timestamp} path. */
    private static Instant toInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    @Override
    public void save(Session s) {
        jdbc.update(
                """
                INSERT INTO auth_sessions (jti, user_id, issued_at, expires_at, revoked_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (jti) DO UPDATE SET
                    expires_at = EXCLUDED.expires_at,
                    revoked_at = EXCLUDED.revoked_at
                """,
                s.jti(),
                s.userId(),
                OffsetDateTime.ofInstant(s.issuedAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(s.expiresAt(), ZoneOffset.UTC),
                s.revokedAt() == null ? null : OffsetDateTime.ofInstant(s.revokedAt(), ZoneOffset.UTC)
        );
    }

    @Override
    public Optional<Session> findByJti(UUID jti) {
        return jdbc.query(
                "SELECT jti, user_id, issued_at, expires_at, revoked_at FROM auth_sessions WHERE jti = ?",
                MAPPER,
                jti
        ).stream().findFirst();
    }

    @Override
    public List<Session> findActiveByUser(UUID userId) {
        return jdbc.query(
                """
                SELECT jti, user_id, issued_at, expires_at, revoked_at
                  FROM auth_sessions
                 WHERE user_id = ?
                   AND revoked_at IS NULL
                   AND expires_at > now()
                """,
                MAPPER,
                userId
        );
    }

    @Override
    public void revoke(UUID jti) {
        jdbc.update(
                "UPDATE auth_sessions SET revoked_at = now() WHERE jti = ? AND revoked_at IS NULL",
                jti
        );
    }
}
