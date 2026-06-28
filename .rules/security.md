# Security rules

> Load this pack when touching auth, JWT, OAuth, secrets, or dependencies.

## Secrets

- **Never** in source. Never in `application.yml` defaults.
- Use `${ENV_VAR:dev-placeholder}` where the placeholder is obviously useless.
- `.env` is gitignored. `.env.example` carries placeholders only.
- `BFF_JWT_SECRET` ≥ 32 chars. The application refuses to start otherwise.
- Generate locally with `openssl rand -base64 32`.
- Rotate any secret that appears in a CI log, PR description, or chat transcript.

## Auth (in `bff/auth/**`)

- The only identity provider is Google. No passwords, no email+password login.
- Sessions are rows in `auth_sessions` referenced by the JWT `jti`. JWT alone is **not** a session.
- The session filter (`SessionFilter`) verifies signature **and** DB row on every request.
- Revocation = `UPDATE auth_sessions SET revoked_at = now() WHERE jti = ?`.
- Cookie flags: `HttpOnly`, `Secure` (non-`local`), `SameSite=Lax`. CSRF strategy documented in `docs/adr/`.

## Dependencies

- Renovate/Dependabot updates land as PRs. The PR body must justify the bump.
- License compatibility: Apache-2.0 / MIT / BSD only. Flag anything else.
- No new runtime dependency without an ADR or a PR-body justification.

## Logging

- Never log a JWT, an `id_token`, a refresh token, a session cookie, or any password-equivalent.
- Never log full request/response bodies in non-`local` profiles.
- Redact `Authorization`, `Cookie`, `Set-Cookie` headers before logging.

## HTTP

- TLS in non-`local` environments. HSTS header set by the gateway/CDN.
- CORS: explicit allow-list. No `*` in production.
- CSRF: strategy in `docs/adr/`. Don't ship a new state-changing endpoint without it.

## Forbidden

- Disabling Spring Security defaults without an ADR.
- Bypassing the JWT secret length check.
- Storing user-supplied passwords (we don't have any — keep it that way).
- Returning detailed error messages to clients in non-`local` profiles.
- Hard-coding Google `client_id` values per environment. The same id is used in `bff` and `front`.

## Reviewers

- PRs touching `bff/auth/**` require a security reviewer in addition to the maintainer approval.
- PRs introducing a new dependency require a license and CVE review.
- PRs that change the JWT strategy, the session model, or the auth flow require an ADR.

## Quick checklist before pushing

- [ ] No secrets in the diff (search for `BFF_JWT_SECRET`, `GOOGLE_CLIENT_ID`, `SONAR_TOKEN`).
- [ ] No `System.out.println` of credentials, tokens, or session cookies.
- [ ] No new dependencies without justification.
- [ ] Any new state-changing endpoint is behind CSRF protection (per ADR).
- [ ] Session handling still hits the DB on every request — no in-memory cache of session state.
