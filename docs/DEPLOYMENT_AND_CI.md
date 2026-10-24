# Deployment, Docker Compose, and CI

## Local Compose

Use Compose for PostgreSQL, the chosen Kafka-compatible broker, and optionally the backend. Pin compatible image tags; never use `latest`. Configure container health checks and backend startup readiness. Use named volumes for local development and document that `docker compose down -v` deletes the persisted synthetic database.

Minimum local services:
- `postgres`: database and health check (`pg_isready` or equivalent).
- `broker`: one Kafka-compatible broker in single-node development mode, with advertised listeners configured for both Compose-network clients and host development clients if both modes are supported.
- `backend` (optional Compose service): builds the app and starts after dependency health checks. The app must still handle late dependency availability at runtime.

Avoid exposing database/broker ports on all interfaces unless required. Bind to localhost in local development when host access is needed. Use Compose service DNS for container-to-container traffic; do not configure a container to connect to `localhost` for another service.

## Configuration

Externalize at least:
- Spring profile / server port.
- PostgreSQL URL, username, password.
- Broker bootstrap servers and topic names.
- Outbox batch size, lease duration, publish retry bounds.
- Processing lease duration, max attempts, retry backoff bounds, recovery sweep interval.
- Reconciliation interval and stale-work age thresholds.
- Log level and actuator/metrics settings.

Commit only a safe `.env.example` with placeholder values. Never require an external API key for the core simulator. Fail startup with a clear sanitized configuration error when required values are missing; do not silently connect to a different database.

## Health and graceful shutdown

- Readiness should reflect whether dependencies required to serve requests are available. Liveness should reflect whether the process can make progress, not fail just because Kafka is temporarily down if the API can still persist transfers to the outbox.
- Drain/stop broker consumption and background workers gracefully when possible.
- Respect in-flight DB transaction completion and avoid acknowledging Kafka records before durable outcomes commit.
- On restart, outbox leases and processing leases must expire/recover safely.

## GitHub Actions CI

Create a workflow that:
1. Checks out code and configures the selected JDK version.
2. Uses the committed Maven Wrapper and caches Maven dependencies safely.
3. Runs format/lint/static checks if configured.
4. Runs unit and integration tests (`./mvnw -B verify` or documented equivalent), including Testcontainers on a Docker-capable runner.
5. Produces build artifacts only after tests pass.
6. Optionally builds the Docker image and validates Compose configuration.
7. Does not deploy publicly from untrusted pull requests or print secrets.

Use least-privilege workflow permissions and pin action versions where practical. If CI splits unit and integration tests into jobs, both are required checks on the main branch. Do not silently exclude slow correctness tests from the required path.

## Public demo deployment

Deployment is optional and comes only after local clean-checkout verification. A public environment must use disposable synthetic data, unique credentials, TLS at the platform edge, private database/broker network access, restricted actuator endpoints, and protected operational APIs. Display the simulator warning on every UI entry route and in the README. Do not expose unauthenticated account-creation, replay, reset, or reconciliation-trigger endpoints to arbitrary Internet users.

Document the actual platform and actual tested steps only after a real deployment is verified. Do not fabricate a live URL, health status, uptime, or successful deployment.
