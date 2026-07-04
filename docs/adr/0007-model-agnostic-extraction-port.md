# ADR 0007: Model-agnostic extraction port

- Status: accepted
- Date: 2026-07-05
- Deciders: arepresas
- Related: backend, infrastructure, bff, ADR 0006

## Context

ADR 0006 introduced the AI extraction pipeline against [MiniMax](https://platform.minimax.io)'s
chat-completions API. The orchestrator (`TicketExtractionService` in the BFF
module) calls `com.ticketapp.infrastructure.ai.MiniMaxApiClient` directly. The
client class name is also the implementation — there is no domain port between
the orchestrator and the vendor.

We want two things:

1. **Swap providers** without rewriting the orchestrator. MiniMax's chat-completions
   endpoint is OpenAI-compatible (ADR 0006 D6) but several other providers offer
   the same shape (OpenAI proper, Anthropic via OpenRouter, etc.). If pricing,
   latency, or quality push us to a different vendor, the change should be a
   Maven dependency swap, not a refactor.
2. **Split the `infrastructure` module** along its two existing concerns —
   persistence (JDBC + Liquibase) and AI integration. They have no shared code,
   only the shared `com.ticketapp.infrastructure.*` package prefix. Splitting
   them gives each module a focused dependency footprint: persistence no longer
   pulls PDFBox + the OpenAI SDK; the AI module no longer pulls Spring JDBC +
   Liquibase.

AGENTS.md §3.2 normally requires an ADR for new top-level Maven modules. This
ADR documents the addition of two (`persistence`, `minimax-ai`) and the
removal of one (`infrastructure`).

## Decisions

### D1. Domain port: `ReceiptExtractor`

**Decision.** Add `com.ticketapp.domain.ai.ReceiptExtractor` as the abstract
port the orchestrator depends on. The interface declares exactly one method:

```java
public interface ReceiptExtractor {
    ReceiptExtractionResult extract(ReceiptExtractionRequest request)
        throws ReceiptExtractionException;
}
```

Supporting types in `domain`:

- `ReceiptExtractionRequest` — neutral record carrying the input the
  orchestrator already has on hand: `mimeType` (String) and either `imageBytes`
  (byte[]) or `pdfText` (String), exactly one of which is set. The PDF-vs-image
  decision lives here because it's a domain concern (what does the receipt
  look like), not a provider concern (which API endpoint to call).
- `ReceiptExtractionResult` — neutral record carrying the structured fields the
  orchestrator already persists: `merchant`, `purchaseDate`, `category`,
  `products` (already a domain type), `totalAmount`, `currency`. Provider-agnostic.
- `ReceiptExtractionException` — domain-defined exception. Provider modules map
  their internal failures (HTTP 4xx/5xx, parse errors, timeouts) to this single
  type. The orchestrator catches `ReceiptExtractionException` and never sees
  provider-specific classes.

**Why.** AGENTS.md §3.1 says domain depends on nothing. The port + the three
records are pure Java records + one interface — no third-party deps. The
orchestrator's existing `parseAssistantReply` parsing logic stays where it is
(in the BFF, against the `ReceiptExtractionResult` returned by the port), so
defensive parsing doesn't need to live in every implementation.

**Why neutral names, not `MinimaxExtractor`.** Provider-shaped names (`MiniMax`)
lock the abstraction to its first user. The interface is called `ReceiptExtractor`
because "extracting structured data from a receipt" is the domain concept; the
*how* (which LLM) is hidden behind the implementation.

### D2. Module split: `infrastructure` → `persistence` + `minimax-ai`

**Decision.** Replace the single `infrastructure` module with two sibling
modules at the repo root:

```
ticketApp/
├── domain/        # unchanged
├── persistence/   # was infrastructure/src/main/java/com/ticketapp/infrastructure/persistence/
├── minimax-ai/    # was infrastructure/src/main/java/com/ticketapp/infrastructure/ai/
└── bff/
```

Module coordinates:

| Old | New |
|---|---|
| `com.ticketapp:infrastructure` | (deleted) |
| — | `com.ticketapp:persistence` |
| — | `com.ticketapp:minimax-ai` |

Package rename (option α from the proposal):

| Old package | New package |
|---|---|
| `com.ticketapp.infrastructure.persistence.*` | `com.ticketapp.persistence.*` |
| `com.ticketapp.infrastructure.support.*` | `com.ticketapp.support.*` |
| `com.ticketapp.infrastructure.ai.*` | `com.ticketapp.minimaxai.*` |

**Why split.** The two halves share nothing but the `infrastructure.*` prefix.
PDFBox and the OpenAI SDK are only needed by the AI half; Spring JDBC and
Liquibase are only needed by the persistence half. The merge meant a ~5 MB
dependency footprint on whichever BFF deployment we ship, even though only one
half is wired at runtime. Splitting also makes test slicing honest:
`PersistenceTestSlice` no longer drags the OpenAI SDK onto the classpath.

**Why full package rename (option α vs β).** Half-rename (β) would keep
`com.ticketapp.infrastructure.persistence.*` and only rename the AI half. The
mixed state leaves future readers guessing why `com.ticketapp.infrastructure.*`
still exists when there is no `infrastructure` module. α is the clean cut —
all three new modules have package paths that match their module names. Cost
is a one-time find-and-replace across `bff/`, the tests, and the docs; the
churn is mechanical and ends here.

