--liquibase formatted sql

-- Give ticket_extractions.category a real type.
--
-- The CHECK this replaces constrained a VARCHAR to a list of string
-- literals, which is not the same thing as a type: the allowed values
-- lived in three places (the prompt, the CHECK, and the enum in Java),
-- and nothing but convention kept them equal. A native enum moves the
-- list into the schema, where the column, the query planner and every
-- reader agree by construction.
--
-- Split from the type build so a database that already has the type
-- fails here with a clear owner instead of halfway through the column
-- conversion.
--
-- Idempotent through a DO block because Liquibase has no "create type
-- if not exists": plain CREATE TYPE has no such form, and this file
-- must not explode on a database where an operator provisioned it out
-- of band. splitStatements:false because the DO body carries semicolons
-- Liquibase would otherwise split on, truncating the statement at the
-- first one.
--
-- The value order is the set's own order, not alphabetical: an ORDER BY
-- on a native enum sorts by declaration, so this is the order the
-- dashboard and any report get for free.
--
--changeset ticketapp:20261012120100-create-spend-category-type runInTransaction:true splitStatements:false
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'spend_category') THEN
        CREATE TYPE spend_category AS ENUM (
            'food',
            'transport',
            'lodging',
            'pharmacy',
            'other'
        );
    END IF;
END
$$;

-- Convert the column, then close it.
--
-- V24 has already rewritten every row onto one of these five values, so
-- the USING cast has nothing unconvertible to meet. The cast is written
-- explicitly because PostgreSQL will not narrow VARCHAR to an enum on
-- its own.
--
-- NOT NULL is not decoration: a native enum column still accepts NULL,
-- and NULL is the one value the domain has no member for — an
-- unclassifiable receipt is stored as 'other'.
--
--changeset ticketapp:20261012120200-category-column-to-spend-category-type runInTransaction:true
ALTER TABLE ticket_extractions
    ALTER COLUMN category TYPE spend_category USING category::spend_category;

--changeset ticketapp:20261012120300-category-column-not-null runInTransaction:true
ALTER TABLE ticket_extractions
    ALTER COLUMN category SET NOT NULL;