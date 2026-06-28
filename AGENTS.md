# AGENTS.md

> Machine-readable operating manual for this repository.
> This file is consumed by automated coding assistants and by humans onboarding to the project.
> It is intentionally tool-agnostic: any assistant that reads a project-level instructions file will pick this up.

---

## 1. Project at a glance

**ticketApp** — multi-module project: Spring Boot 4 BFF + Svelte 5 web components + PostgreSQL.

| Path | Purpose | Stack |
|------|---------|-------|
| `domain/` | Pure Java domain model (no dependencies) | Java 25 |
| `infrastructure/` | Persistence: JDBC + Liquibase | Java 25, PostgreSQL 18 |
| `bff/` | Backend-for-frontend, REST + OAuth (Google) | Spring Boot 4.1, Java 25 |
| `front/` | Web components, landing + dashboard | Svelte 5, Vite 8, TypeScript, Tailwind 4 |
| `local-environment/` | docker-compose (Postgres 18) | Docker |
| `.github/` | CI workflows, Dependabot, PR template | GitHub Actions |

Group: `com.ticketapp` · Version: `0.0.1-SNAPSHOT` · Java: 25 · Node: 24 LTS · Package manager: pnpm 11.

See `README.md` for setup, `CONVENTIONS.md` for coding standards, `CONTRIBUTING.md` for contribution workflow.

---

## 2. Build & run quick reference

```bash
# Backend
mvn -B verify                                                     # full build + tests (Testcontainers spins up Postgres)
mvn -B -pl bff -am spring-boot:run -Dspring-boot.run.profiles=local   # BFF on :8080

# Frontend
cd front && corepack enable && pnpm install --ignore-scripts
pnpm dev                # vite dev server
pnpm build              # production build
pnpm test               # vitest run
pnpm test:coverage      # vitest + lcov report under front/coverage
pnpm check              # svelte-check (types + a11y)

# Local infra
docker compose -f local-environment/docker-compose.yml up -d
```

All commands must succeed before a change is considered done. See `CONVENTIONS.md` §10 for the full quality gate.

---

## 3. Repository rules — read these before changing anything

1. **Layer boundaries are non-negotiable.** `domain` depends on nothing; `infrastructure` depends only on `domain`; `bff` depends on both. Reverse dependencies are a build break, not a style issue.
2. **No new top-level Maven modules** without an ADR in `docs/adr/`.
3. **Database changes go through Liquibase.** Never edit schema in Java code or runtime DDL. New migrations: `infrastructure/src/main/resources/db/migration/`.
4. **Migrations are additive.** Backfills ship in a separate, ordered migration. No `DROP` in the same release that removes the column from code.
5. **Secrets are never committed.** Use the env vars defined in `README.md` §Environment variables. `.env` is gitignored; `.env.example` is the template.
6. **BFF JWT secret must be ≥ 32 chars** or the application refuses to start. Generate with `openssl rand -base64 32`.
7. **The SonarQube quality gate is enforced in CI.** New code must not introduce BLOCKER, CRITICAL, or unresolved security hotspots.
8. **PR template must be filled.** See `.github/PULL_REQUEST_TEMPLATE.md`. Tick the boxes that apply; leave a one-line note on the ones that don't.
9. **Conventional Commits** for commit messages: `feat:`, `fix:`, `chore:`, `refactor:`, `test:`, `docs:`, `build:`, `ci:`.
10. **One concern per PR.** Refactors + feature + migration in the same PR is a review smell.

---

## 4. Where things live

```
ticketApp/
├── domain/                          # Pure model: entities, value objects, domain services
│   └── src/main/java/com/ticketapp/domain/
├── infrastructure/                  # Adapters: JDBC repositories, Liquibase migrations
│   └── src/main/java/com/ticketapp/infrastructure/
│   └── src/main/resources/db/migration/
├── bff/                             # HTTP edge: controllers, auth, DTOs, application config
│   └── src/main/java/com/ticketapp/bff/
│       ├── api/                     # @RestController classes
│       ├── auth/                    # SessionFilter, OAuth, JWT, user repository
│       └── BffApplication.java      # Entry point
│   └── src/main/resources/
│       ├── application.yml
│       └── application-local.yml
├── front/                           # Web components (Svelte 5 + TS)
│   └── src/
│       ├── lib/
│       │   ├── api/                 # Typed HTTP clients → BFF
│       │   ├── auth/                # Google login, session store
│       │   ├── components/          # UI primitives (shadcn-style)
│       │   ├── charts/              # Chart.js registrations
│       │   └── landing/             # Public landing page
│       └── index.ts                 # Web-component entry point
├── local-environment/
├── .github/
│   ├── workflows/                   # ci.yml, sonar-manual.yml
│   ├── dependabot.yml
│   └── PULL_REQUEST_TEMPLATE.md
└── .rules/                          # Reusable rules packs (this directory)
    ├── backend.md
    ├── frontend.md
    ├── database.md
    └── testing.md
```

---

## 5. Reusable rules (`.rules/`)

