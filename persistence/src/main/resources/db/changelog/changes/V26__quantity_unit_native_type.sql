--liquibase formatted sql

-- Give products.unit a real type, and give the match key a plain one.
--
-- The column has been constrained to the two members for a while
-- (V22 backfilled the NULLs, V23 set NOT NULL), but "constrained" was
-- still a VARCHAR holding a string. A native enum moves the list into
-- the schema, where the column, the index and every reader agree by
-- construction — and an ORDER BY on it sorts by declaration order for
-- free.
--
-- Idempotent through a DO block because Liquibase has no "create type
-- if not exists": plain CREATE TYPE has no such form, and this file
-- must not explode on a database where an operator provisioned it out
-- of band. splitStatements:false because the DO body carries semicolons
-- Liquibase would otherwise split on, truncating the statement at the
-- first one.
--
-- The value order is the domain enum's own order.
--
--changeset ticketapp:20261013120000-create-quantity-unit-type runInTransaction:true splitStatements:false
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'quantity_unit') THEN
        CREATE TYPE quantity_unit AS ENUM ('kg', 'unit');
    END IF;
END
$$;

-- Drop the COALESCE match key BEFORE the conversion, not after.
--
-- The expression existed because the column was nullable and Postgres
-- treats NULL as distinct in a unique index, so the COALESCE made "no
-- unit" collide with itself. V23 removed the NULLs, which makes the
-- expression an identity function written in the most expensive way
-- available.
--
-- It also cannot survive the conversion: ALTER COLUMN ... TYPE
-- re-evaluates every index built on the column, and
-- COALESCE(quantity_unit, '') is not a valid expression over an enum —
-- Postgres rejects the whole ALTER with "COALESCE types quantity_unit
-- and text cannot be matched". Dropping first turns that from a
-- failure into the obvious sequence.
--
--changeset ticketapp:20261013120100-drop-products-match-key-index runInTransaction:true
DROP INDEX IF EXISTS idx_products_normalised_name_unit;

-- The conversion is explicit because PostgreSQL will not narrow a
-- VARCHAR to an enum on its own. Every row already holds one of the two
-- values: V22 wrote 'unit' where the column used to say "no unit".
--
--changeset ticketapp:20261013120200-quantity-unit-column-type runInTransaction:true
ALTER TABLE products
    ALTER COLUMN unit TYPE quantity_unit USING unit::quantity_unit;

-- Rebuild the match key against the typed column. Same name, same
-- uniqueness — only the expression changes, so the ON CONFLICT target
-- in JdbcProductRepository can stay written as plain column names.
--
--changeset ticketapp:20261013120300-products-match-key-index-rebuild runInTransaction:true
CREATE UNIQUE INDEX idx_products_normalised_name_unit
    ON products (normalised_name, unit);