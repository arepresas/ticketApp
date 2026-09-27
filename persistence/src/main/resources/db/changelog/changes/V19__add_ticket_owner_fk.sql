-- Referential integrity for ticket ownership (closes schema.md gap #1).
--
-- `tickets.owner_id` has been a logical-only reference since V9.
-- This constraint makes the database enforce what the SQL layer
-- already assumes: every ticket belongs to a real `app_users` row.
--
-- ON DELETE CASCADE: deleting a user wipes their tickets (and, by
-- cascade, extractions / prices / line_tickets) — the same privacy
-- posture as `auth_sessions`, and what GDPR-style erasure needs.
--
-- NOT VALID + immediate VALIDATE: the project has no production
-- data, so validation is expected to pass outright. If this ever
-- fails on a dirty database, split the change — keep the NOT VALID
-- constraint, clean the orphan rows out-of-band, and land the
-- VALIDATE in a follow-up migration.
ALTER TABLE tickets
    ADD CONSTRAINT fk_tickets_owner_id
    FOREIGN KEY (owner_id) REFERENCES app_users(id) ON DELETE CASCADE
    NOT VALID;

ALTER TABLE tickets VALIDATE CONSTRAINT fk_tickets_owner_id;
