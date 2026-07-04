# ADR 0006: AI extraction scheduler for ticket metadata

- Status: accepted
- Date: 2026-07-04
- Deciders: arepresas
- Related: backend, infrastructure, bff

## Amendment: 2026-07-05

On 2026-07-05 MiniMax-M3 started wrapping its reply in `<output>...</output>`
markup. PG rejected the save (`invalid input syntax for type json`), the
scheduler's transaction aborted, and the ticket was stuck in
`IN_PROGRESS` until manual cleanup. D2's assumption — "raw_response is
genuinely open-ended JSONB" — was wrong; it's opaque LLM output that
*should* be JSON but isn't guaranteed to be. D8 below records the fix.

## Context

Every uploaded receipt lands in the `tickets` table with only its raw
bytes plus the user-supplied title/description. There's no structured
data — merchant, purchase date, line items, prices, total — until a
human reads it. As the volume grows, manual triage is the bottleneck.

We need an automated pipeline that:
1. Picks up `OPEN` tickets on a schedule.
2. Sends the receipt to the [MiniMax API](https://platform.minimax.io)
   for structured extraction.
3. Persists the structured result alongside the ticket.
4. Marks the ticket as `IN_PROGRESS` while it is being processed.

This ADR records the architecture decisions for that pipeline. It is
required by `AGENTS.md` §3.2 because the work introduces a new runtime
dependency (Apache PDFBox) and a new cross-cutting concern (LLM-backed
extraction) that crosses every module boundary.

## Decisions

### D1. No new top-level Maven module

**Decision.** The scheduler lives in the existing `bff` module. The
HTTP client to MiniMax and the PDF text extractor live in
`infrastructure`. Domain owns the `TicketExtraction` entity and the
repository port.

**Why.** AGENTS.md §3.2 forbids new top-level modules without an ADR.
The cross-cutting concern here is small enough (one scheduled bean, one
HTTP client, one text extractor) that lifting it to a sibling module
would cost more in wiring than it saves in modularity. The same
adapter-in-infrastructure + scheduler-in-bff pattern is already used
for the upload pipeline (`JdbcTicketRepository`, `TicketController`).

**Alternative considered.** A new `ai/` module with its own package
boundary and a dedicated `application.yml` namespace. Rejected: would
require a separate Spring `@ConfigurationProperties` registration,
duplicate the Liquibase wiring, and add a module-to-module dependency
that the simpler structure does not need.

### D2. New `ticket_extractions` table (1:1 with `tickets`)

**Decision.** Extracted data lives in a dedicated table joined to
`tickets` by `ticket_id` (UNIQUE FK). The `tickets` row is not mutated
beyond status transitions.

```sql
CREATE TABLE ticket_extractions (
    ticket_id     UUID         PRIMARY KEY REFERENCES tickets(id) ON DELETE CASCADE,
    merchant      VARCHAR(255) NOT NULL,
    purchase_date DATE         NOT NULL,
    category      VARCHAR(64),
    products      JSONB        NOT NULL,
    total_amount  NUMERIC(12,2) NOT NULL,
    currency      CHAR(3)      NOT NULL DEFAULT 'EUR',
    model         VARCHAR(64)  NOT NULL,
    extracted_at  TIMESTAMPTZ  NOT NULL,
    raw_response  JSONB        NOT NULL
);
CREATE INDEX idx_ticket_extractions_merchant ON ticket_extractions (merchant);
```

**Why.** A separate table keeps the existing `Ticket` record immutable
for the parts that consumers (the BFF wire response, the dashboard)
already depend on, and lets us evolve the extraction schema (new
fields, alternate models) without touching upload code. JSONB is used
for `products` (array of line items) and `raw_response` (full LLM
response for audit / re-parsing) because the schema is genuinely
open-ended there.

**Additivity check.** database.md requires new columns to be NULL-able
or have a default. `ticket_extractions` is a brand-new table, so the
rule does not apply; the migration is unambiguously additive. No
backfill needed because there is no historical data to backfill into
it.

### D3. Apache PDFBox for PDF text extraction

**Decision.** When the receipt is a PDF, we extract its text with
Apache PDFBox 3.0.x before sending it to MiniMax.

**Why.** MiniMax's chat-completions endpoint accepts images (JPEG,
PNG, GIF, WEBP) and videos natively via `image_url` / `video_url`
content parts — but **not** PDFs. The Files API only accepts
`voice_clone | prompt_audio | t2a_async_input | video_understanding`
purposes; there is no `document_understanding`. Without a
PDF-to-text step, PDF receipts would be invisible to the extraction
pipeline. PDFBox is the de-facto Java PDF library, MIT-licensed (per
security.md's allowed-licenses list), and small (~3 MB).

**Alternative considered.** Rasterizing the first page of the PDF
to an image and sending it as `image_url`. Rejected: loses selectable
text in the prompt, doubles token usage, and risks OCR-style errors
on small print. Text extraction is strictly better when the PDF is
digital-born (the common case for receipts).

### D4. Ticket status transitions on the extraction path

**Decision.** The job transitions the ticket through this state
machine while processing:

```
OPEN ──(job picks up)──▶ IN_PROGRESS ──(success)──▶ IN_PROGRESS (stays)
                                │
                                └─(failure)────▶ OPEN (back to retry queue)
```

A ticket that already has a row in `ticket_extractions` is excluded
from the job's SELECT. A ticket that fails extraction is reverted to
`OPEN` and **will be retried on the next scheduled pass** — there is
no backoff window and no dead-letter queue. The next cron tick picks
it up again because no `ticket_extractions` row exists for it.

**Why.** The user requirement is "if there's an error, leave the
ticket as OPEN and it will be treated on the next pass." A simple
retry-on-next-tick semantics matches that exactly and keeps the
implementation free of state machines beyond what `Ticket.withStatus`
already provides.

**Alternative considered.** Adding a new `EXTRACTION_FAILED` status
to the enum. Rejected: pollutes the public enum for an internal
concern; the absence of a `ticket_extractions` row already encodes
"not yet extracted," and re-trying without a status bump is exactly
the semantics the user asked for.

### D5. Configuration via `@ConfigurationProperties("ticketapp.ai")`

**Decision.** All knobs live in `application.yml` under
`ticketapp.ai.*` and are bound to a single `AiProperties` record:

| Property | Default | Purpose |
|---|---|---|
| `enabled` | `true` | Kill switch (set to `false` in tests) |
| `base-url` | `https://api.minimax.chat` | MiniMax API root |
| `api-key` | `${MINIMAX_API_KEY:dev-placeholder}` | Bearer token (env var only) |
| `model` | `MiniMax-M3` | Model identifier |
| `cron` | `0 */15 * * * *` | When the job runs |
| `batch-size` | `5` | Max tickets per tick |
| `timeout-ms` | `30000` | HTTP client timeout |
| `retry-attempts` | `2` | Per-ticket retries before giving up for the tick |

**Why.** `application.yml` is the project's configuration root
(backend.md §Configuration). `@ConfigurationProperties` records
replace `@Value` for grouped properties (backend.md §Idioms). The API
key is bound to `${MINIMAX_API_KEY:dev-placeholder}` — the placeholder
is obviously useless and there is no default that could accidentally
work in production (security.md §Secrets).

### D6. OpenAI-compatible HTTP client, hand-rolled

**Decision.** The HTTP client uses Java's built-in `java.net.http.HttpClient`
(no new HTTP dependency) and targets MiniMax's
`POST /v1/chat/completions` endpoint, which is OpenAI-compatible.

**Why.** The MiniMax chat-completions API is a documented
OpenAI-compatible surface — adding `spring-ai` or `openai-java` would
be a heavyweight dependency for what is effectively one POST request
with a JSON body. Java 25's `HttpClient` supports HTTP/2, timeouts,
and JSON serialization via Jackson (already on the classpath). One
class, ~150 lines, no new transitive deps.

**Alternative considered.** Spring AI. Rejected: introduces a
substantial dependency tree for a single endpoint, and ties us to a
specific abstraction layer. We'd rather see the raw JSON.

### D7. Tests: mock the HTTP layer; no real MiniMax calls in CI

**Decision.** Unit tests use a stub `HttpClient` to verify the wire
format MiniMax expects. The integration test for the job boots a full
Spring context with `ticketapp.ai.enabled=false` and a
`@TestConfiguration` that swaps in a fake `MiniMaxApiClient` to verify
the orchestration logic (status transitions, persistence, error
revert). No test ever hits `api.minimax.chat`.

**Why.** testing.md bans real network calls in unit tests; the CI
quality gate must be deterministic and not consume paid tokens.

### D8. `raw_response` moves from JSONB to TEXT, additively (2026-07-05)

**Decision.** Add `raw_response_text TEXT` next to `raw_response JSONB`,
backfill the new column from the old, drop `NOT NULL` on the legacy
column, and write new rows into the TEXT column. The legacy column is
left in place and dropped in a follow-up migration once the application
reads from the new column exclusively.

```sql
-- V5__add_raw_response_text.sql
ALTER TABLE ticket_extractions
    ADD COLUMN raw_response_text TEXT;
ALTER TABLE ticket_extractions
    ALTER COLUMN raw_response DROP NOT NULL;
UPDATE ticket_extractions
   SET raw_response_text = raw_response::text
 WHERE raw_response_text IS NULL;
```

`V6` (next PR) tightens `raw_response_text` to `NOT NULL`, switches the
mapper and repository to read/write only the new column, and drops
`raw_response`.

**Why.** The original V4 typing assumed the model always emits valid
JSON. That assumption broke in production — the model started wrapping
replies in markup, PG rejected the save, and the whole scheduler tick
aborted. JSONB was the wrong type because the column is opaque LLM
output, not a structured document we own. TEXT accepts anything; the
application layer (defensive parser in `TicketExtractionService`) is
where JSON-ness should be enforced, not the database.

**Why additive.** AGENTS.md §3.4 forbids DROPping a column in the same
release that removes it from code. Two PRs, two migrations, the schema
is forward-compatible at every step.

## Consequences

### Positive

- Automatic structured data on every receipt, no human in the loop.
- The schema for extracted fields is open (JSONB) — we can add new
  fields without a migration.
- The extraction is fully recoverable: re-running the job on the same
  ticket would be a no-op (UNIQUE FK), but deleting the row would
  re-trigger extraction on the next tick.

### Negative / risks

- **Cost.** Every tick processes up to `batch-size` receipts. With the
  defaults (cron every 15 minutes, batch 5) and assuming the BFF runs
  continuously, that's ~480 calls/day. Each call sends at most one
  image or a few KB of extracted PDF text. The receipt size image
  path is the expensive one; we'll monitor and tune `batch-size` and
  `cron` empirically.
- **Latency.** PDFBox + MiniMax call is 2-15s per ticket. The
  scheduler uses `fixedDelay` semantics (default for Spring's
  `@Scheduled` with cron), so a slow tick simply delays the next one.
  No risk of overlapping runs on a single instance.
- **Privacy.** Receipts are personal financial data. The
  `MiniMaxApiClient` does not log request or response bodies, and the
  `raw_response` column is stored for internal re-parsing only —
  no read path exposes it to the front-end in this PR. (The column
  type is moving JSONB → TEXT in D8; the privacy posture is unchanged.)

### Operational

- New env var: `MINIMAX_API_KEY`. Documented in `.env.example` (the
  template, not `.env`).
- New migration: `V4__ticket_extractions.sql` + the
  `last_extraction_attempt_at` column on `tickets`.
- The job logs `INFO` per ticket (id, merchant, total) and `WARN` on
  parse failures, per backend.md §Logging. No request bodies, no API
  keys, no raw receipt text in logs.
## See also

- ADR 0007 (2026-07-05) splits the `infrastructure` module into
  `persistence` + `minimax-ai`, moves the `MiniMaxApiClient` into
  the latter, and introduces a domain port
  (`com.ticketapp.domain.ai.ReceiptExtractor`) that the orchestrator
  depends on. D6 (this ADR) is partially superseded — the HTTP
  client is now driven by the OpenAI SDK via Spring Boot
  autoconfiguration rather than by a BFF-owned `@Configuration`,
  and the orchestrator no longer imports `MiniMaxApiClient` directly.