### D3. Provider selection: Maven, not Spring profiles

**Decision.** The active provider is whichever AI module is declared as a
dependency in `bff/pom.xml`. Today that's `minimax-ai`. Tomorrow it could be
`openai-ai` or `anthropic-ai`; the choice is a single `<dependency>` line.

Each AI module ships a Spring Boot autoconfiguration:

```
minimax-ai/src/main/resources/META-INF/spring/
  org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

Listing exactly one class — its `@Configuration` — that registers a
`ReceiptExtractor` bean (or one bean per supported capability, but the only
capability today is receipt extraction). The BFF never references a concrete
extractor class; it autowires the port.

**Why Maven-driven, not profile-driven.** Profile selection works but adds a
property knob (`ticketapp.ai.provider=minimax`), two bean definitions in the
BFF (one per provider, gated on the property), and a startup validation that
"exactly one is selected". That's ceremony for a decision that is essentially
"which JAR is on the classpath" — Maven already tracks that. Drop
`minimax-ai` from the BFF POM and the BFF fails to start with
`NoSuchBeanDefinitionException: ReceiptExtractor`, which is the right failure
mode. Swap `<dependency>` for `<dependency>` and the BFF ships a different
provider.

**Why autoconfiguration.** Spring Boot 4 picks up classes listed in
`META-INF/spring/...AutoConfiguration.imports` automatically — no XML, no
`@EnableX` annotation in the BFF. The AI module is a black box from the BFF's
perspective: it ships a bean, the BFF consumes it.

### D4. Migration path (atomic commits in one PR)

Per AGENTS.md §3.10 ("one concern per PR") this is one concern (provider
swappability) split across multiple files. We ship it as one PR with five
atomic commits:

1. `refactor(domain): add ReceiptExtractor port` — adds the four `domain.ai.*`
   types, no other change.
2. `refactor: move persistence to new persistence module, drop infrastructure`
   — moves JDBC repos + Liquibase + support helpers, renames packages.
3. `refactor: move AI client to new minimax-ai module` — moves the API client
   and PDFBox helper, renames packages, implements the port, registers the
   autoconfiguration.
4. `chore(pom): update parent + bff + persistence + minimax-ai poms` — adds
   the two new modules, drops `infrastructure`, retargets the BFF.
5. `docs(adr): ADR-0007 model-agnostic extraction` — this file.

The ordering matters: (1) lands first so the later commits can reference the
port type; (2) and (3) move code without yet breaking the BFF (which still
imports the old paths); (4) flips the BFF over. Bisecting at any point in the
PR's history shows either a working tree or one specific commit that compiles
clean with old paths.

## Consequences

### Positive

- A future provider (`openai-ai`, `anthropic-ai`, `local-llm-ai`) drops in as
  a new Maven module that ships one bean — no BFF changes.
- The BFF module's dependency footprint shrinks: it no longer pulls
  `openai-java-core` or `pdfbox` directly. Those become transitive through
  whichever AI module is wired.
- Tests can target a single module cleanly: `persistence`'s integration tests
  no longer need to keep the AI half out of the classpath.

### Negative / risks

- **Renames are large.** ~12 Java files in `bff/` and the BFF tests have their
  imports rewritten. The PR diff is dominated by import lines, not logic.
- **Autoconfiguration has to play nicely with the existing IT setup.** The BFF
  IT tests (`BffApplicationIT`, `TicketControllerIT`, etc.) boot a full Spring
  context. With the AI module on the classpath, the `ReceiptExtractor` bean is
  registered. Tests that don't mock the AI client need the test-time API key
  to either be valid or the bean to be replaced via `@MockBean`. The existing
  `TicketExtractionJob` kill switch (`ticketapp.ai.enabled=false`) means the
  real bean is never invoked in tests, but the bean itself must be constructible.
  The autoconfiguration's factory accepts the dev-placeholder key when
  `enabled=false` (same posture as ADR 0006 D5).
- **One per-pom lock-in.** If two AI modules end up on the classpath at the
  same time, Spring fails to start with "more than one bean of type
  ReceiptExtractor". That's the intended failure mode for "you tried to swap
  providers and forgot to remove the old one" — better than silent ambiguity.

### Operational

- The `META-INF/spring/...AutoConfiguration.imports` mechanism is a Spring
  Boot 4 feature; this repo already uses Spring Boot 4 elsewhere (ADR 0006).
- The Docker build (`Dockerfile`) currently does `COPY infrastructure` —
  updated to `COPY persistence` + `COPY minimax-ai`.
- SonarQube's `sonar.sources` + `sonar.java.binaries` lists in
  `sonar-project.properties` are updated to point at the new module dirs.
- This ADR supersedes the parts of ADR 0006 that placed
  `com.ticketapp.infrastructure.ai.MiniMaxApiClient` directly behind the
  orchestrator. ADR 0006's other decisions (D2 schema, D3 PDFBox, D4 status
  machine, D5 properties, D7 tests) remain unchanged. D6 is partially
  superseded: it described the HTTP client as `java.net.http.HttpClient`, but
  the code today uses the OpenAI SDK; this ADR doesn't fix that discrepancy
  (still tracked as a separate cleanup).