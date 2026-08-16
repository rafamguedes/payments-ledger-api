# Payments Ledger API

Payments Ledger API is a Java 21 / Spring Boot service for account balances,
idempotent transfer intake, and asynchronous transfer settlement.

The project is no longer scoped as a short-lived benchmark prototype. Its
goal is to evolve into a production-oriented payment ledger service with clear
API contracts, operational configuration, load testing, and predictable failure
behavior under pressure.

## Scope

The service manages accounts and transfers between accounts.

- `POST /accounts` creates an account with an initial balance.
- `POST /transfers` accepts a transfer request and returns immediately with a
  `pending` transfer.
- A background settlement worker completes or fails pending transfers.
- `GET /transfers/{id}` returns the current transfer state.
- `GET /accounts/{id}/statement` returns the account balance and completed
  transfers.
- `GET /health` is used by Docker and external health checks.

Money is represented as integer cents using `BIGINT`. Floating point values are
not used for balances or transfer amounts.

The HTTP contract is documented in [docs/api-contract.md](docs/api-contract.md).
Architecture notes are documented in [docs/architecture.md](docs/architecture.md).
Observability notes are documented in [docs/observability.md](docs/observability.md).

## Architecture

The code is intentionally small and explicit:

- `com.payments.web`: HTTP controllers and DTOs.
- `com.payments.service`: application use cases.
- `com.payments.repo`: JDBC repositories.
- `com.payments.worker`: asynchronous settlement worker.
- `com.payments.config`: database and runtime configuration.
- `com.payments.load`: Gatling simulations.

Transfers are first inserted as `pending` with a unique idempotency key. The
settlement worker later locks the transfer row and both account rows in a stable
order before applying balance updates. This keeps settlement atomic and prevents
double settlement.

The API and worker share the same PostgreSQL database. Backpressure is applied
before JDBC access so bursty HTTP traffic does not create an unbounded queue
inside HikariCP.

## Requirements

- Docker and Docker Compose
- Java 21 and Maven, if running without Docker
- Optional: a local PostgreSQL instance if not using Compose
- PostgreSQL schema changes are managed by Flyway migrations in
  `src/main/resources/db/migration`.

## Run Locally

Start the application and PostgreSQL:

```bash
docker compose up --build
```

The API will be available at:

```text
http://localhost:3005
```

Health check:

```bash
curl http://localhost:3005/health
```

Actuator readiness and Prometheus metrics:

```bash
curl http://localhost:3005/actuator/health/readiness
curl http://localhost:3005/actuator/prometheus
```

Create accounts and a transfer:

```bash
curl -X POST http://localhost:3005/accounts \
  -H "Content-Type: application/json" \
  -d '{"id": "acc-1", "balance": 100000}'

curl -X POST http://localhost:3005/accounts \
  -H "Content-Type: application/json" \
  -d '{"id": "acc-2", "balance": 0}'

curl -X POST http://localhost:3005/transfers \
  -H "Content-Type: application/json" \
  -d '{"payerId": "acc-1", "payeeId": "acc-2", "amount": 2500, "idempotencyKey": "abc-123"}'
```

Read data:

```bash
curl http://localhost:3005/transfers/<transfer-id>
curl http://localhost:3005/accounts/acc-1/statement
```

## Configuration

The main runtime configuration lives in `src/main/resources/application.yml`.

Important settings:

- `DATABASE_URL`: PostgreSQL connection string. Both `postgres://...` and
  `jdbc:postgresql://...` formats are supported.
- `payments.http.db-permits`: maximum number of DB-bound HTTP requests allowed
  to enter JDBC concurrently.
- `payments.worker.threads`: number of background settlement consumers.
- `payments.worker.sweep-interval-ms`: how often the worker scans for pending
  transfers that were not in the in-memory queue.
- `payments.datasource.maximum-pool-size`: HikariCP maximum connection count.
- `payments.datasource.minimum-idle`: HikariCP minimum idle connection count.
- `payments.datasource.connection-timeout-ms`: maximum wait for a database
  connection before returning a capacity error.

The default Docker Compose database URL is:

```text
postgres://payments:payments@postgres:5432/payments
```

If an old local Compose volume was created with previous database credentials,
the PostgreSQL volume must be recreated before the new credentials will apply.
The same applies to local volumes created before Flyway was introduced: recreate
the local database volume or baseline it manually before starting this version.

## Database Migrations

Flyway runs automatically when the Spring Boot application starts.

Migration files live in:

```text
src/main/resources/db/migration
```

The initial schema is defined by:

```text
V1__create_ledger_schema.sql
```

Future schema changes should be added as new versioned files, for example
`V2__add_transfer_metadata.sql`. Existing migration files should not be edited
after they have been applied to a shared environment.

## Load Testing

Gatling is the official load testing tool for this project.

Available simulations:

- `com.payments.load.ThroughputSimulation`
- `com.payments.load.LatencySimulation`

Run throughput with local Maven:

```bash
mvn gatling:test \
  -Dgatling.simulationClass=com.payments.load.ThroughputSimulation \
  -DbaseUrl=http://localhost:3005
```

Run latency with local Maven:

```bash
mvn gatling:test \
  -Dgatling.simulationClass=com.payments.load.LatencySimulation \
  -DbaseUrl=http://localhost:3005
```

Run via Docker when Maven is not installed locally:

```bash
docker run --rm \
  -v "$PWD:/workspace" \
  -v maven-repo:/root/.m2 \
  -w /workspace \
  maven:3.9.9-eclipse-temurin-21 \
  mvn gatling:test \
  -Dgatling.simulationClass=com.payments.load.ThroughputSimulation \
  -DbaseUrl=http://host.docker.internal:3005
```

Useful Gatling parameters:

- `-Daccounts=50`: number of accounts seeded before the test.
- `-DseedBalance=100000000`: starting balance for seeded accounts.
- `-DtargetRps=200`: target request rate for `ThroughputSimulation`.
- `-DconcurrentUsers=50`: concurrent users for `LatencySimulation`.

Reports are generated under:

```text
target/gatling/
```

During a load test, inspect runtime pressure with:

```bash
curl -s http://localhost:3005/actuator/prometheus | grep hikaricp_connections
curl -s http://localhost:3005/actuator/prometheus | grep payments_settlement
```

The key saturation signals are Hikari pending connections, Hikari connection
timeouts, settlement queue size, settlement errors, and HTTP status distribution.

## Current Performance Notes

Recent load tests showed the main bottleneck is PostgreSQL connection pressure:

```text
Connection is not available, request timed out after 5000ms
```

The production direction is to avoid HTTP 500s caused by saturation by applying
backpressure before HikariCP, reducing worker/database contention, and improving
statement queries as data volume grows.

Near-term tuning priorities:

- Keep DB-bound HTTP concurrency below the effective Hikari/PostgreSQL capacity.
- Keep worker concurrency conservative so settlement does not starve the API.
- Optimize account statements to avoid expensive scans as transfers accumulate.
- Convert expected saturation into controlled `429` or `503` responses instead
  of unhandled server errors.
