# Observability

Payments Ledger API exposes operational health and metrics through Spring Boot
Actuator and Micrometer.

## Endpoints

The public application health endpoint remains:

```text
GET /health
```

Actuator endpoints are exposed under:

```text
GET /actuator/health
GET /actuator/health/liveness
GET /actuator/health/readiness
GET /actuator/metrics
GET /actuator/prometheus
```

The Docker health check still uses `/health` so local orchestration remains
stable even if Actuator exposure changes later.

## Important Metrics

HikariCP metrics are automatically registered by Spring Boot Actuator when the
DataSource is available:

- `hikaricp_connections_active`
- `hikaricp_connections_idle`
- `hikaricp_connections_pending`
- `hikaricp_connections_timeout_total`
- `hikaricp_connections_usage_seconds`

HTTP server metrics:

- `http_server_requests_seconds_count`
- `http_server_requests_seconds_sum`
- `http_server_requests_seconds_max`

Custom settlement metrics:

- `payments_settlement_enqueued_total`
- `payments_settlement_enqueue_duplicates_total`
- `payments_settlement_completed_total`
- `payments_settlement_failed_total`
- `payments_settlement_skipped_total`
- `payments_settlement_errors_total`
- `payments_settlement_duration_seconds`
- `payments_settlement_queue_size`
- `payments_settlement_in_flight_size`
- `payments_settlement_worker_threads`

Custom HTTP admission metrics:

- `payments_http_db_permits_available`
- `payments_http_db_permits_rejected_total`
- `payments_http_db_permits_interrupted_total`

All metrics include the `application="payments-ledger-api"` tag.

## Load Test Reading

During Gatling runs, the first signals to watch are:

- `hikaricp_connections_pending`: should stay close to zero. If it climbs, HTTP
  traffic is waiting inside the pool.
- `hikaricp_connections_timeout_total`: should not increase during a stable
  test. If it increases, the API is still admitting more DB work than the pool
  can serve.
- `payments_http_db_permits_rejected_total`: may increase during overload. This
  is controlled backpressure, not an unexpected application failure.
- `payments_settlement_queue_size`: shows worker backlog. Growth means intake is
  faster than settlement.
- `payments_settlement_errors_total`: should stay flat. Growth here means
  settlement retries are being triggered by unexpected failures.
- `http_server_requests_seconds_*`: use by URI and status to separate expected
  `503` backpressure from application errors.

## Prometheus Scrape

For local inspection:

```bash
curl http://localhost:3005/actuator/prometheus
```

Example focused checks:

```bash
curl -s http://localhost:3005/actuator/prometheus | grep hikaricp_connections
curl -s http://localhost:3005/actuator/prometheus | grep payments_http_db_permits
curl -s http://localhost:3005/actuator/prometheus | grep payments_settlement
```

## Operating Target

The production target is not "no rejected requests under unlimited load". The
target is:

- no generic HTTP 500 for known capacity pressure;
- controlled `503 Service Unavailable` when the database is saturated;
- stable Hikari pending connections;
- bounded settlement queue growth;
- no unexpected settlement errors.
