# Security, Privacy, and Simulator Safety

## Mandatory boundary

PayRail is an educational portfolio simulator using synthetic data only. It is not a bank, payment service provider, or production financial system. Do not connect it to actual banks, payment rails, card processors, payment gateways, identity providers, or real customer accounts. Use fictional names and balances. No credentials or financial personal data should be required to run the core project.

## Data handling
- Never ingest real account statements, card numbers, CVVs, passwords, government IDs, or real personal data.
- `.env.example` uses empty or clearly fake placeholders. `.env`, local overrides, certificates, and generated secrets are ignored by Git.
- Do not log raw idempotency keys, secrets, authorization headers, or full event payloads. Use correlation IDs and safe key fingerprints if needed.
- API errors must not expose SQL, stack traces, hostnames, credentials, broker configuration, or environment variables.
- Restrict Actuator endpoints; never expose environment/configuration, heap dumps, unrestricted beans, or all metrics without consideration.

## API trust boundaries

A local MVP may be unauthenticated only when it is bound to localhost/private development networking and clearly labeled as demo/admin functionality. Before any public deployment, implement authentication and role-based authorization for account creation, operational endpoints, reconciliation triggers, data reset, and replay operations. A hidden URL is not access control. Never claim the unauthenticated MVP is production-secure.

Add strict request validation, request-body limits, bounded pagination, parameterized SQL, and sane timeouts. Never accept arbitrary SQL, shell commands, file paths, or unrestricted broker topics from clients.

## Data integrity as security
- Revalidate account activity, currencies, and funds at processing time.
- Use DB locks, constraints, foreign keys, idempotency, terminal-state guards, and atomic posting transactions.
- Do not expose an API to directly set current balance or edit/delete ledger entries.
- Treat reconciliation as detection/reporting only; do not auto-repair money state.
- Read authoritative transfer data from PostgreSQL instead of trusting a message payload for amount, currency, or status.
- Event replay must be explicit, auditable, and idempotent.

## Public demo checklist
- Display “Synthetic Payment Simulator — no real money or bank connections.”
- Use disposable synthetic data and unique secrets; protect privileged operations.
- Keep PostgreSQL, broker, and management ports private.
- Use TLS at the platform edge and restrict Actuator access.
- Document reset procedures; destructive reset tools must refuse to run outside a development profile.
- Never label an unverified deployment as live or production-ready.

## Acceptance checklist
- [ ] No real bank/payment integration or financial credential path.
- [ ] No secret tracked in Git.
- [ ] Money amounts never use binary floating point.
- [ ] Ledger is immutable through app/API paths.
- [ ] Operations are protected or the demo stays private/localhost-only.
- [ ] Logs/errors omit secrets, raw keys, SQL and stacks.
- [ ] API/UI label outcomes as simulated.
