-- Add OCR transcription column to tickets.
--
-- Populated at upload time by the OCR step that runs before the
-- ticket is returned to the SPA (see the BFF's
-- ImageTextExtractionSyncService and the domain ImageTextExtractor
-- port). The column is nullable: existing rows predate the OCR step
-- and stay valid as NULL; new uploads land here with the verbatim
-- text the provider returned, or NULL when the provider couldn't
-- produce any.
--
-- Additive per database.md:
--   * Nullable, no DEFAULT — the orchestrator is the only writer
--     and it always knows whether it managed to transcribe the
--     image or not.
--   * No backfill — pre-existing rows legitimately have no OCR
--     text; the column carries exactly the values the application
--     has produced since the migration shipped.
ALTER TABLE tickets
    ADD COLUMN ocr_text TEXT;
