# CONVENTIONS.md

> Coding standards for ticketApp. If a rule here conflicts with a rule in `AGENTS.md`, `AGENTS.md` wins (it is the authoritative source of truth for repository workflow). When this file conflicts with a generated framework default (Spring Boot, Svelte 5, Vite), the framework default wins unless explicitly overridden here.

---

## Table of contents

1. [Architecture](#1-architecture)
2. [Module rules](#2-module-rules)
3. [Java / Spring code style](#3-java--spring-code-style)
4. [Persistence & Liquibase](#4-persistence--liquibase)
5. [Frontend (Svelte 5 + TS)](#5-frontend-svelte-5--ts)
6. [Naming](#6-naming)
7. [Error handling](#7-error-handling)
8. [Security](#8-security)
9. [Testing](#9-testing)
10. [Quality gate](#10-quality-gate)
11. [Commits, branches, PRs](#11-commits-branches-prs)
12. [Documentation](#12-documentation)

---

## 1. Architecture

The codebase is **strictly layered**. Dependencies flow one way only:

```
bff  ──▶  infrastructure  ──▶  domain
 └────────────────────────────▶
```

- `domain` — pure Java. **No** Spring, **no** JDBC, **no** Jackson, **no** `java.util.logging`. Only `java.*` and (for value types) `org.jetbrains.annotations` or similar JDK-only annotations.
- `infrastructure` — JDBC adapters, Liquibase change sets, mappers. Depends only on `domain`.
- `bff` — HTTP edge, Spring configuration, DTOs, JWT/OAuth. Depends on `infrastructure` and `domain`.

Reverse dependencies (`domain` importing Spring, `infrastructure` importing `bff`) are a **build break**, not a style issue. CI will fail.

Cross-cutting decisions that change architecture (new module, new persistence tech, new auth flow) require an ADR in `docs/adr/` (Status: Proposed → Accepted → Deprecated).

---

## 2. Module rules

### 2.1 Maven layout

- Group: `com.ticketapp`. Artifact IDs are exactly the directory names: `domain`, `infrastructure`, `bff`, `ticketapp-parent`.
- Java: 25. Source/target/release all pinned to 25 in the parent POM.
- Maven plugins: `maven-compiler-plugin`, `maven-failsafe-plugin`, `jacoco-maven-plugin` are managed by the parent. Module POMs don't override them unless they must.

### 2.2 Package layout

```
com.ticketapp.<module>.<concern>.<role>
```

Examples (in repo today):

| Package | Concern |
|---------|---------|
| `com.ticketapp.bff.api` | REST controllers (`@RestController`) |
| `com.ticketapp.bff.auth` | Session/JWT/OAuth (`SessionFilter`, `SessionTokenService`, …) |
| `com.ticketapp.bff.auth` | `AuthenticatedUser` value object (HTTP-auth boundary) |
| `com.ticketapp.infrastructure.auth` | `JdbcUserRepository`, `JdbcSessionRepository` |
| `com.ticketapp.domain.<bounded-context>` | Pure model (entities, value objects, domain services) |

Repositories (`*Repository`) live in `infrastructure`, **never** in `bff`. `bff` wires them into controllers.

### 2.3 Banned module layouts

- No `util`, `helpers`, `common`, `shared`, `misc` packages. If two modules need the same logic, lift it into `domain` (if pure) or accept duplication.
- No `dto` package at module root. DTOs are colocated with their consumer (`com.ticketapp.bff.api.dto.TicketResponse`).
- No `constants` package. Use `static final` on the type that owns the constant.

---

## 3. Java / Spring code style

### 3.1 Language & idioms

- Java 25 features OK (records, sealed types, pattern matching, `var`). Don't use preview features.
- Prefer records for immutable value objects and DTOs.
- Prefer `Optional<T>` over nullable returns at API boundaries. Internal code may use null when documented.
- Prefer composition over inheritance. Sealed interfaces are encouraged for closed type hierarchies.
- Avoid Lombok unless the module already uses it (it doesn't, today). Use records and explicit constructors.

### 3.2 Formatting

- 4-space indent, no tabs. LF line endings. UTF-8.
- 120-column soft limit, 140 hard limit. The CI runner may wrap; don't.
- One blank line between members. No trailing whitespace. One trailing newline.
- Use the project's IDE formatter (IntelliJ scheme lives in `.idea/` when present; otherwise import the parent Spring Boot code style).

### 3.3 Imports

- No wildcard imports.
- Import order: `java.*`, `javax.*`, `jakarta.*`, third-party (`org.*`, `com.*` other than `com.ticketapp.*`), `com.ticketapp.*`. One blank line between groups.

### 3.4 Spring specifics

- `@RestController` for HTTP. No `@Controller` returning views — this service has no UI.
- Constructor injection only. Field injection (`@Autowired` on fields) is **banned**.
- `@Value` is allowed for primitive config (e.g. `bff.jwt.secret`). Prefer `@ConfigurationProperties` records when 3+ properties belong to the same concern.
- Beans are package-private by default. Public only when external wiring requires it.
- `application.yml` carries shared config. Profile-specific files (`application-local.yml`, `application-prod.yml`) override.

### 3.5 What to avoid

- `System.out.println` / `System.err.println` in production code. Use the SLF4J logger.
- Catching `Exception` or `Throwable`. Catch the specific checked type and rethrow or convert.
- Silent swallowing (`catch (...) {}`). Always log or rethrow.
- Reflection on framework classes.
- `Thread.sleep` outside of test code.

---

## 4. Persistence & Liquibase

### 4.1 Schema evolution

- **All** schema changes go through Liquibase. No `spring.jpa.hibernate.ddl-auto=update`. No Flyway. No raw `CREATE TABLE` in app code.
- One logical change per changeset file. Filename pattern: `YYYYMMDDHHMMSS_<short-descriptor>.yaml` (or `.xml`, `.sql`). The numeric prefix determines apply order; never renumber an existing changeset.
- New migrations live under `infrastructure/src/main/resources/db/migration/`.
- The master changelog (`db.changelog-master.yaml`) includes files in lexicographic order. Don't hand-edit it once a changeset has shipped to `main`.

### 4.2 Migrations are additive

- **No `DROP` in the same release that removes the column from code.** Two-release deprecation:
  1. Stop reading the column. Release N.
  2. `DROP COLUMN` in release N+1 (or later).
- Backfills ship in their own ordered migration, never bundled with the structural change.
- New columns are `NULL`-able or have a default. Never `NOT NULL` without a default in a single migration against an existing table with rows.
- Index additions: use `CREATE INDEX CONCURRENTLY` (PG ≥ 9.4) wrapped in a Liquibase `precondition` that detects lock conflicts when running outside Postgres.

### 4.3 SQL style

- Lowercase keywords. Uppercase identifiers only when needed for case-sensitive objects (rare).
- Always name constraints: `pk_<table>`, `fk_<table>_<col>`, `idx_<table>_<col>`.
- `TIMESTAMPTZ` for every timestamp. Never `TIMESTAMP` (without tz).
- UUID primary keys stored as `UUID` type, not `VARCHAR(36)`.
- Avoid JSON columns unless the schema is genuinely open. Prefer typed columns.

### 4.4 JDBC code style

- Use `JdbcTemplate` or named-parameter `NamedParameterJdbcTemplate`. No JPA/Hibernate in the infrastructure module.
- One mapper method per query. Mappers are package-private static methods on the repository.
- No SQL string concatenation. Use parameter binding. (`?` or `:name` with named params.)
- Transactional boundaries live in `bff` service classes, not in `infrastructure` repositories.

---

## 5. Frontend (Svelte 5 + TS)

### 5.1 Components

- Svelte 5 runes syntax (`$state`, `$derived`, `$effect`, `$props`). No legacy `let` + reactive-statement patterns.
- One component per file. Filename = component name in PascalCase (`KpiCard.svelte`).
- Component file naming: `Card.svelte` + `CardContent.svelte` for compound primitives is allowed when the API mirrors shadcn-style conventions.
- Props via `$props()` with explicit destructuring and types:

  ```ts
  let { title, count = 0, children }: { title: string; count?: number; children?: Snippet } = $props();
  ```

- Local state stays local. Lift only when siblings need it.
- Effects (`$effect`) only for DOM side effects (Chart.js registration, event listeners on `window`). Never for derived state — use `$derived`.

### 5.2 TypeScript

- `strict: true`. No `any`. Use `unknown` + narrowing when the type is genuinely unknown.
- Public APIs (`front/src/lib/api/*.ts`) export typed functions matching the BFF contract. Run `pnpm check` before committing.
- No `// @ts-ignore`. `// @ts-expect-error` only with a one-line reason and an issue link.

### 5.3 Styling

- Tailwind 4 utility classes. No `<style>` blocks unless the styling is dynamic and depends on runtime values that Tailwind cannot express statically.
- shadcn-style primitives live under `front/src/lib/components/ui/<name>/`. Each primitive is its own file.
- Theme tokens in `front/src/app.css`. Do not hardcode colors in components; use `bg-background`, `text-foreground`, etc.

### 5.4 HTTP clients

- One client per resource under `front/src/lib/api/<resource>.ts`. Returns typed promises.
- Errors throw `HttpError` with `status` and `body`. Don't `console.error` inside the client — let callers decide.

### 5.5 Web components

- The `front/src/index.ts` is the entry. Each component registered via `define` is its own bundle entry. Don't import the whole library from a leaf component.

---

## 6. Naming

| Thing | Convention | Example |
|-------|------------|---------|
| Java package | lowercase, dot-separated | `com.ticketapp.bff.auth` |
| Java class | PascalCase | `SessionTokenService` |
| Java method | camelCase, verbs | `verifyToken`, `findByJti` |
| Java constant | `UPPER_SNAKE_CASE` | `DEFAULT_TTL_HOURS` |
| DB table | snake_case, plural | `auth_sessions`, `users` |
| DB column | snake_case | `user_id`, `created_at` |
| Liquibase changeset id | `YYYYMMDDHHMMSS-<verb-noun>` | `20260115120000-add-users-email-index` |
| Svelte component | PascalCase.svelte | `Dashboard.svelte` |
| TS file (non-component) | camelCase.ts | `google.ts`, `store.svelte.ts` |
| Test file | mirrors source + `.test.ts` or `Test.java` / `IT.java` | `SessionTokenServiceTest.java` |
| Migration file | `YYYYMMDDHHMMSS_<name>.yaml` | `20260115120000_create_users.yaml` |
| Env var | `UPPER_SNAKE_CASE` | `BFF_JWT_SECRET` |
| Git branch | `kebab-case`, prefix by type | `feat/add-recent-tickets-table` |

Boolean variables read as predicates: `isActive`, `hasChildren`, `shouldRetry`. No `flag`, no `status: boolean`.

---

## 7. Error handling

### 7.1 Backend

- Domain errors: custom unchecked exceptions in `domain`. Carry a stable error code (`AUTH_SESSION_EXPIRED`, `TICKET_NOT_FOUND`).
- HTTP errors: `@RestControllerAdvice` maps domain exceptions to `ProblemDetail` (RFC 7807). No `ResponseEntity<ErrorResponse>` per controller.
- Never leak stack traces to clients in non-`local` profiles.
- Logging: `LoggerFactory.getLogger(YourClass.class)`. Levels: `INFO` lifecycle, `WARN` recoverable, `ERROR` operator-visible. Never `DEBUG` in `application.yml` defaults outside `com.ticketapp`.

### 7.2 Frontend

- Throw on failure. The caller decides whether to render, retry, or surface.
- A single `ErrorBoundary` component catches render-time exceptions. Don't wrap every component.
- User-facing error messages go through `front/src/lib/i18n/errors.ts` (or equivalent). Hardcoded strings in templates are banned.

---

## 8. Security

### 8.1 Secrets

- Never in source. Never in `application.yml` defaults. Use `${ENV_VAR:dev-placeholder}` where the placeholder is obviously useless.
- `.env` is gitignored. `.env.example` carries placeholders only.
- `BFF_JWT_SECRET` ≥ 32 chars. The app refuses to start otherwise. Don't bypass the check.
- Rotate any secret that appears in a CI log, PR description, or Slack message.

### 8.2 Auth

- The only identity provider is Google (`id_token` verified against Google's JWKS). No password storage, no email+password login.
- Sessions live in `auth_sessions`. JWT alone is **not** a session. The filter (`SessionFilter`) checks both signature and DB row on every request.
- Revocation = `UPDATE auth_sessions SET revoked_at = now() WHERE jti = ?`. The next request with that JWT gets 401.
- Cookie flags: `HttpOnly`, `Secure` (non-`local`), `SameSite=Lax`. CSRF strategy documented in `docs/adr/`.

### 8.3 Dependencies

- Renovate/Dependabot updates land as PRs. The PR body must justify the bump ("patch only — security CVE-XXX", "minor — needed for feature Y").
- No new runtime dependency without an ADR or a PR-body justification.
- License compatibility: Apache-2.0 / MIT / BSD only. Flag anything else.

---

## 9. Testing

### 9.1 The test pyramid

- **Unit (`*Test.java`, `*.test.ts`)** — fast, no Spring context, no DB. One assertion per concept per test.
- **Integration (`*IT.java`, integration `*.test.ts`)** — Spring context or Testcontainers. One slice of behaviour.
- **E2E (`*.spec.ts` under Playwright)** — UI flows only. Smoke covers login + dashboard render.

Default ratio per PR: ~70% unit, ~25% integration, ~5% E2E.

### 9.2 Backend tests

- `@SpringBootTest` only for tests that need the full context. Use `@JdbcTest`, `@WebMvcTest`, `@DataJpaTest` (if JPA ever returns) for slices.
- Testcontainers (`postgres`) spins up a real PG instance. The container is shared across tests in a class via `@Container static`.
- Migration verification: one `*IT` per release touches the DB to assert `DATABASECHANGELOG` is up to date.
- JWT/session tests use a fixed `BFF_JWT_SECRET` from test resources (`src/test/resources/application-test.yml`).

### 9.3 Frontend tests

- Vitest + `@testing-library/svelte`. Component tests render the component; logic tests extract the pure function.
- Mock HTTP via `vi.mock`. Real network calls are banned in unit tests.
- `pnpm test` runs once. `pnpm test:watch` for development. CI runs `pnpm test:ci` and parses junit.
- Coverage: 70% lines, 60% branches on changed files. The CI job is informational; the quality gate is enforced by SonarQube.

### 9.4 What not to do

- No sleep-based assertions (`await sleep(500)`). Use `waitFor` or polling helpers.
- No commented-out tests. Delete or fix.
- No tests named `test1`, `testFoo`. The name describes the behaviour: `rejectsExpiredSession`, `rendersKpiWhenCountIsZero`.
- No mocking the type under test.

---

## 10. Quality gate

A change is **done** when all of the following pass locally:

```bash
# Backend (any change touching domain/, infrastructure/, bff/)
mvn -B verify

# Frontend (any change touching front/)
cd front
pnpm install --frozen-lockfile --ignore-scripts
pnpm test:ci
pnpm build
pnpm test:coverage

# Quality
mvn -B -DskipTests verify sonar:sonar \
  -Dsonar.token=$SONAR_TOKEN \
  -Dsonar.host.url=$SONAR_HOST_URL \
  -Dsonar.organization=$SONAR_ORGANIZATION
```

CI runs the same gates. PRs that fail the Sonar quality gate are blocked from merge.

### 10.1 Coverage thresholds

| Module | Lines | Branches |
|--------|-------|----------|
| `domain` | 85% | 75% |
| `infrastructure` | 75% | 65% |
| `bff` | 70% | 60% |
| `front` | 70% | 60% |

Lower coverage on new code requires an explicit justification in the PR body.

---

## 11. Commits, branches, PRs

### 11.1 Commit messages — Conventional Commits

```
<type>(<scope>): <subject>

<body>

<footer>
```

- **type**: `feat`, `fix`, `chore`, `refactor`, `test`, `docs`, `build`, `ci`, `perf`, `revert`.
- **scope** (optional): `bff`, `front`, `domain`, `infra`, `deps`, `auth`, `db`, etc.
- **subject**: ≤ 72 chars, imperative, no trailing period.
- **body**: explain *why*, not *what*. Wrap at 100 cols.
- **footer**: `Refs: #123`, `Closes: #123`, `BREAKING CHANGE: …`.

### 11.2 Branches

- `main` is always deployable. Force-push to `main` is forbidden.
- Topic branches: `<type>/<short-kebab-summary>` (e.g. `feat/recent-tickets-table`).
- Squash-merge or rebase-merge only. No merge commits on `main`.

### 11.3 Pull requests

- One concern per PR. Refactor + feature + migration in the same PR is a review smell.
- The PR template at `.github/PULL_REQUEST_TEMPLATE.md` is mandatory. Tick what applies; one-line note what doesn't.
- Reviewers: at least one maintainer approval. Backend changes touching `bff/auth/**` require an additional security reviewer.
- PRs that touch more than 500 lines (excluding generated files) should be split.
- Force-push to a PR branch is allowed **only before** review has started.

---

## 12. Documentation

- `README.md` — onboarding, build, env vars. Update on every change to the toolchain.
- `CONVENTIONS.md` (this file) — coding rules. Update when the rules change.
- `AGENTS.md` — operating manual for assistants. Update when the workflow changes.
- `CONTRIBUTING.md` — human contribution guide.
- `docs/adr/NNNN-<short-title>.md` — significant decisions. Use the MADR template.
- Javadoc: required on every public type in `domain`. Optional but encouraged in `infrastructure` and `bff`.
- Inline comments: explain *why*, not *what*. Avoid restating the code.
