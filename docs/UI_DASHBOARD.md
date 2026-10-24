# Dashboard Specification (Phase 5 Only)

Do not start this phase until Phases 1–4 gates pass. The dashboard is an operational viewer, not a source of financial truth.

## Stack and rules
- React + TypeScript with lockfile; choose and pin a compatible maintained build/test stack at Phase 5.
- Persistent warning: **“PayRail Synthetic Payment Simulator — no real money or bank connections.”**
- Amounts arrive as decimal strings. Do not use JavaScript floating point for authoritative money arithmetic.
- API responses are authoritative. Poll nonterminal transfer state at bounded intervals and stop on `SUCCEEDED`/`FAILED`.
- Provide loading, empty, error, validation, stale-data, and success states; support keyboard navigation, focus states, accessible labels, and responsive layout.
- Do not display secrets, stack traces, claim tokens, raw DB payloads, or environment values.

## Screens

### Overview
Transfer counts by status, synthetic totals clearly labeled as simulated, outbox backlog/oldest age, retry activity, latest reconciliation result, and service health. No authoritative balance computation in the browser.

### Accounts
Paginated synthetic accounts with display label, UUID, currency, status, opening/current balance, and transfer history. Account creation is for local/demo use and must not be exposed publicly without authorization.

### Transfers
Paginated/filterable transfer list by status, date, source/destination account, currency, or transfer ID. Detail screen displays IDs, amount/currency, timestamps, terminal failure code/message, attempt count, correlation ID, status history, and processing attempts. Retries are not additional money movement.

### Reconciliation
Show runs, scan counts, issues, severity, related IDs, and timestamps. Resolving an issue is an operator annotation if supported; never offer “auto-fix balance” or direct ledger editing.

### Operations
Show outbox status, age, attempts, safe error codes, broker/consumer health, and DLQ summaries. Provide only safe per-event replay if backend implements it with authorization and audit; otherwise read-only diagnostic views.

## Minimum UI tests
- Simulator warning visible on all entry routes.
- Transfer filtering, pagination, empty state, and API failures.
- Transfer detail displays history and safe error message.
- Polling stops at terminal status and is cleaned up on unmount.
- Amount remains a string through API/display; no `parseFloat` used for money calculations.
- Unsupported operational actions are absent or disabled.

## Non-goals
No real payment connection, card capture, FX, customer onboarding, advanced analytics, or unsupported administrative API calls. Public exposure requires a real auth/authorization decision; hiding controls is not access control.
