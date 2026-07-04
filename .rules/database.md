# Database rules (Liquibase + JDBC)

> Load this pack when writing or reviewing migrations, JDBC queries, or schema changes.

## All schema changes go through Liquibase

- One logical change per changeset file.
- Filename: `YYYYMMDDHHMMSS_<short-descriptor>.yaml` (or `.xml`, `.sql`).
- Files live under `persistence/src/main/resources/db/changelog/changes/`.
- The numeric prefix determines apply order. Never renumber an existing shipped changeset.
- The master changelog (`db.changelog-master.yaml`) includes files in lexicographic order. Don't hand-edit it once a changeset has shipped.

## Migrations are additive

- **No `DROP` in the same release that removes the column from code.** Two-release deprecation:
  1. Stop reading the column (release N).
  2. `DROP COLUMN` in release N+1 (or later).
- Backfills ship in their own ordered migration, never bundled with the structural change.
- New columns: `NULL`-able or have a `DEFAULT`. Never `NOT NULL` without a default against an existing populated table.
- Index additions: `CREATE INDEX CONCURRENTLY` wrapped in a Liquibase `precondition` that detects lock conflicts when running outside Postgres.

## SQL style

- Lowercase keywords.
- Always name constraints: `pk_<table>`, `fk_<table>_<col>`, `idx_<table>_<col>`.
- `TIMESTAMPTZ` for every timestamp. Never bare `TIMESTAMP`.
- UUID primary keys stored as `UUID` type, not `VARCHAR(36)`.
- Avoid JSON columns unless the schema is genuinely open. Prefer typed columns.

## Naming

| Object | Convention | Example |
|--------|------------|---------|
| Table | snake_case, plural | `auth_sessions`, `users` |
| Column | snake_case | `user_id`, `created_at` |
| Primary key | `pk_<table>` | `pk_users` |
| Foreign key | `fk_<table>_<col>` | `fk_sessions_user_id` |
| Index | `idx_<table>_<col>` | `idx_sessions_user_id` |
| Check constraint | `ck_<table>_<col>` | `ck_users_email_format` |
| Liquibase id | `YYYYMMDDHHMMSS-<verb-noun>` | `20260115120000-add-users-email-index` |

## JDBC code

- `JdbcTemplate` or `NamedParameterJdbcTemplate`. No JPA/Hibernate.
- One mapper method per query. Mappers are package-private static methods on the repository.
- No SQL string concatenation. Use parameter binding.
- Transactional boundaries live in `bff` service classes, not in `persistence` repositories.

## Migration verification in tests

- One `*IT.java` per release touches the DB to assert `DATABASECHANGELOG` is up to date.
- Testcontainers (`postgres`) is the canonical DB for tests. Never test against a live DB.

## Forbidden

- `spring.jpa.hibernate.ddl-auto=update` (or any auto-DDL).
- Editing schema in Java code at runtime.
- Bundling backfill data with structural changes.
- Renaming an existing changeset that has shipped.
- Mixing `.sql`, `.xml`, and `.yaml` formats within a single migration (pick one per file).
- `DROP TABLE` without an ADR.

## Quick checklist before pushing

- [ ] New changeset under `persistence/src/main/resources/db/changelog/changes/`
- [ ] Master changelog includes the new file in order
- [ ] Migration is additive or has an ADR justifying the destructive change
- [ ] Constraints, indexes, and column types follow the conventions above
- [ ] `*IT.java` exercises the migration
- [ ] `mvn -B verify` is green
