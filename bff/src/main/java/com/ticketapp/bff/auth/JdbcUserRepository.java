package com.ticketapp.bff.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcUserRepository implements UserRepository {

    private final JdbcTemplate jdbc;

    private static final String COLS =
            "id, google_sub, email, name, picture_url, created_at, last_login_at";

    private static final RowMapper<AuthenticatedUser> MAPPER = (rs, rowNum) -> new AuthenticatedUser(
            (UUID) rs.getObject("id"),
            rs.getString("google_sub"),
            rs.getString("email"),
            rs.getString("name"),
            rs.getString("picture_url"),
            toInstant(rs, "created_at"),
            toInstant(rs, "last_login_at")
    );

    /** Avoids the legacy {@code java.sql.Timestamp} path. */
    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    @Override
    public Optional<AuthenticatedUser> findByGoogleSub(String googleSub) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLS + " FROM app_users WHERE google_sub = ?",
                    MAPPER,
                    googleSub));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AuthenticatedUser> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLS + " FROM app_users WHERE id = ?",
                    MAPPER,
                    id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Atomic upsert. On first sight we generate the id and stamp created_at;
     * on subsequent logins we only refresh mutable fields (name, picture) and
     * last_login_at.
     */
    @Override
    public AuthenticatedUser upsertFromGoogle(String googleSub,
                                              String email,
                                              String name,
                                              String pictureUrl) {
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();

        int inserted = jdbc.update(
                """
                INSERT INTO app_users (id, google_sub, email, name, picture_url, created_at, last_login_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (google_sub) DO NOTHING
                """,
                id,
                googleSub,
                email,
                name,
                pictureUrl,
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        );

        // Refresh mutable profile fields + last_login_at regardless of insert/update.
        jdbc.update(
                """
                UPDATE app_users
                   SET email = ?,
                       name = ?,
                       picture_url = ?,
                       last_login_at = ?
                 WHERE google_sub = ?
                """,
                email,
                name,
                pictureUrl,
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                googleSub
        );

        return findByGoogleSub(googleSub)
                .orElseThrow(() -> new IllegalStateException(
                        "upsertFromGoogle produced no row for sub=" + googleSub + " (inserted=" + inserted + ")"));
    }
}
