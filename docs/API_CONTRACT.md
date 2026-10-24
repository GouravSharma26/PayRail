# REST API Contract (MVP)

Base path: `/api/v1`. JSON is UTF-8. OpenAPI is the machine-readable reference generated from DTO/controller contracts. All examples use fictional UUIDs and synthetic balances. The app is a simulator, not a real payment service.

## General conventions

- IDs are UUID strings.
- Amount fields are decimal strings, e.g. `"25.00"`; reject exponent notation and amounts with more than two fractional digits.
- Currency is an uppercase three-character code. No FX.
- Timestamps are ISO-8601 UTC values.
- Support `X-Correlation-Id` as a UUID if supplied; otherwise generate one. Return it in the response and include it in structured logs.
- New transfer request requires `Idempotency-Key`, 8–128 permitted printable ASCII characters. Never treat it as an auth token.
- Paginated lists use `page` (default 0), `size` (default 20, hard max 100), stable ordering, and a response containing `items`, `page`, `size`, `totalItems`, `totalPages`.
- Do not return stack traces, SQL, broker metadata, secrets, or internal hostnames in error bodies.

## Error envelope

```json
{
  "timestamp": "2026-01-01T00:00:00Z",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "The request is invalid.",
  "details": [{"field": "amount", "code": "INVALID_SCALE", "message": "Amount must have at most two decimal places."}],
  "correlationId": "00000000-0000-0000-0000-000000000001"
}
```

Keep error `code` stable; human-readable message can improve. Never include account private details (this MVP stores no real identity data).

## Account endpoints

### `POST /accounts`

Create a synthetic account. This is a simulator/admin-style operation, not an account-opening integration.

Request:

```json
{"displayName":"Synthetic Account A","currency":"INR","openingBalance":"1000.00"}
```

Rules: display name <= 80 chars; balance >= 0; currency matches supported three-letter code; opening balance is a synthetic starting condition; opening balance is immutable after creation. Response `201 Created` with account ID, status, currency, opening/current balance strings, and timestamps.

### `GET /accounts`

List accounts with bounded pagination and optional `status` and `currency` filters. Never provide unbounded list output.

### `GET /accounts/{accountId}`

Return account metadata and current balance.

### `GET /accounts/{accountId}/transfers`

Return transfers where this account is source or destination, paginated newest-first.

## Transfer endpoints

### `POST /transfers`

Required header: `Idempotency-Key`.

Request:

```json
{
  "sourceAccountId":"00000000-0000-0000-0000-000000000010",
  "destinationAccountId":"00000000-0000-0000-0000-000000000020",
  "amount":"125.40",
  "currency":"INR"
}
```

Synchronous validation: required fields, UUIDs, positive amount, max precision/scale, distinct accounts, valid currency format. Database/account status/funds validations must be repeated in the posting transaction because account state can change after submission.

New request response: `202 Accepted`, with `transferId`, `status: PENDING`, creation timestamp, and a status URL. It means the transfer was durably accepted, **not** that it succeeded.

Idempotency behavior:
- Same key + same canonical request hash: return the original transfer identity/status with an idempotent replay indicator; do not create a new transfer or outbox event.
- Same key + different request hash: `409 IDEMPOTENCY_KEY_REUSED`.
- Concurrent requests with same key must resolve through a unique DB constraint/re-read, not an in-memory check.
- If server response was lost after commit, retrying is safe.

### `GET /transfers/{transferId}`

Return transfer details: IDs, amount/currency, lifecycle status, failure code/message if failed, attempts count, timestamps, and links/paths to status history and attempts. Omit internal claim token/lease token.

### `GET /transfers`

Search by optional status, source account, destination account, currency, from/to timestamps, and exact transfer ID. All lists must be paginated and indexed. Validate date ranges and caps. Return stable sort `createdAt DESC, id DESC`.

### `GET /transfers/{transferId}/history`

Return ordered status history.

### `GET /transfers/{transferId}/attempts`

Return safe processing attempt details, sanitized error messages, outcome, attempt number, timestamps, and duration. Do not return stack traces.

## Operational / reconciliation endpoints

These are intended for the local simulator demo. If deployed publicly, protect them with authentication/authorization before enabling them.

- `GET /ops/health/summary`: safe app/broker/database status summary (do not leak credentials or internal network topology).
- `GET /ops/outbox`: paginated unpublished/recent outbox records with event IDs/type, age, attempts, and safe error code; never expose the full arbitrary event payload by default.
- `GET /ops/reconciliation/runs`: list runs.
- `POST /ops/reconciliation/runs`: trigger one bounded reconciliation run; enforce a cooldown or single-flight guard.
- `GET /ops/reconciliation/runs/{runId}`: run summary + paginated issues.
- `GET /ops/dlq`: expose only safely decoded dead-letter summaries and event identifiers if implemented; provide a documented replay workflow rather than a blind “replay all” button.

Operational endpoints must never let the caller execute SQL, modify ledger entries, or mark a payment successful manually.

## Health endpoints

- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus` if Prometheus export is configured

Expose only intended actuator endpoints. Do not expose environment/configuration, heap dumps, or unrestricted beans.

## Status and response examples

`GET /transfers/{id}` response shape:

```json
{
  "id":"00000000-0000-0000-0000-000000000099",
  "sourceAccountId":"00000000-0000-0000-0000-000000000010",
  "destinationAccountId":"00000000-0000-0000-0000-000000000020",
  "amount":"125.40",
  "currency":"INR",
  "status":"SUCCEEDED",
  "failureCode":null,
  "createdAt":"2026-01-01T00:00:00Z",
  "updatedAt":"2026-01-01T00:00:01Z",
  "completedAt":"2026-01-01T00:00:01Z",
  "processingAttemptCount":1,
  "correlationId":"00000000-0000-0000-0000-000000000001"
}
```

## HTTP status semantics

- `200 OK`: successful query or idempotent replay of an existing transfer, according to documented implementation.
- `201 Created`: synthetic account created.
- `202 Accepted`: new transfer durably accepted for async processing.
- `400 Bad Request`: request-shape/validation failure.
- `404 Not Found`: unknown resource.
- `409 Conflict`: idempotency key reused with different request; state conflict if explicitly documented.
- `422 Unprocessable Entity`: may be used for synchronous semantic validation if the API consistently separates it from shape validation. Do not randomly mix 400/422.
- `429 Too Many Requests`: configured rate limit exceeded, if implemented.
- `500/503`: unexpected service failure or unavailable dependency; do not tell the caller a transfer failed if its durable state is unknown. Encourage querying by idempotency key/retrying the same key.

Document the final choice in OpenAPI and tests. Avoid returning `200` to mean async work is completed.
