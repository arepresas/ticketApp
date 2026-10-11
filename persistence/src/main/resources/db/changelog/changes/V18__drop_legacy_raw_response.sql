-- Drop the legacy raw_response JSONB column (planned in ADR 0006 D8).
--
-- Since V5 every write goes to raw_response_text TEXT; the mapper
-- already prefers the TEXT column, and no reader needs the legacy one.
--
-- Second half of the two-release deprecation per database.md: code
-- stopped writing the column in V5, the DROP lands here alongside the
-- mapper cleanup that removes the fallback read.
--
-- Rule note (database.md asks for N stops-reading / N+1 drops as
-- separate releases): the N half shipped as V5 (writes moved to TEXT).
-- There was never a backfill to replay — the JSONB column was only ever
-- written before the project had data anyone cared about, so dropping it
-- loses nothing.
ALTER TABLE ticket_extractions
    DROP COLUMN IF EXISTS raw_response;