---
trigger: model_decision
description: Apply when adding tests, debugging flaky tests, changing retry behavior, or reviewing CI validation.
---

# Testing and Evidence Rules

- Each behavior change includes a test; correctness-critical claims need integration tests, not only mocks.
- Use Testcontainers PostgreSQL for transactions/constraints/locking and a real Kafka-compatible broker for event delivery semantics. H2 is not evidence for PostgreSQL concurrency correctness.
- Never weaken or delete an assertion just to pass a test. Do not silently skip integration tests in CI.
- Concurrency tests should synchronize contenders to maximize the race and then assert status, balances, ledger legs, and history.
- Test duplicate idempotency keys, duplicate broker events, crash windows, broker outages, retry exhaustion, malformed event handling, and reconciliation findings.
- Report exact commands and counts. Distinguish passed, failed, skipped, and blocked tests. Never claim commands were run if they were not.
- Test-only fault injection must be isolated from default/public runtime configuration.
