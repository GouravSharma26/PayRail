# PayRail — Distributed Payment Processing & Ledger Simulator

> A synthetic-only payment platform built to demonstrate backend correctness, event-driven processing, recovery, and ledger reconciliation. **It must never connect to a real bank, payment network, card processor, or real customer funds.**

## Project goal

Build a demonstrable, locally runnable system that accepts transfer commands, guarantees idempotent API behavior, processes work asynchronously, records balanced double-entry transfer postings, recovers from transient failures, and reports inconsistencies without silently rewriting financial history.

## Primary stack

- Java 21 LTS
- Spring Boot (select and pin one supported release line after verifying compatibility for all selected libraries)
- Maven Wrapper
- PostgreSQL, with schema changes managed only by Flyway
- Kafka protocol event broker (Apache Kafka in KRaft mode or Redpanda; pick one and pin an image tag)
- Docker Compose
- JUnit 5, Spring test support, and Testcontainers for PostgreSQL and broker integration tests
- Spring Actuator, structured logs, and Micrometer metrics
- Optional later phase: React + TypeScript dashboard

## Non-negotiable safety boundary

All accounts, identities, balances, and transfers are fictional synthetic data. Do not add bank integrations, payment-provider SDKs, card data, real account numbers, credential collection, or production-money language. The README, UI, seeded data, and API docs must label the application a simulator. Do not use real customer data in tests or examples.

## Planned repository layout

```text
payrail/
├── AGENTS.md
├── README.md
├── plan.md
├── docs/
│   ├── ARCHITECTURE.md
│   ├── DOMAIN_MODEL.md
│   ├── DATA_MODEL.md
│   ├── API_CONTRACT.md
│   ├── EVENTING_AND_FAILURES.md
│   ├── TESTING_STRATEGY.md
│   ├── SECURITY_AND_SAFETY.md
│   ├── UI_DASHBOARD.md
│   ├── DEPLOYMENT_AND_CI.md
│   ├── MODEL_AND_TECH_DECISIONS.md
│   ├── HUMAN_SETUP.md
│   ├── ANTIGRAVITY_WORKFLOW.md
│   ├── DEVELOPMENT_CHECKLIST.md
│   ├── PHASE_LOG.md
│   └── OFFICIAL_REFERENCES.md
├── .agents/rules/
│   ├── database.md
│   ├── java.md
│   └── testing.md
├── backend/                 # Spring Boot application
├── frontend/                # Added only after backend gates pass
├── docker-compose.yml
└── .github/workflows/ci.yml
```

The agent may adjust the exact package/repository layout if it records the reason first and keeps the architectural boundaries intact. Do not create empty placeholder modules just to match the diagram.

## Start here

1. Read [`AGENTS.md`](AGENTS.md), [`plan.md`](plan.md), and [`docs/ANTIGRAVITY_WORKFLOW.md`](docs/ANTIGRAVITY_WORKFLOW.md).
2. Read all design docs before implementing the affected phase.
3. Ask Antigravity to execute **Phase 0 only** first: inspect the workspace, write a proposed file tree and decisions, verify toolchain compatibility, and wait for review before coding.
4. Implement one phase at a time. Run tests and update the checklist/log at each phase boundary.

## Key design principle

PostgreSQL is the source of truth for transfer state, idempotency, balances, ledger postings, outbox records, and reconciliation findings. Kafka/Redpanda carries work but is not the source of truth. Delivery is treated as **at least once**; durable database constraints, row locking, transactional state changes, and deduplication make repeated delivery safe.

## Status

Documentation / implementation specification only. Do not mark any feature implemented until code and tests prove it. Use `docs/PHASE_LOG.md` to record actual progress, test commands, results, commit hashes, and known limitations.
