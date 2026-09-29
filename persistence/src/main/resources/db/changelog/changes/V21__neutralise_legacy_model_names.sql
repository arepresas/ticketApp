-- Neutralise vendor-specific model ids in the extraction audit trail.
--
-- The extraction pipeline became provider-agnostic: the provider
-- module talks to any OpenAI-compatible endpoint and the model id is
-- configuration (ADR 0007). Rows written before that carry the vendor
-- name that happened to be configured at the time, and the audit
-- trail renders that column verbatim ("extracted by <model> on
-- <date>").
--
-- Scope, deliberately narrow: ONLY the exact legacy model ids that
-- this project wrote. A prefix match would be wrong here — the same
-- vendor also publishes other ids (e.g. 'minimax-text-01') which are
-- genuine audit data, and this update cannot be undone. Other model
-- ids (gpt-4o-mini, deepseek-reasoner, ...) are left alone: this is a
-- rename, not a data purge.
--
-- If your database holds another id from that vendor, add it to the
-- list below explicitly rather than widening it to a LIKE.
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
WHERE lower(model) IN ('minimax-m3');
