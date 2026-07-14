# Security Policy

## Supported Versions

The `main` branch is the only version receiving security patches. Older
releases (anything pinned in a `v*` tag) are best-effort and may not
receive fixes for newly disclosed vulnerabilities.

| Version  | Supported          |
| -------- | ------------------ |
| `main`   | :white_check_mark: |
| `<tag>`  | :x:                |

## Reporting a Vulnerability

**Please do not file a public issue for suspected security
problems.** Use one of these private channels instead:

1. **GitHub Security Advisories** (preferred): open a private
   [security advisory](../../security/advisories/new) on this
   repository. You can mark it as draft while you collect details;
   submit it once you have a reproducer.
2. **Email**: `security@ticketapp.dev` (PGP key on request).
   Encrypt sensitive details — full exploit chains, raw stack
   traces, etc. — and include the GitHub issue / commit SHA in the
   subject.

Either channel reaches the same maintainer triage rotation. Pick
whichever is convenient.

### What to include

A useful report covers:

- The affected component (`bff/`, `minimax-ai/`, `front/`, etc.) and
  commit SHA / version tag.
- A self-contained reproducer: curl commands, request bodies,
  screenshots, or a minimal test case.
- The impact you observed (data exposure, privilege escalation,
  denial of service, etc.) and your assessment of how serious it
  is.
- Your name / handle if you'd like credit in the advisory.

Reports filed in languages other than English or Spanish are fine —
please summarise in one of those two so triage isn't blocked on
translation.

## What to expect

| Stage                | SLA                                |
| -------------------- | ---------------------------------- |
| First acknowledgement| within 3 business days             |
| Triage + severity    | within 10 business days            |
| Fix or mitigation    | depends on severity (see below)    |
| Public disclosure    | coordinated with the reporter      |

Severity drives the response window:

- **Critical** (RCE, auth bypass, cross-tenant data leak): hotfix
  within 7 days, immediate advisory draft.
- **High** (auth issue with preconditions, persistent XSS): fix
  within 30 days.
- **Medium** (info disclosure, rate-limit bypass): fix in the next
  regular release cycle.
- **Low** (hardening, defense-in-depth): best-effort, often folded
  into larger refactors.

If the report turns out to be a non-issue, we will tell you and
explain why.

## Scope

In scope on this repository:

- The BFF (`bff/`) — REST API surface, JWT session handling, Google
  OAuth callback, file-upload validation, owner-scoping enforcement.
- The persistence layer (`persistence/`) — JDBC repos, Liquibase
  migrations, owner-scoped queries.
- The provider module (`minimax-ai/`) — request shapes, secret
  handling for the upstream API key.
- The frontend (`front/`) — token storage, CSP-relevant behaviour,
  authenticated session management.
- Build / CI configuration (`.github/`, `Dockerfile`, `pom.xml`) —
  dependency pinning, secret leakage, supply-chain risk.

Out of scope (file regular issues instead):

- Bugs that don't have a security impact.
- Theoretical attacks that require an attacker to already have
  full admin access.
- Volumetric denial-of-service against the public landing page.
- Vulnerabilities in third-party dependencies that already have a
  fix released upstream — please open a Dependabot-style report or
  PR instead.

## Security model — what's enforced today

These are the invariants the codebase already upholds; please don't
file a report about them without evidence of a bypass:

- **BFF session integrity.** Every protected request must carry a
  Bearer JWT signed by `BFF_JWT_SECRET` (HS256, ≥ 32 chars —
  enforced at boot). The token's `jti` must reference a live row in
  `auth_sessions`; revoked or expired sessions are rejected even if
  the signature is valid.
- **Google login.** Google `id_token` is verified against Google's
  JWKS. No server-side Google secret is held by the application.
- **Tenant isolation.** Every read/write in the controller layer
  filters by `owner_id`. Cross-tenant reads return 404, never 403,
  so existence is not leaked across users. The
  `findOpenForExtraction` path is the only system-scope query and
  is unreachable from the controller layer.
- **File upload hygiene.** Multipart uploads are size-capped
  (10 MB) and mime-restricted to `application/pdf` plus a small
  set of image types. Bytes are stored verbatim in the
  `tickets` table; no filesystem staging.
- **Secrets in code.** `application.yml` defaults use
  `${ENV_VAR:dev-placeholder}` placeholders that are obviously
  useless, never a working secret. `.env` is gitignored;
  `.env.example` is the template.
- **Dependency hygiene.** Renovate / Dependabot open PRs for
  upstream security advisories. SonarQube blocks the merge gate
  on BLOCKER / CRITICAL / unresolved security hotspots.

## Coordinated disclosure

We follow a 90-day coordinated disclosure window from the date a
report is acknowledged. If a fix requires longer, we'll agree on a
new date with the reporter. Premature public disclosure (a tweet,
a public GitHub issue, a blog post) before the agreed window
forces us to expedite the patch and may reduce the time we have to
notify downstream users.

## Recognition

Reporters who follow the process and consent to attribution are
credited in the published advisory. Anonymous reports are
accepted; just say so up front.

## Acknowledgements

This policy is modelled on the
[GitHub Security Lab](https://securitylab.github.com/) template
and the
[Coordinated Disclosure ISO/IEC 29147](https://www.iso.org/standard/72311.html)
guidelines.
