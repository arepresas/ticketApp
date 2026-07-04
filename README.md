# ticketApp

Multi-module project: Spring Boot 4 BFF + Svelte 5 web components + PostgreSQL.

## Layout

| Path | Stack |
|------|-------|
| `domain/` | Pure Java domain model (no deps); owns the `ReceiptExtractor` port |
| `persistence/` | JDBC + Liquibase persistence |
| `minimax-ai/` | Provider implementation of the `ReceiptExtractor` port (MiniMax) |
| `bff/` | Spring Boot 4 backend-for-frontend; orchestrates against the port |
| `front/` | Svelte 5 + Vite 8 web components |
| `local-environment/` | docker-compose (PostgreSQL 18) |
| `.github/` | CI workflows, Dependabot, PR template |

## Requirements

- Java 25+
- Maven 3.9+
- Node 24 LTS (use `corepack enable` for pnpm)
- pnpm 11 (declared via `packageManager` in `front/package.json`)
- Docker (for local Postgres and Testcontainers)

## Local startup

```bash
# 1. copy env template (Postgres + Sonar + Google + BFF JWT)
cp .env.example .env
$EDITOR .env

# 2. start postgres (data persists in local-environment/docker-ticketApp/pg-data/)
docker compose -f local-environment/docker-ticketApp/docker-compose.yml up -d

# 3. run the BFF (dev mode, profile=local picks up application-local.yml)
#    Default port: 8080. Health: http://localhost:8080/actuator/health
mvn -B -pl bff -am spring-boot:run -Dspring-boot.run.profiles=local

# 4. run backend unit + integration tests (uses Testcontainers, no live DB needed)
mvn -B verify

# 5. front
cd front
corepack enable
pnpm install --ignore-scripts
pnpm dev
```

The BFF reads Postgres + Google + JWT vars from the process environment (so
`export $(grep -v '^#' .env | xargs)` or a direnv/`.envrc` setup works). Tests
under `bff/` spin up their own Postgres via Testcontainers and ignore these
vars.

## Build

```bash
mvn -B verify
cd front && pnpm build
```

## Quality

```bash
mvn -B -DskipTests verify sonar:sonar \
  -Dsonar.token=$SONAR_TOKEN \
  -Dsonar.host.url=$SONAR_HOST_URL \
  -Dsonar.organization=$SONAR_ORGANIZATION
```

## Environment variables

The repo ships `.env.example` as a template for local development. Copy it to
`.env` and fill in the real values (the real file is gitignored):

```bash
cp .env.example .env
$EDITOR .env
```

Variables defined:

| Variable            | Used by                                              | Notes                       |
|---------------------|------------------------------------------------------|-----------------------------|
| `POSTGRES_*`        | `local-environment/docker-ticketApp/docker-compose.yml` | DB name, user, password, host port |
| `DB_URL`            | `bff` (Spring `spring.datasource.url`)               | JDBC URL. Default in `application-local.yml` is `jdbc:postgresql://localhost:6432/ticketAppDb`; override for non-local profiles. |
| `DB_USER`           | `bff` (Spring `spring.datasource.username`)          | Default in `application-local.yml`: `ticketAppUser`. |
| `DB_PASSWORD`       | `bff` (Spring `spring.datasource.password`)          | Default in `application-local.yml`: `ticketAppPass`. |
| `GOOGLE_CLIENT_ID`  | `bff` (`google.client-id`) + `front` (`VITE_GOOGLE_CLIENT_ID`) | OAuth 2.0 Web client id from Google Cloud Console. Same value on both sides — the SPA exchanges the Google `id_token` and the BFF verifies it against this audience. |
| `BFF_JWT_SECRET`    | `bff` (`bff.jwt.secret`)                             | HS256 signing key for BFF-issued session JWTs. **≥32 chars (256 bits)** or the app refuses to start. Generate with `openssl rand -base64 32`. A dev placeholder ships in `application.yml` so `mvn verify` works without it; replace it in any non-local environment. |
| `SONARQUBE_TOKEN`   | opencode MCP server, CI workflows (as GitHub secret) | `sqp_...` from <https://sonarcloud.io/account/security>; required |
| `SONARQUBE_ORG`     | opencode MCP server, CI workflows (as GitHub secret) | this repo's organization: `arepresas`; required |
| `SONARQUBE_URL`     | opencode MCP server, CI workflows (as GitHub secret) | required, no default — set to `https://sonarcloud.io` (or `https://sonarqube.us` for SonarQube Cloud US, or your self-hosted URL) |

The opencode MCP server reads `.env` via `dotenv-cli` at startup; the back-end
reads `POSTGRES_*` via docker compose variable substitution. SonarQube vars are
required everywhere — the scanner refuses to run if any is missing.

## Package registry

The `front/` module ships a local `.npmrc` pinning the registry to the public
`https://registry.npmjs.org/`, overriding any user/global mirror configuration.
This keeps the repo portable across machines and CI runners.
