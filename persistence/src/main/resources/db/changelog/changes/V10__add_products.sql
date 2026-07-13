-- Normalised product catalogue + per-ticket lines.
--
-- Triggered when a ticket transitions to DONE (or otherwise lands in
-- a validated state). The full extraction payload already lives in
-- `ticket_extractions.products` as JSONB; this migration snapshots
-- it into four relational tables so dashboard queries can join
-- cleanly without parsing JSONB at runtime.
--
-- Entities (full normalisation, not denormalised):
--
--   shops          master merchant registry. Match key is the
--                  normalised merchant name (lower(trim(name))).
--
--   products       master product registry. Match key is
--                  (normalised_name, unit) so "Bread" and "Bread
--                  1kg" become distinct products; NULL unit is a
--                  distinct unit, not a wildcard.
--
--   prices         per-ticket price snapshot. A product can have
--                  many prices over time because it appears on
--                  many tickets; UNIQUE (product_id, ticket_id,
--                  amount) lets the same product on the same ticket
--                  reuse one price row when the AI emits it twice,
--                  while still allowing different amounts when the
--                  receipt genuinely shows two (loyalty discount,
--                  multi-pack variation, etc.).
--
--   line_tickets   one row per (ticket, product). Quantity +
--                  line_total + FK to the price snapshot. The
--                  price_id is the source of truth for that line's
--                  per-unit amount — line_tickets doesn't store it
--                  directly so a single price can be referenced by
--                  multiple lines (or, if a future feature splits a
--                  product's bundle, by multiple lines in a
--                  different way).
--
-- The four tables cross-reference each other with FKs; the
-- on-delete semantics mirror the architectural layers:
--   * tickets → line_tickets: CASCADE (delete the ticket, lines go)
--   * tickets → prices:        CASCADE (price is anchored to its ticket)
--   * products → prices:       RESTRICT (catalog integrity; deleting
--                                a master product is a deliberate op,
--                                not a cascade side effect)
--   * shops/products → lines:  RESTRICT (same — masters are kept
--                                intact even after ticket history
--                                goes away)
--
-- Migration replaces an earlier draft (V10 was just `products` +
-- `ticket_products` — a flat shape missing the shop and the price
-- entity). The feature branch is pre-merge so the rewrite is safe;
-- in a future shipped state a proper V10 → V11 would require a
-- destructive DROP+CREATE pair. No backfill in this file: prior
-- to V10 nothing related to product catalogues existed.

CREATE TABLE IF NOT EXISTS shops (
    id               UUID         PRIMARY KEY,
    name             VARCHAR(255) NOT NULL,
    normalised_name  VARCHAR(255) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_shops_normalised_name UNIQUE (normalised_name)
);

CREATE TABLE IF NOT EXISTS products (
    id               UUID         PRIMARY KEY,
    name             VARCHAR(255) NOT NULL,
    normalised_name  VARCHAR(255) NOT NULL,
    unit             VARCHAR(16),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Unique match key: same name + same unit = same product id.
-- COALESCE so NULL units collide with each other (the standard UNIQUE
-- predicate treats NULLs as distinct in B-tree indexes).
CREATE UNIQUE INDEX IF NOT EXISTS idx_products_normalised_name_unit
    ON products (normalised_name, COALESCE(unit, ''));

CREATE TABLE IF NOT EXISTS prices (
    id          UUID          PRIMARY KEY,
    product_id  UUID          NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    ticket_id   UUID          NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    amount      NUMERIC(12,4) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_prices_amount_nonneg CHECK (amount >= 0)
);

-- One price per (product, ticket, amount). A second occurrence of the
-- same product on the same ticket at the same amount reuses the same
-- row instead of duplicating it.
CREATE UNIQUE INDEX IF NOT EXISTS idx_prices_product_ticket_amount
    ON prices (product_id, ticket_id, amount);

CREATE INDEX IF NOT EXISTS idx_prices_ticket_id ON prices (ticket_id);
CREATE INDEX IF NOT EXISTS idx_prices_product_id ON prices (product_id);

CREATE TABLE IF NOT EXISTS line_tickets (
    id          UUID          PRIMARY KEY,
    ticket_id   UUID          NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    shop_id     UUID          NOT NULL REFERENCES shops(id) ON DELETE RESTRICT,
    product_id  UUID          NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    price_id    UUID          NOT NULL REFERENCES prices(id) ON DELETE RESTRICT,
    quantity    NUMERIC(10,3) NOT NULL,
    line_total  NUMERIC(12,2) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_line_tickets_qty_positive CHECK (quantity > 0)
);

-- One line per (ticket, product) — re-validating the same ticket
-- upserts the existing row in place via ON CONFLICT DO UPDATE.
CREATE UNIQUE INDEX IF NOT EXISTS idx_line_tickets_ticket_product
    ON line_tickets (ticket_id, product_id);

CREATE INDEX IF NOT EXISTS idx_line_tickets_ticket_id
    ON line_tickets (ticket_id);

CREATE INDEX IF NOT EXISTS idx_line_tickets_shop_id
    ON line_tickets (shop_id);

CREATE INDEX IF NOT EXISTS idx_line_tickets_product_id
    ON line_tickets (product_id);

CREATE INDEX IF NOT EXISTS idx_line_tickets_price_id
    ON line_tickets (price_id);
