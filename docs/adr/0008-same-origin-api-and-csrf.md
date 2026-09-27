# ADR 0008: Same-origin API, no cookies — why there is no CORS config and no CSRF token

- Status: accepted
- Date: 2026-09-27
- Deciders: arepresas
- Related: security, bff, front

## Context

`.rules/security.md` has required two things this ADR now records:

- "CORS: explicit allow-list. No `*` in production."
- "CSRF: strategy in `docs/adr/`."

Neither was ever written down, and neither is configured in code.
`SecurityConfig` ships no CORS bean, no `@CrossOrigin` exists, and the
SPA never sends credentials. The gap was invisible because it works —
but "it works because of an accident of topology" is not a strategy,
and the next person to put the front on a second domain would ship a
cross-origin hole or a confusing 401.

Two facts decide it:

1. **Authentication is a bearer token in a header, not a cookie.**
   `POST /api/auth/google` returns the session JWT in a JSON body; the
   SPA stores it and sends `Authorization: Bearer …` on every call.
   There is no `Set-Cookie` anywhere in the BFF, and no
   `credentials: 'include'` in the client.
2. **The SPA calls relative paths.** `API_BASE = '/api/tickets'`, and
   in production the frontend app reverse-proxies `/api/*` to the BFF
   over Fly private networking (`front/Dockerfile`, `front/fly.toml`),
   so the browser only ever talks to one origin.

## Decisions

### D1. No CORS configuration; the API is same-origin only

The BFF serves no `Access-Control-Allow-Origin` header, so a
cross-origin `fetch` is blocked by the browser. That is the intended
posture, not a missing feature: the API has exactly one legitimate
client, and it is served from the same origin.

**Consequence to accept:** a browser-side XSS on the front would be
able to call the API with the token it already holds. Same-origin does
not protect against that; token storage and CSP would. Recorded here
so nobody reads "no CORS" as a mitigation.

### D2. No CSRF token, because there is no ambient credential

CSRF requires the browser to attach an authenticated credential the
user did not intend to send cross-site. A bearer token in a header set
by `fetch` is not such a credential: a cross-site page cannot make the
browser attach `Authorization: Bearer …` to a request it did not
initiate with that header. If the session ever moves to a cookie, this
ADR is void and a CSRF strategy is required in the same change.

### D3. Local dev keeps the same shape

Vite proxies `/api` to the BFF in `local`, so the same-origin rule
holds in development. If you ever need a cross-origin caller (a
mobile app, a third-party integration), it gets an explicit allow-list
entry in `SecurityConfig` and an ADR amendment — never a wildcard.

## Consequences

### Negative

- A future second web client on another domain needs real CORS work
  (allow-list, credentialed requests, preflight) before it can talk to
  the BFF. That friction is intended.
- `SecurityConfig` has no CORS bean, so a reader may assume it was
  forgotten. This ADR is the answer to that.

### Positive

- No wildcard origin, no `Access-Control-Allow-Credentials`, no
  preflight cache to reason about.
- The CSRF question is settled by construction rather than by a token
  nobody would have reviewed.

## See also

- `.rules/security.md` — the two rules this ADR satisfies.
- `front/Dockerfile` — the nginx `/api/` proxy that makes the
  same-origin property true in production.
- ADR 0007 — the other edge contract (the AI provider port).
