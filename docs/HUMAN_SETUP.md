# Human Setup Guide

This guide is for the developer operating Antigravity and reviewing its work. The agent must verify installed versions instead of assuming commands succeed.

## Required tools
- Git
- Java 21 JDK (`java -version`)
- Project Maven Wrapper (`mvnw.cmd` on Windows, `./mvnw` on macOS/Linux)
- Docker Desktop/Engine and Docker Compose v2 (`docker compose version`)
- Optional later: Node.js/npm once Phase 5 starts

## Initial Antigravity procedure
1. Open the repository root as the workspace.
2. Run `git status` and confirm the location before edits; do not overwrite existing work.
3. Read `AGENTS.md`, `plan.md`, and `docs/ANTIGRAVITY_WORKFLOW.md`.
4. Ask Antigravity to execute Phase 0 only and review version choices before implementation.
5. Never paste API keys or credentials into prompts. The core simulator should require no external API secrets.

## Windows PowerShell

```powershell
java -version
git --version
docker --version
docker compose version

# After docker-compose.yml exists
docker compose up -d --build
docker compose ps

# Build and test from repository root
.\mvnw.cmd -B verify

# Logs (use actual Compose service names)
docker compose logs -f postgres
docker compose logs -f redpanda

docker compose down
```

If the broker service is named `broker` or `kafka`, use that actual service name instead of `redpanda`.

## macOS / Linux

```bash
java -version
git --version
docker --version
docker compose version
./mvnw -B verify
docker compose up -d --build
docker compose ps
docker compose logs -f backend
docker compose down
```

Do not run `docker compose down -v` casually: it deletes named volumes and local synthetic database state. Use it only for a deliberate reset of the disposable PayRail development environment.

## Expected local workflow
1. Copy `.env.example` to `.env` only if local configuration requires overrides; `.env` must remain untracked.
2. Start PostgreSQL and the broker with Compose; wait for health checks rather than fixed sleeps.
3. Start the backend using the documented profile or Compose service.
4. Verify `/actuator/health/readiness`.
5. Run tests and follow README curl examples.
6. Submit a transfer and verify `202 PENDING` followed by a terminal simulated state.
7. Repeat with the same idempotency key and verify no duplicate transfer/posting.
8. Trigger reconciliation and inspect the report.

## Troubleshooting
- **Java mismatch:** use JDK 21 and verify `JAVA_HOME`/PATH.
- **Docker unavailable:** run build/unit tests, report integration tests as blocked locally, and ensure CI runs them.
- **Port conflict:** adjust local Compose overrides; do not expose DB/broker ports publicly as a workaround.
- **Migration failure:** inspect Flyway error and add a migration fix; never manually patch tables to bypass the migration.
- **Broker outage:** transfer must remain durable, and outbox should show pending publication; restart broker and verify recovery.
- **Stuck transfer:** inspect status history, attempt details, outbox age, logs, and reconciliation findings; never manually update rows to fake success.

## Resetting synthetic data
Prefer a documented fixture reset against a disposable local database. Any reset command must show the exact target and refuse to run against a non-development profile. Never point a reset script at an externally configured database.
