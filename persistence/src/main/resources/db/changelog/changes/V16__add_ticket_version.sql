-- Optimistic-locking version for tickets.
--
-- Guards the read-modify-write races between the extraction scheduler
-- (segment 1 marks IN_ANALYSIS, segment 2 flips IN_PROGRESS) and
-- user-driven PATCHes (retry to OPEN, cancel, mark DONE). Every
-- `save` bumps the column and refuses to overwrite a row whose
-- version moved underneath the caller (see JdbcTicketRepository).
--
-- Additive per database.md:
--   * New column, NOT NULL with DEFAULT 0 — existing rows land on
--     version 0 and every subsequent save bumps from there. No
--     backfill needed; no reader depends on a specific value.
ALTER TABLE tickets
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
