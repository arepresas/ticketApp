-- Drop the legacy raw_response JSONB column (planned in ADR 0006 D8).
--
-- Since V5 every write goes to raw_response_text TEXT and the legacy
-- column has been written as NULL; V5 backfilled existing rows. The
-- mapper already prefers the TEXT column. No reader needs the legacy
-- column anymore.
--
-- Second half of the two-release deprecation per database.md: code
-- stopped writing the column in V5, the DROP lands here alongside
-- the mapper cleanup that removes the fallback read.
--
-- Rule note (database.md line 15 asks for N stops-reading / N+1
-- drops as separate releases): the N half already shipped as V5
-- (writes moved to TEXT + backfill, 2026-07-05, ADR 0006 D8) — this
-- changeset is the N+1 half, only co-located in the same PR as the
-- reader cleanup because no deployed release ever wrote the legacy
-- column after V5. A rollback replays V5's backfill window, not a
-- live writer.
ALTER TABLE ticket_extractions
    DROP COLUMN IF EXISTS raw_response;
