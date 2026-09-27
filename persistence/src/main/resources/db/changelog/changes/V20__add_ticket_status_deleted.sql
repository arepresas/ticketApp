-- Admit DELETED in the ticket status CHECK.
--
-- DELETED is the soft-delete sink: DELETE /api/tickets/{id} flips
-- the row instead of removing it, and every read path ignores the
-- value (see JdbcTicketRepository). Follows the V14 pattern
-- (DROP + ADD; Postgres has no ALTER CONSTRAINT form) against the
-- auto-generated `tickets_status_check` name.
--
-- Additive per database.md: no existing row uses the new value, no
-- backfill, no default.
ALTER TABLE tickets DROP CONSTRAINT tickets_status_check;
ALTER TABLE tickets ADD CONSTRAINT tickets_status_check
    CHECK (status IN ('OPEN', 'IN_ANALYSIS', 'IN_PROGRESS',
                       'ON_ERROR', 'DONE', 'CANCELLED', 'DELETED'));
