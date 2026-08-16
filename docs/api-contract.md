# API Contract

This document describes the public HTTP contract for Payments Ledger API.

All request and response bodies use JSON. Monetary values are integer cents.

## Error Format

All expected application errors use the same response shape:

```json
{
  "code": "invalid_request",
  "message": "amount must be positive"
}
```

Common error codes:

- `invalid_request`: malformed JSON, missing fields, invalid values, or invalid account references.
- `conflict`: resource already exists.
- `not_found`: resource does not exist.
- `service_unavailable`: temporary capacity exhaustion or database timeout.

The service should not return HTTP 500 for known operational conditions such as
database pool saturation. Those cases must be mapped to `503 Service Unavailable`.

## Health

### `GET /health`

Returns whether the HTTP process is alive.

Response:

```json
{
  "status": "ok"
}
```

Status codes:

- `200 OK`: process is healthy.

## Accounts

### `POST /accounts`

Creates an account with an initial balance.

Request:

```json
{
  "id": "acc-1",
  "balance": 100000
}
```

Response:

```json
{
  "id": "acc-1",
  "balance": 100000
}
```

Status codes:

- `201 Created`: account created.
- `409 Conflict`: account already exists.
- `422 Unprocessable Entity`: missing or invalid payload.
- `503 Service Unavailable`: temporary database capacity exhaustion.

### `GET /accounts/{id}/statement`

Returns the account balance and completed transfers for the account.

Response:

```json
{
  "accountId": "acc-1",
  "balance": 97500,
  "transfers": [
    {
      "id": "6f71d48a-9e56-4b1a-a1f6-f8eb4ab2d537",
      "payerId": "acc-1",
      "payeeId": "acc-2",
      "amount": 2500,
      "idempotencyKey": "abc-123",
      "status": "completed",
      "failureReason": null,
      "createdAt": "2026-08-16T00:00:00Z"
    }
  ]
}
```

Status codes:

- `200 OK`: statement returned.
- `404 Not Found`: account does not exist.
- `503 Service Unavailable`: temporary database capacity exhaustion.

## Transfers

### `POST /transfers`

Accepts a transfer and returns immediately. Settlement happens asynchronously.

Request:

```json
{
  "payerId": "acc-1",
  "payeeId": "acc-2",
  "amount": 2500,
  "idempotencyKey": "abc-123"
}
```

Response for a new transfer:

```json
{
  "id": "6f71d48a-9e56-4b1a-a1f6-f8eb4ab2d537",
  "payerId": "acc-1",
  "payeeId": "acc-2",
  "amount": 2500,
  "idempotencyKey": "abc-123",
  "status": "pending",
  "failureReason": null,
  "createdAt": "2026-08-16T00:00:00Z"
}
```

Status codes:

- `201 Created`: transfer accepted for settlement.
- `200 OK`: idempotency key already existed and the original transfer was returned.
- `422 Unprocessable Entity`: missing fields, invalid amount, same payer/payee, or invalid account reference.
- `503 Service Unavailable`: temporary database capacity exhaustion.

Idempotency:

- `idempotencyKey` is required and globally unique.
- Repeating a request with an existing key returns the original transfer with `200 OK`.
- The original transfer is not modified by subsequent requests using the same key.
- Clients should treat the key as the identity of an operation attempt, not merely as a retry token.

Settlement states:

- `pending`: transfer has been accepted and is waiting for settlement.
- `completed`: settlement succeeded and balances were updated.
- `failed`: settlement was attempted but could not be completed, for example because of insufficient funds.

### `GET /transfers/{id}`

Returns the current state of a transfer.

Status codes:

- `200 OK`: transfer returned.
- `404 Not Found`: transfer does not exist or the id is not a valid UUID.
- `503 Service Unavailable`: temporary database capacity exhaustion.

## Operational Semantics

The API is designed to prefer explicit backpressure over unbounded request
admission. Under sustained overload, clients should expect either slower
responses or `503 Service Unavailable`, not generic server errors.

Clients should retry `503` responses with exponential backoff and should reuse
the same idempotency key when retrying `POST /transfers`.