The `.rules/` directory holds modular rule packs that an assistant can load on demand.
They are not loaded by default — load only the ones relevant to the current task:

| File | Load when… |
|------|------------|
| `.rules/backend.md` | Touching Java/Spring code under `domain/`, `infrastructure/`, or `bff/` |
| `.rules/frontend.md` | Touching anything under `front/src/` |
| `.rules/database.md` | Writing or reviewing Liquibase migrations, JDBC queries, schema changes |
| `.rules/testing.md` | Adding tests (Testcontainers, Vitest, Spring `@SpringBootTest`) |
| `.rules/security.md` | Touching auth, JWT, OAuth, secrets, dependencies |

**Rule loading protocol:** when a task touches multiple areas, load each rule pack at the start and re-read after 30+ minutes of edits. Do not assume; the rules are short and load fast.

---

## 6. Workflow contract for assistants

When asked to make a change, follow this sequence unless the user explicitly opts out:

1. **Read** — skim `README.md`, `CONVENTIONS.md`, and the relevant `.rules/*.md` file.
2. **Locate** — search the codebase before assuming a file path. Glob > guess.
3. **Plan** — state the change in 1–3 sentences. If it crosses a module boundary, name the modules.
4. **Minimal change** — touch only what the task requires. No drive-by refactors.
5. **Validate** — run the smallest command that proves correctness (a single test, `pnpm check`, `mvn -pl <module> test`).
6. **Full gate** — before declaring done, run `mvn -B verify` (if backend touched) and/or `pnpm test && pnpm check && pnpm build` (if frontend touched).
7. **Report** — list changed files, the commands run, and any follow-up the user should know about.

### Forbidden actions

- Do **not** commit secrets, real `.env` values, or generated credentials.
- Do **not** force-push, amend pushed commits, or skip hooks.
- Do **not** edit `pom.xml` versions without a corresponding Renovate/Dependabot PR.
- Do **not** create files at the repo root unless they are listed in §4 or are standard config (`*.md`, `.gitignore`, etc.).
- Do **not** add dependencies without justification in the PR body ("adds X for Y because Z").
- Do **not** disable a failing test to make CI green. Report → propose fix → wait for approval.

---

## 7. Domain language (working glossary)

Keep these terms stable across code, commits, and PR descriptions:

- **BFF** — Backend-for-frontend. The Spring Boot service exposed to the SPA. Not a generic API gateway.
- **Session** — A row in `auth_sessions` + the JWT that references it. JWT alone is not a session; revocation lives in the DB.
- **AuthenticatedUser** — Domain record returned after Google `id_token` verification. Contains `id` (UUID) and Google profile fields.
- **Migration** — A Liquibase changeset under `infrastructure/src/main/resources/db/migration/`. One logical change per file.
- **Web component** — A custom element exposed from `front/src/index.ts`. Each is its own bundle entry when imported via `define`.
- **Landing** — Public marketing page (`front/src/lib/landing/`). No auth required.
- **Dashboard** — Authenticated app (`front/src/lib/auth/DashboardApp.svelte`). Requires valid session.

---

## 8. Environment variables cheat sheet

The full table is in `README.md`. Minimum required to run locally:

```env
# Local dev (copied from .env.example)
POSTGRES_DB=ticketAppDb
POSTGRES_USER=ticketAppUser
POSTGRES_PASSWORD=ticketAppPass
DB_URL=jdbc:postgresql://localhost:6432/ticketAppDb
DB_USER=ticketAppUser
DB_PASSWORD=ticketAppPass
GOOGLE_CLIENT_ID=...                       # from Google Cloud Console
BFF_JWT_SECRET=...                         # openssl rand -base64 32

# CI / quality (GitHub Actions secrets, required for the sonar job)
SONAR_TOKEN=...                            # sqp_... from SonarCloud
SONAR_HOST_URL=...                         # https://sonarcloud.io
SONAR_ORGANIZATION=arepresas
```

Tests under `bff/` ignore these and spin up Postgres via Testcontainers.

---

## 9. CI pipeline (what runs on every PR)

1. **backend** — `mvn -B -ntp verify` on JDK 25. Publishes surefire/failsafe reports + jacoco XML.
2. **frontend** — pnpm install → `pnpm test:ci` → `pnpm build` → `pnpm test:coverage`. Publishes junit XML + lcov.
3. **sonar** — depends on both above. Single `sonar` scanner invocation; fails if quality gate is RED.

Manual trigger: `.github/workflows/sonar-manual.yml` (re-runs the scanner without rebuilding).

---

## 10. When in doubt

- Architecture question → re-read `CONVENTIONS.md` §1–§3.
- Auth or session question → `.rules/security.md` + `bff/src/main/java/com/ticketapp/bff/auth/`.
- Migration question → `.rules/database.md` + existing migrations under `infrastructure/src/main/resources/db/migration/`.
- Test question → `.rules/testing.md` + the closest existing `*IT.java` / `*.test.ts`.
- Everything else → ask, don't guess.