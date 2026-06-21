# ticketApp

Multi-module project: Spring Boot 4 BFF + Svelte 5 web components + PostgreSQL.

## Layout

| Path | Stack |
|------|-------|
| `domain/` | Pure Java domain model (no deps) |
| `infrastructure/` | JDBC + Liquibase persistence |
| `bff/` | Spring Boot 4 backend-for-frontend |
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
# 1. start postgres
docker compose -f local-environment/docker-compose.yml up -d

# 2. backend (unit + integration via Testcontainers)
mvn -B verify

# 3. front
cd front
corepack enable
pnpm install --ignore-scripts
pnpm dev
```

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
