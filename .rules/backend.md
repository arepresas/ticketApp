# Backend rules (Java + Spring)

> Load this pack when editing Java code under `domain/`, `persistence/`, `minimax-ai/`, or `bff/`.

## Layer boundaries (build-break if violated)

```
                       ┌────────────┐
                       │   domain   │   (no outward deps)
                       └────────────┘
                            ▲   ▲
              ┌─────────────┘   └────────────┐
              │                              │
       ┌─────────────┐                ┌─────────────┐
       │ persistence │                │  minimax-ai │   (ADR 0007)
       └─────────────┘                └─────────────┘
              ▲                              ▲
              └─────────────┐   ┌────────────┘
                            │   │
                       ┌────────────┐
                       │     bff    │
                       └────────────┘
```

- `domain` imports **only** `java.*` and JDK-only annotations (`org.jetbrains.annotations`).
- `persistence` and `minimax-ai` import `domain` only. **Never** `bff`. **Never** each other.
- `bff` imports all three (`domain`, `persistence`, `minimax-ai`). Holds Spring wiring, controllers, DTOs.

If a reverse dependency is required, stop and write an ADR (`docs/adr/`).

## Package layout

```
com.ticketapp.<module>.<concern>.<role>
```

| Package | Lives |
|---------|-------|
| `com.ticketapp.bff.api` | `@RestController` classes |
| `com.ticketapp.bff.auth` | `SessionFilter`, `SessionTokenService`, `GoogleTokenVerifier`, `AuthenticatedUser` |
| `com.ticketapp.bff.auth` | Repository interfaces (`SessionRepository`, `UserRepository`) |
| `com.ticketapp.persistence` | JDBC implementations (`JdbcTicketRepository`, `JdbcTicketExtractionRepository`, ...) |
| `com.ticketapp.domain.<bounded-context>` | Pure model |
| `com.ticketapp.domain.ai` | Provider-agnostic ports (`ReceiptExtractor`) — ADR 0007 |
| `com.ticketapp.minimaxai` | Provider implementation of the `ReceiptExtractor` port |

**Banned package names**: `util`, `helpers`, `common`, `shared`, `misc`, `dto`, `constants`. Lift to `domain` (if pure) or accept duplication.

## Idioms

- Records for value objects and DTOs. Lombok is allowed in BFF and backend modules for boilerplate reduction (for example `@RequiredArgsConstructor`, `@Slf4j`) when behavior remains explicit and code stays easy to review.
- Constructor injection only. No `@Autowired` on fields.
- `@ConfigurationProperties` records when 3+ properties belong to the same concern.
- Provider-specific properties (`@ConfigurationProperties("ticketapp.ai.<provider>.*")`) live with the provider module, not in the BFF. The BFF owns provider-agnostic knobs (`enabled`, `cron`, `batchSize`, `retryAttempts`) only.
- Throw domain-specific unchecked exceptions with stable error codes (`AUTH_SESSION_EXPIRED`). Map to `ProblemDetail` (RFC 7807) in a single `@RestControllerAdvice`.
- Never `catch (Exception)` or `catch (Throwable)`. Never silently swallow.
- When a method declares `throws X`, the caller catches `X` and propagates. Provider modules wrap their internal failures into domain exceptions at the port boundary — callers should never see provider-specific exception classes.

## What to avoid

- `System.out.println` / `System.err.println` → SLF4J.
- `Thread.sleep` outside tests.
- `spring.jpa.hibernate.ddl-auto=update` → Liquibase.
- Field injection, circular bean dependencies.
- Catching and re-logging without rethrow.
- Returning `Map<String, Object>` from controllers — use a typed DTO/record.
- Importing a provider-specific class from the BFF (or anywhere outside the provider module). The orchestrator depends only on the port.

## Auth-specific (in `bff/auth/**`)

- `SessionTokenService` issues an HS256 JWT whose `jti` references a row in `auth_sessions`.
- Verification rejects the token if the `auth_sessions` row is missing, revoked, or expired — even when the signature is valid.
- `BFF_JWT_SECRET` is read from `bff.jwt.secret`. Application refuses to start if it's < 32 chars. Don't bypass the check.
- Google login verifies the `id_token` against Google's JWKS. No server-side secret for Google — verification is anonymous.
- Reviewers: at least one maintainer + one security reviewer for any PR touching this folder.

## Logging

- One logger per class: `LoggerFactory.getLogger(YourClass.class)`.
- Levels: `INFO` lifecycle, `WARN` recoverable, `ERROR` operator-visible. `DEBUG` only under `com.ticketapp.*` and only in `local` profile.

## Configuration

- Shared config in `bff/src/main/resources/application.yml`.
- Profile-specific overrides: `application-local.yml`, `application-{profile}.yml`.
- `${ENV_VAR:dev-placeholder}` — placeholder must be obviously useless, never a working secret.

## Quick checklist before pushing

- [ ] `mvn -B verify` is green.
- [ ] New public types in `domain` have Javadoc.
- [ ] No new dependencies without PR-body justification.
- [ ] No secrets in `application.yml` defaults.
- [ ] Layer boundaries still hold (`mvn -B -ntp dependency:tree` is clean).
- [ ] If you added a new provider module: it ships a Spring Boot autoconfiguration that registers a `ReceiptExtractor` bean; the BFF never imports any of its classes.
