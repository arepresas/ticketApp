# Testing rules

> Load this pack when adding or reviewing tests.

## The pyramid

- **Unit (`*Test.java`, `*.test.ts`)** — fast, no Spring context, no DB. One concept per test.
- **Integration (`*IT.java`, integration `*.test.ts`)** — Spring context or Testcontainers. One slice of behaviour.
- **E2E (`*.spec.ts` under Playwright)** — UI flows only. Smoke covers login + dashboard render.

Default ratio per PR: ~70% unit, ~25% integration, ~5% E2E.

## Backend tests

- `@SpringBootTest` only when the full context is required. Use slice annotations (`@JdbcTest`, `@WebMvcTest`) otherwise.
- Testcontainers (`postgres`) for any test that touches the DB. Container is shared via `@Container static`.
- Migration verification: at least one `*IT` per release asserts `DATABASECHANGELOG` is up to date.
- JWT/session tests use a fixed `BFF_JWT_SECRET` from `src/test/resources/application-test.yml`.
- One test class per production class. Mirror the package: `com.ticketapp.bff.auth.SessionTokenServiceTest` lives next to `SessionTokenService`.

## Frontend tests

- Vitest + `@testing-library/svelte`. Component tests render; logic tests extract the pure function.
- Mock HTTP via `vi.mock`. Real network calls are banned in unit tests.
- `pnpm test` runs once. `pnpm test:watch` for development. CI runs `pnpm test:ci` (writes junit to `front/reports/junit.xml`).
- Coverage: 70% lines / 60% branches on changed files. Enforced by SonarQube, not the runner.

## Naming

| Layer | Pattern |
|-------|---------|
| Java unit | `<Class>Test.java` |
| Java integration | `<Class>IT.java` |
| Vitest unit | `<file>.test.ts` next to `<file>.ts` |
| Vitest component | `<Component>.test.ts` next to `<Component>.svelte` |
| Playwright | `*.spec.ts` under `front/tests/e2e/` |

Test names describe the behaviour, not the implementation: `rejectsExpiredSession`, not `testSession1`.

## What to avoid

- `await sleep(500)` for async assertions. Use `waitFor` or polling helpers.
- Commented-out tests. Delete or fix.
- Tests named `test1`, `testFoo`.
- Mocking the type under test.
- Asserting on log output instead of state.
- Snapshot tests for things that change every release (date-dependent formatting, generated IDs).

## Coverage thresholds

| Module | Lines | Branches |
|--------|-------|----------|
| `domain` | 85% | 75% |
| `infrastructure` | 75% | 65% |
| `bff` | 70% | 60% |
| `front` | 70% | 60% |

Lower coverage on new code requires an explicit justification in the PR body.

## Quick checklist before pushing

- [ ] New tests cover the change at the right pyramid layer
- [ ] `mvn -B verify` green (backend)
- [ ] `pnpm test:ci` green (frontend)
- [ ] Coverage on changed files meets the threshold
- [ ] No `disabled`, `skipped`, or `@Ignore` markers left behind
- [ ] No flaky tests added (network-dependent timing, sleep, order coupling)
