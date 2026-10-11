--liquibase formatted sql

-- Fold every stored category onto the closed SpendCategory set.
--
-- Split from the structural change (V25) per database.md: "backfills
-- ship in their own ordered migration, never bundled with the
-- structural change".
--
-- Why this file exists at all: the prompt used to ask the model for
-- `food|pharmacy|restaurant|fuel|other` while the dashboard reported a
-- different whitelist (`transport|food|lodging|other`) through a CASE
-- in the read query. Nothing agreed, so a fuel receipt was stored as
-- `fuel` and reported as `other`, and `transport` / `lodging` were
-- never written at all. The column now holds one set and V25
-- constrains it to these values.
--
-- The mapping is the enum's own LEGACY_LABELS table in
-- SpendCategory — restaurant folds into food, fuel into transport, and
-- anything unrecognised (including NULL) into other. 'food' and
-- 'other' are already members and are left alone.
--
-- lower(btrim(...)) rather than an exact match: the column was filled
-- by a language model, so casing and padding vary. Case-insensitive
-- matching is also what the runtime fold does, so a row this migration
-- misses still reads correctly.
--
--changeset ticketapp:20261012120000-fold-ticket-extraction-category runInTransaction:true
UPDATE ticket_extractions
SET category = CASE
    WHEN category IS NULL OR btrim(category) = '' THEN 'other'
    WHEN lower(btrim(category)) IN ('food', 'groceries', 'grocery',
                                   'supermarket', 'restaurant', 'restaurants')
        THEN 'food'
    WHEN lower(btrim(category)) IN ('transport', 'fuel', 'gasoline', 'petrol',
                                   'taxi', 'parking')
        THEN 'transport'
    WHEN lower(btrim(category)) IN ('lodging', 'hotel') THEN 'lodging'
    WHEN lower(btrim(category)) IN ('pharmacy', 'health', 'beauty') THEN 'pharmacy'
    ELSE 'other'
END
WHERE category IS DISTINCT FROM CASE
    WHEN category IS NULL OR btrim(category) = '' THEN 'other'
    WHEN lower(btrim(category)) IN ('food', 'groceries', 'grocery',
                                   'supermarket', 'restaurant', 'restaurants')
        THEN 'food'
    WHEN lower(btrim(category)) IN ('transport', 'fuel', 'gasoline', 'petrol',
                                   'taxi', 'parking')
        THEN 'transport'
    WHEN lower(btrim(category)) IN ('lodging', 'hotel') THEN 'lodging'
    WHEN lower(btrim(category)) IN ('pharmacy', 'health', 'beauty') THEN 'pharmacy'
    ELSE 'other'
END;