-- Add IN_ANALYSIS to the ticket status enum.
--
-- The status enum grew from 5 to 6 values: the new IN_ANALYSIS
-- state is set by the scheduler before it invokes
-- TicketExtractionService.processTicket, so the dashboard can
-- distinguish "the AI is currently being called" (IN_ANALYSIS)
-- from "the AI is done, awaiting your validation" (IN_PROGRESS).
-- The two share the same pipeline entry point (the orchestrator)
-- but live on opposite sides of the provider call.
--
-- Additive per database.md:
--   * New value added to the existing CHECK constraint via DROP
--     + ADD. Postgres lets you DROP CONSTRAINT and ADD CONSTRAINT
--     in two statements inside the same migration — there's no
--     ALTER CONSTRAINT form. The constraint name is the
--     auto-generated `tickets_status_check` (the V1 + V8
--     migrations used an anonymous CHECK, so Postgres assigned
--     the name). Verified in a follow-up query against
--     information_schema.
--   * No backfill — no existing row uses IN_ANALYSIS, the column
--     carries the same values it had before.
--   * No default — the constraint just admits one more value;
--     the application is the only writer.
ALTER TABLE tickets DROP CONSTRAINT tickets_status_check;
ALTER TABLE tickets ADD CONSTRAINT tickets_status_check
    CHECK (status IN ('OPEN', 'IN_ANALYSIS', 'IN_PROGRESS',
                       'ON_ERROR', 'DONE', 'CANCELLED'));
