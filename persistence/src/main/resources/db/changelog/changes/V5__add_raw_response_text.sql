-- raw_response is moving from JSONB to TEXT, additively.
--
-- The original V4 schema typed `raw_response` as JSONB under the
-- assumption that the model would always emit valid JSON. That
-- assumption broke in production on 2026-07-05 when MiniMax-M3 started
-- wrapping its reply in <output>...</output> markup; PG rejected the
-- whole save with "invalid input syntax for type json", which aborted
-- the scheduler's transaction and left the ticket stuck in IN_PROGRESS.
--
-- The fix is a two-migration rollout (per AGENTS.md §3.4):
--   * V5 (this file): add raw_response_text TEXT, drop the NOT NULL on
--     the legacy JSONB column, backfill. Both columns coexist.
--   * V6 (follow-up PR): code reads/writes only raw_response_text, the
--     legacy column is dropped.
--
-- This file is additive — no column is removed, no row is mutated
-- beyond a backfill that copies raw_response::text into raw_response_text
-- (lossless for the valid JSONB values that exist today).

ALTER TABLE ticket_extractions
    ADD COLUMN raw_response_text TEXT;

-- Drop NOT NULL on the legacy column so new INSERTs can leave it empty
-- while the application writes the raw reply into raw_response_text.
-- We keep the column itself around for V6's drop.
ALTER TABLE ticket_extractions
    ALTER COLUMN raw_response DROP NOT NULL;

-- Backfill. ::text on a JSONB value yields the canonical JSON text
-- representation; existing rows are all valid JSONB (V4's type
-- constraint rejected anything else at insert time), so the cast is
-- lossless.
UPDATE ticket_extractions
   SET raw_response_text = raw_response::text
 WHERE raw_response_text IS NULL;