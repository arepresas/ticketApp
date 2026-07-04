-- AI extraction pipeline (ADR 0006).
--
-- Adds:
--   * last_extraction_attempt_at to tickets — bookkeeping for the scheduler;
--     kept even on failure so the cron can skip-on-success in the future without
--     a schema change. Nullable: existing rows have never been attempted.
--   * ticket_extractions — 1:1 join to tickets with the structured data
--     extracted by the MiniMax pipeline. PK is the ticket_id itself, so
--     re-extraction requires an explicit DELETE + INSERT (not a silent
--     overwrite). FK uses ON DELETE CASCADE so a removed ticket removes
--     its extraction automatically.

ALTER TABLE tickets
    ADD COLUMN last_extraction_attempt_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_tickets_last_extraction_attempt_at
    ON tickets (last_extraction_attempt_at);

CREATE TABLE IF NOT EXISTS ticket_extractions (
    ticket_id     UUID         PRIMARY KEY REFERENCES tickets(id) ON DELETE CASCADE,
    merchant      VARCHAR(255) NOT NULL,
    purchase_date DATE         NOT NULL,
    category      VARCHAR(64),
    -- Array of {name, quantity, unit, price_per_unit, line_total}; readers
    -- should treat unknown fields as forward-compatible additions.
    products      JSONB        NOT NULL,
    total_amount  NUMERIC(12,2) NOT NULL,
    currency      CHAR(3)      NOT NULL DEFAULT 'EUR',
    model         VARCHAR(64)  NOT NULL,
    extracted_at  TIMESTAMPTZ  NOT NULL,
    -- Raw LLM response — kept for audit and re-parsing on prompt changes.
    raw_response  JSONB        NOT NULL,
    CONSTRAINT fk_ticket_extractions_ticket_id
        FOREIGN KEY (ticket_id) REFERENCES tickets(id) ON DELETE CASCADE,
    CONSTRAINT ck_ticket_extractions_total_nonneg
        CHECK (total_amount >= 0)
);

CREATE INDEX IF NOT EXISTS idx_ticket_extractions_merchant
    ON ticket_extractions (merchant);

CREATE INDEX IF NOT EXISTS idx_ticket_extractions_purchase_date
    ON ticket_extractions (purchase_date DESC);