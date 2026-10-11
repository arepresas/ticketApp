--liquibase formatted sql

-- Give tickets.status a real type, then retire the CHECK that stood in
-- for one.
--
-- tickets_status_check (added with DELETED in V20) was a list of string
-- literals: it enforced the same seven values a type does, while leaving
-- the column VARCHAR and the list itself in one place nobody read from.
-- The type moves it into the schema.
--
-- The value order is com.ticketapp.domain.Ticket.Status's declaration
-- order, so an ORDER BY on the column matches the enum's own ordering
-- rather than an alphabetical accident.
--
--changeset ticketapp:20261013130000-create-ticket-status-type runInTransaction:true splitStatements:false
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'ticket_status') THEN
        CREATE TYPE ticket_status AS ENUM (
            'OPEN',
            'IN_ANALYSIS',
            'IN_PROGRESS',
            'ON_ERROR',
            'DONE',
            'CANCELLED',
            'DELETED'
        );
    END IF;
END
$$;

-- Explicit cast: PostgreSQL will not narrow a VARCHAR to an enum on its
-- own. Every row already holds one of the seven — the CHECK that guarded
-- them is still in place at this point, so the cast has nothing
-- unconvertible to meet.
--
--changeset ticketapp:20261013130100-ticket-status-column-type runInTransaction:true
ALTER TABLE tickets
    ALTER COLUMN status TYPE ticket_status USING status::ticket_status;

-- Now that the type carries the same seven values, the CHECK is a
-- duplicate of the schema rather than a safety net: keeping it would
-- mean editing two lists whenever a status is added, and forgetting the
-- second one would fail writes with a constraint error instead of a
-- compile error. Dropped after the conversion, never before.
--
--changeset ticketapp:20261013130200-drop-tickets-status-check runInTransaction:true
ALTER TABLE tickets DROP CONSTRAINT tickets_status_check;