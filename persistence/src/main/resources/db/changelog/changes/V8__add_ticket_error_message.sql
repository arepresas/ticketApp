-- error_message: last extraction failure reason for tickets that
-- reached ON_ERROR.
--
-- Before this migration the orchestrator reverted failed tickets to
-- OPEN so the next cron tick would retry them. That made transient
-- provider failures invisible (silently retried) and permanent
-- failures noisy (an infinite loop on the same broken receipt,
-- filling the WARN log every minute).
--
-- Now failed tickets stay in ON_ERROR with the error text attached,
-- and the scheduler filters on Status.OPEN — so a failed ticket is
-- never retried automatically. The dashboard reads error_message to
-- tell the user why. A manual PATCH /api/tickets/{id}/status → OPEN
-- (or CANCELLED) clears the message via Ticket.withStatus().
--
-- Additive:
--   * New column nullable, no default → existing rows get NULL,
--     which renders as "no error" — the desired behaviour for
--     tickets that were never attempted or that succeeded.
--   * The status check constraint is widened (DROP + ADD) to admit
--     the new ON_ERROR value. Not a DROP of a column or table —
--     safe to ship in the same release.
--   * No backfill: pre-migration tickets never carried an error
--     message, and reconstructing one from logs would require
--     correlating with the scheduler tick that handled them. Out of
--     scope.

ALTER TABLE tickets
    ADD COLUMN error_message TEXT;

ALTER TABLE tickets
    DROP CONSTRAINT tickets_status_check;

ALTER TABLE tickets
    ADD CONSTRAINT tickets_status_check
    CHECK (status IN ('OPEN', 'IN_PROGRESS', 'ON_ERROR', 'DONE', 'CANCELLED'));