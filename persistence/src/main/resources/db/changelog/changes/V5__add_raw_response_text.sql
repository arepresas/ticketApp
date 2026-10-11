-- raw_response is moving from JSONB to TEXT, additively.
--
-- Header reworded 2026-09-27, before any production deployment: the
-- incident below named the model that was configured at the time, and
-- the module is now provider-agnostic, so the wording is too. The
-- reword is comment-only and changes no DDL. Liquibase checksums the
-- file, so an environment that has already applied this changeset
-- must reconcile its DATABASECHANGELOG checksum. Reconcile ONLY
-- these two changesets and only to the checksum of the file in this
-- commit: never run clearCheckSums and never blanket-UPDATE MD5SUM,
-- because a broad reset hides genuine drift. The release notes for
-- this change carry the exact command. No schema or data is affected
-- either way.
--
-- The original V4 schema typed `raw_response` as JSONB under the
-- assumption that the model would always emit valid JSON. That
-- assumption broke in production on 2026-07-05 when the configured
-- model started wrapping its reply in <output>...</output> markup;
-- PG rejected the
-- whole save with "invalid input syntax for type json", which aborted
-- the scheduler's transaction and left the ticket stuck in IN_PROGRESS.
--
-- The fix is a two-migration rollout (per AGENTS.md §3.4):
--   * V5 (this file): add raw_response_text TEXT, drop the NOT NULL on
--     the legacy JSONB column. Both columns coexist.
--   * V6 (follow-up PR): code reads/writes only raw_response_text, the
--     legacy column is dropped.
--
-- This file is additive — no column is removed and no row is touched.

ALTER TABLE ticket_extractions
    ADD COLUMN raw_response_text TEXT;

-- Drop NOT NULL on the legacy column so new INSERTs can leave it empty
-- while the application writes the raw reply into raw_response_text.
-- We keep the column itself around for V6's drop.
ALTER TABLE ticket_extractions
    ALTER COLUMN raw_response DROP NOT NULL;

-- No backfill from the legacy JSONB column. This project has never run
-- against a database anyone cares about, so there are no rows to copy:
-- every row that exists was written after this migration. Reinstating
-- one later means writing it then, against real data, rather than
-- carrying a no-op that reads as if it mattered.