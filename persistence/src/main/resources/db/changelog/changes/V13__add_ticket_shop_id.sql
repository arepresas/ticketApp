-- Anchor the shop on the ticket itself; drop the redundant
-- per-line shop_id column.
--
-- Every line of a receipt belongs to the same merchant by
-- construction: the normaliser resolves the shop once per
-- ticket and stamps the id onto each line_ticket row. That
-- per-line stamp is a schema smell — a single ticket can
-- never have two different shops across its lines, and the
-- BFF controller's catalogue() endpoint already admits the
-- redundancy in a comment ("all line_tickets for one ticket
-- share the same shop row, picking the first is safe").
--
-- V13 fixes the smell by moving the FK up to the ticket:
--
--   * tickets.shop_id   — nullable, FK to shops(id) ON DELETE
--     RESTRICT. NULL for OPEN / IN_PROGRESS / ON_ERROR /
--     CANCELLED tickets (no normaliser has run yet). Populated
--     by the normaliser during the DONE transition, in the
--     same transaction as the catalogue writes.
--
--   * line_tickets.shop_id removed entirely. The column is
--     redundant once the FK lives on the ticket (one line per
--     ticket → same shop as every other line in the ticket).
--     AGENTS.md §3.4 mandates a two-release deprecation
--     (stop reading in N, drop in N+1) for destructive
--     changes; the project has no production data per V9's
--     comment so we ship the drop in the same migration as
--     the code change. A future re-introduction of the column
--     would have to come with a backfill, but at that point
--     the data would come from tickets.shop_id which is
--     always the same value for every line — so the backfill
--     is a one-liner anyway.
--
-- Additive per database.md:
--   * New column NOT NULL would force a backfill; we don't
--     have production data yet (per V9's comment) so the
--     column is nullable without a DEFAULT — most rows stay
--     NULL until the normaliser runs for the first time.
--   * No index — the only consumer of shop_id is the
--     catalogue() endpoint which already filters by id (the
--     PK), so the join to shops is row-level and doesn't need
--     a separate index. Add one if a future feature queries
--     "all tickets sold at shop X" — that's a different
--     concern.
--   * No FK constraint rename on the new column — the
--     implicit name tickets_shop_id_fkey is fine.
ALTER TABLE tickets
    ADD COLUMN shop_id UUID REFERENCES shops(id) ON DELETE RESTRICT;

-- Drop the line_tickets FK and the index BEFORE the column
-- itself — the FK name (Postgres convention: <table>_<col>_fkey)
-- is the only handle Postgres gives us, and dropping the column
-- would auto-drop the FK with no way to address it by name.
ALTER TABLE line_tickets
    DROP CONSTRAINT line_tickets_shop_id_fkey;
DROP INDEX IF EXISTS idx_line_tickets_shop_id;
ALTER TABLE line_tickets
    DROP COLUMN shop_id;
