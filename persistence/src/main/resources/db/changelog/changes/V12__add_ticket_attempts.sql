-- attempts: number of times the AI extraction pipeline has been
-- triggered for this ticket. Surfaced in the dashboard next to the
-- status badge so the user can see how many retries have been
-- burned on a stuck extraction. Reset to zero on a fresh ticket; the
-- orchestrator increments the counter on every processTicket call
-- (success or failure). Manual PATCH /api/tickets/{id}/status → OPEN
-- does NOT reset the counter — "I clicked retry three times" is the
-- actionable signal we want to preserve.
--
-- Additive migration per database.md:
--   * NOT NULL with DEFAULT 0 — every pre-existing row gets 0 on the
--     ALTER, satisfying the constraint without a backfill migration.
--     The default is intentionally NOT dropped: the column is purely
--     informational (the UI displays the value; nothing depends on
--     the bind being explicit). Future inserts may rely on the
--     application code, but a DEFAULT keeps ad-hoc SQL painless.
--   * No CHECK constraint — there's no business meaning for an upper
--     bound on attempts today, and adding one would force a second
--     migration the day we want to lift it.
--   * No index — the column is read alongside the row, never as a
--     filter or sort key. The existing owner_id + status indexes
--     cover the read paths.

ALTER TABLE tickets
    ADD COLUMN attempts INT NOT NULL DEFAULT 0;