# Contributing to ticketApp

Thank you for contributing. This document is for **humans**. Automated assistants should read `AGENTS.md` instead — it carries the same operational rules in a machine-friendly format.

---

## 1. Code of conduct

Be respectful. Disagree on substance, not on people. Assume good intent. The project follows the [Contributor Covenant](https://www.contributor-covenant.org/) — read it once before your first PR.

---

## 2. Where to start

| I want to… | Go to |
|------------|-------|
| Report a bug | [GitHub Issues](../../issues) — pick the **Bug** template |
| Propose a feature | [GitHub Discussions](../../discussions) — `Ideas` category |
| Ask a question | [GitHub Discussions](../../discussions) — `Q&A` category |
| Submit a small fix | Open a PR directly. One concern per PR. |
| Submit a bigger change | Discuss first → ADR (see below) → PR |
| Update docs only | Open a PR — `docs:` commit type |

---

## 3. Before opening a PR

1. **Read** [`README.md`](README.md), [`CONVENTIONS.md`](CONVENTIONS.md), and [`AGENTS.md`](AGENTS.md).
2. **Search** existing issues and PRs. The bug may already be tracked.
3. **Reproduce** the bug locally before claiming you fixed it.
4. **Branch** off `main`: `git switch -c feat/<short-summary>` or `fix/<short-summary>`.
5. **Run the local quality gate** before pushing (see §6 below).

For changes that touch architecture (new module, new auth flow, new persistence tech, schema rewrites), open a discussion first and produce an ADR in `docs/adr/` before writing code. See §5.

---

## 4. Making a change

### 4.1 Branch & commit

- One concern per branch. Refactors + feature + migration in the same PR is a review smell.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/):

  ```
  feat(auth): add session revocation endpoint

  AuthenticatedUser can revoke their own session by POSTing /api/auth/logout.
  The endpoint looks up the jti from the JWT, marks the row as revoked, and
  clears the cookie. The SessionFilter already rejects revoked jtis, so the
  client is logged out on its next request.

  Refs: #142
  ```

- Allowed types: `feat`, `fix`, `chore`, `refactor`, `test`, `docs`, `build`, `ci`, `perf`, `revert`.
- Subject ≤ 72 chars, imperative mood, no trailing period.

### 4.2 Code style

- Java: see `CONVENTIONS.md` §3. Constructor injection, records for value objects, no Lombok.
- SQL: see `CONVENTIONS.md` §4. Liquibase for every schema change.
- Svelte/TS: see `CONVENTIONS.md` §5. Runes, strict TS, shadcn-style primitives.
- Tests: see `CONVENTIONS.md` §9. Test the behaviour, not the implementation.

### 4.3 Secrets

- Never commit `.env`. Never paste real `GOOGLE_CLIENT_ID`, `BFF_JWT_SECRET`, `SONAR_TOKEN`, or any other secret in code, logs, screenshots, or PR descriptions.
- If a secret leaks: rotate it first, then revert.

### 4.4 Dependencies

- Adding a new dependency? Justify it in the PR body. "Adds X for Y because Z." Renovate/Dependabot will keep it current; no manual version bumps outside that flow.

---

## 5. Architecture decisions (ADR)

Significant changes need a written decision before code lands.

- Create `docs/adr/NNNN-<short-title>.md` (next free number — `ls docs/adr` to find it).
- Use the [MADR](https://adr.github.io/madr/) template. Keep it short (1–2 pages).
- Status: `Proposed` → review → `Accepted` (or `Rejected` / `Superseded by NNNN`).
- Merging an ADR requires a maintainer approval. The PR for the change and the ADR can land in the same release.

You need an ADR when:

- Adding a new top-level Maven module.
- Introducing a new persistence technology.
- Changing the auth flow or the JWT signing strategy.
- Switching from Liquibase to anything else.
- Adding a new external integration (payment, email, analytics).

You do **not** need an ADR for:

- A new REST endpoint in the existing BFF.
- A new Svelte component or page.
- A new Liquibase changeset (the changeset *is* the documentation).
- A bug fix or refactor that preserves the architecture.

---

## 6. Quality gate (run locally before pushing)

Run the smallest command that proves your change works, then the full gate:

```bash
# Backend (any change under domain/, infrastructure/, bff/)
mvn -B verify

# Frontend (any change under front/)
cd front
pnpm install --frozen-lockfile --ignore-scripts
pnpm test:ci
pnpm build
pnpm test:coverage
cd ..

# SonarQube scan (optional locally; required in CI)
mvn -B -DskipTests verify sonar:sonar \
  -Dsonar.token=$SONAR_TOKEN \
  -Dsonar.host.url=$SONAR_HOST_URL \
  -Dsonar.organization=$SONAR_ORGANIZATION
```

All commands must pass before you request review.

---

## 7. Pull request checklist

The PR template at [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md) opens automatically. Fill it in. Common oversights:

- [ ] Title follows Conventional Commits (`feat(scope): …`).
- [ ] "How to test" section has runnable steps a reviewer can follow.
- [ ] "Modules touched" lists every module the diff actually changes.
- [ ] "Database / migrations" reflects reality: no DB change ticked if you added a changeset.
- [ ] Quality-gate commands from §6 ran locally and passed.
- [ ] No secrets, no `.env` values, no `target/` or `node_modules/` in the diff.
- [ ] New tests cover the change (and `mvn verify` / `pnpm test:ci` reports them).
- [ ] `AGENTS.md` / `CONVENTIONS.md` / `README.md` updated if the rules or the toolchain changed.

---

## 8. Review process

1. **Automated checks** run first: backend, frontend, SonarQube. A red gate blocks review.
2. **Maintainer review** — at least one approval required.
3. **Security review** — required for changes under `bff/auth/**`, dependency changes, or anything touching secrets.
4. **Squash-merge** to `main`. The PR title becomes the squashed commit message; make sure it follows Conventional Commits.

Reviewers may request changes. Address them as new commits on the same branch — don't force-push during review.

---

## 9. After merge

- The `sonar` job in CI uploads coverage + smells. Check the SonarQube dashboard for your PR.
- Dependabot/Renovate picks up new transitive CVEs within hours. If your change introduced one, the bot will file the fix.
- The `main` branch deploys automatically via the workflow defined in `.github/workflows/`. Don't bypass it.

---

## 10. Getting help

Stuck? Ask in [GitHub Discussions](../../discussions) `Q&A`. Don't DM maintainers for general questions — public answers help the next person.

For security issues, **do not** open a public issue. Email the maintainers (address in the repo description) and wait for an acknowledgment before disclosing publicly.
