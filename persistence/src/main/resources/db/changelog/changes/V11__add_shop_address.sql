-- shops: extended contact info.
--
-- Carries the merchant's postal address, tax id, phone, and website
-- so the dashboard can show "where was this receipt from" and the
-- user can edit any field manually via PATCH /api/shops/{id}.
--
-- All columns nullable: extraction may produce a subset (some
-- receipts omit the phone, some don't print a tax id, etc.) and the
-- manual edit endpoint accepts partial updates.
--
-- Additive per database.md:
--   * No defaults — the columns stay NULL until the first source
--     (extraction or manual edit) populates them. A DEFAULT '' would
--     mask "we never saw this" behind "we saw an empty string" and
--     force every read path to distinguish the two.
--   * No new constraints or indexes — the match key stays on
--     normalised_name (uq_shops_normalised_name). Address fields
--     are descriptive, not identifiers.
--   * No FK — address is plain text. A future "country" lookup
--     table would warrant a FK, but that's a separate concern.
--   * No backfill — V10's comment explicitly states the table was
--     empty at V10 ship time. Any rows added before this migration
--     simply get NULLs across the new columns.

ALTER TABLE shops
    ADD COLUMN address_line VARCHAR(255),
    ADD COLUMN postal_code  VARCHAR(16),
    ADD COLUMN city         VARCHAR(128),
    ADD COLUMN country      CHAR(2),
    ADD COLUMN phone        VARCHAR(32),
    ADD COLUMN tax_id       VARCHAR(32),
    ADD COLUMN website      VARCHAR(255);