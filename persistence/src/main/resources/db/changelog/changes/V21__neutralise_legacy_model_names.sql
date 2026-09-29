-- Neutralise vendor-specific model ids in the extraction audit trail.
--
-- The extraction pipeline became provider-agnostic: the provider
-- module talks to any OpenAI-compatible endpoint and the model id is
-- configuration (ADR 0007). Rows written before that carry the vendor
-- name that happened to be configured at the time, and the audit
-- trail renders that column verbatim ("extracted by <model> on
-- <date>").
--
-- Scope, deliberately narrow: ONLY values naming the vendor that was
-- renamed away. Other model ids (gpt-4o-mini, deepseek-reasoner, ...)
-- are genuine and useful audit data — this is a rename, not a data
-- purge, and blanking everything would throw away information nobody
-- asked to lose.
--
-- The trade-off, stated plainly: after this migration a legacy row no
-- longer says which model produced it. That cannot be recovered from
-- the database — `raw_response_text` does not record the model either.
-- Cheapest now, while the table is small and nothing runs in
-- production, rather than later.
--
-- Data-only, no schema change. `model` stays NOT NULL; 'unknown' is
-- the replacement.

UPDATE ticket_extractions
SET model = 'unknown'
WHERE model ILIKE 'minimax%';
