-- owner_id: the user who created the ticket. Every read/write in the
-- controller path filters by this column; cross-tenant reads are
-- refused at the SQL layer.
--
-- The scheduler (TicketExtractionJob) runs without a user session and
-- needs to process tickets from any owner, so the column is read in
-- the SYSTEM scope too via findOpenForExtraction. The controller never
-- calls that path.
--
-- Additive migration per database.md:
--   * New column NOT NULL. The default sentinel is applied for the
--     moment of the ALTER so the constraint is satisfiable on any
--     pre-existing row (the table was empty in dev at the time of
--     this migration; the default is a safety net for any future
--     backfill scenario). The default is dropped in the same
--     migration so future INSERTs MUST bind owner_id explicitly —
--     production code paths already do.
--   * No backfill row in this migration. Pre-V9 rows were created
--     without an owner; if any exist, the sentinel default takes
--     over (UUID 00000000-0000-0000-0000-000000000000) and the
--     corresponding user record (if it ever exists) would silently
--     own them. In practice the project has no production data at
--     this point.
--   * Index added — owner-scoped list queries (the dashboard's
--     "list my tickets" and "list my pending tickets") filter on
--     owner_id + status and would otherwise full-scan the table.

ALTER TABLE tickets
    ADD COLUMN owner_id UUID NOT NULL
    DEFAULT '00000000-0000-0000-0000-000000000000';

ALTER TABLE tickets
    ALTER COLUMN owner_id DROP DEFAULT;

CREATE INDEX IF NOT EXISTS idx_tickets_owner_id ON tickets (owner_id);