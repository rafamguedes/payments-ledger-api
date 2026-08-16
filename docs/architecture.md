# Architecture

Payments Ledger API is organized around a simple ledger boundary: HTTP accepts
commands, PostgreSQL stores durable state, and a background worker settles
pending transfers.

## Component Map

- `web`: HTTP controllers, request validation, and response DTOs.
- `service`: application workflows for accounts, transfers, and statements.
- `repo`: JDBC access with explicit SQL and transaction participation.
- `worker`: asynchronous transfer settlement.
- `config`: runtime configuration, HikariCP setup, and HTTP backpressure.
- `load`: Gatling simulations for throughput and latency validation.

Operational metrics are exposed through Actuator and documented in
[observability.md](observability.md).

## Request Flow

Account creation and read endpoints execute synchronously against PostgreSQL.

Transfer creation is split into two stages:

1. The API validates the request, checks account references, and inserts a
   `pending` transfer with a unique idempotency key.
2. The settlement worker later locks the transfer and account rows, applies the
   balance movement atomically, and marks the transfer as `completed` or
   `failed`.

This keeps transfer intake fast while preserving a durable record before any
money movement is attempted.

## Settlement Flow

The worker consumes transfer ids from an in-memory queue. A scheduled sweep also
looks for pending transfers that may have been missed because of a process
restart or transient worker failure.

For each transfer, settlement runs in one database transaction:

- Lock the transfer row with `SELECT ... FOR UPDATE`.
- Re-check that the transfer is still `pending`.
- Lock payer and payee account rows in a stable global order.
- Debit the payer and credit the payee if funds are available.
- Mark the transfer as `completed` or `failed`.

The stable account lock order avoids database deadlocks between concurrent
transfers that touch the same accounts in opposite directions.

## Backpressure

HTTP traffic is limited before it enters the JDBC layer. The
`payments.http.db-permits` setting caps the number of DB-bound requests that may
run at the same time. This prevents an unbounded queue inside HikariCP and keeps
overload behavior easier to reason about.

Requests wait for a permit only up to `payments.http.db-permit-timeout-ms`. When
that timeout is reached, the API returns a controlled `503 Service Unavailable`
instead of allowing request latency to grow without a clear bound.

The worker has an independent `payments.worker.threads` limit. This should stay
conservative because worker settlement and HTTP requests share the same
PostgreSQL connection pool.

HikariCP is configured through `payments.datasource.*`:

- `maximum-pool-size`: upper bound of concurrent database connections.
- `minimum-idle`: idle connections kept ready for traffic.
- `connection-timeout-ms`: maximum wait for a connection before failing.

## Persistence Boundaries

PostgreSQL is the source of truth. Schema changes are managed by Flyway
migrations in `src/main/resources/db/migration`, so database evolution is
versioned with the application code.

The in-memory queue is only an acceleration mechanism; correctness depends on
persisted `pending` rows and the sweep process, not on the queue surviving
restarts.

Balances are stored as integer cents. Transfer settlement updates balances and
transfer status in the same transaction so clients never observe a completed
transfer without its corresponding balance movement.

## Current Bottlenecks

Recent Gatling runs showed PostgreSQL connection pressure as the dominant
failure mode. Saturation previously surfaced as connection timeouts and generic
server errors.

The production direction is:

- Keep request concurrency below effective database capacity.
- Reserve enough pool capacity for worker settlement without starving the API.
- Return controlled `503 Service Unavailable` responses for temporary capacity
  exhaustion.
- Add indexes and pagination before statement history grows large.

## Future Evolution

Near-term improvements:

- Persist an outbox or durable worker queue for stronger recovery guarantees.
- Add structured logs for request correlation and settlement retries.
- Add authentication and authorization before exposing the API outside a trusted
  environment.
- Define service-level objectives for latency, successful intake rate, and
  settlement delay.
