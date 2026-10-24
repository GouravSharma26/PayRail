---
trigger: model_decision
description: Apply when implementing Java/Spring Boot code, REST controllers, service transactions, configuration, and event processing.
---

# Java / Spring Rules

- Target Java 21 and a single explicitly pinned compatible Spring Boot line.
- Controllers validate/translate HTTP requests; application services implement use cases; persistence code owns SQL. Keep DTOs separate from DB row objects.
- Prefer explicit JDBC for locking-critical ledger/outbox code. If annotations define transaction boundaries, avoid self-invocation traps and document the boundary.
- Amount values are `BigDecimal`, validated to scale 2 and precision 19; JSON uses strings.
- Use `Instant`/UTC, UUID IDs, immutable DTOs where practical, and stable machine-readable error codes.
- Do not swallow exceptions or map unknown results to success. Sanitize public error messages.
- Inject `Clock` or use controllable time in tests for leases/backoff where useful.
- Configuration is externalized, validated at startup, and contains no committed secrets.
- Do not add microservices/frameworks/dependencies without a documented reason.
