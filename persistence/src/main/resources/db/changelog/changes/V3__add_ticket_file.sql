-- Add uploaded-file columns to tickets.
-- Nullable: existing rows have no file; new uploads populate all three.
-- file_data stores the raw bytes (BYTEA) — no separate object storage.
ALTER TABLE tickets
    ADD COLUMN content_type VARCHAR(100),
    ADD COLUMN file_name    VARCHAR(255),
    ADD COLUMN file_data    BYTEA;