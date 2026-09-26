--liquibase formatted sql
--changeset ticketapp:20260926120000-create-pg-trgm-extension runInTransaction:false
-- pg_trgm backs the product autocomplete index below.
-- Split from the index build so a locked-down role (or PG < 13,
-- where pg_trgm is not a trusted extension) fails here with a clear
-- owner instead of halfway through the index build. Provision the
-- extension out-of-band in those environments and this changeset
-- becomes a halt with no side effects.
--
-- No dbms precondition: this changelog only ever runs on Postgres
-- (local, Testcontainers, CI, prod are all PG 18) and Liquibase
-- formatted SQL does not support the dbms precondition type, so a
-- non-Postgres target would fail loudly here instead of silently
-- applying PG-only syntax.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

--changeset ticketapp:20260926120100-add-products-trgm-index runInTransaction:false
-- Trigram index for the product autocomplete prefix search.
--
-- `JdbcProductRepository.searchByNormalisedName` runs
-- `normalised_name ILIKE :prefix%`. Without an index that is a
-- sequential scan once the catalogue grows. pg_trgm makes the
-- prefix lookup index-backed regardless of column collation.
--
-- Additive per database.md. `CREATE INDEX CONCURRENTLY` per the
-- index rule (database.md line 20); the dbms precondition above is
-- the rule's wrapper for non-Postgres targets. Hence
-- runInTransaction:false — CONCURRENTLY cannot run inside a
-- transaction block. The statement is IF NOT EXISTS, but note: an
-- interrupted CONCURRENTLY leaves an INVALID index behind that IF
-- NOT EXISTS would then skip over. If this changeset ever fails
-- mid-build, drop the invalid index manually
-- (DROP INDEX CONCURRENTLY IF EXISTS idx_products_normalised_name_trgm)
-- and re-run; do not assume a green re-run rebuilt it.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_products_normalised_name_trgm
    ON products USING gin (normalised_name gin_trgm_ops);
