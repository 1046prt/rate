# Rate Limiter Service

A **Spring Boot** (Java 21) micro-service that provides rate-limiting as a service (RLaaS). It implements five classic algorithms with **Redis** for distributed state, using atomic Lua scripts so concurrent requests are safe. Built to survive Redis outages: every Redis call runs behind a circuit breaker with a fail-open policy (see [Resilience](#resilience)). Includes optional API-key auth, TLS profiles, Prometheus metrics, and Testcontainers integration tests.

## Features

- Five algorithms: Fixed Window, Sliding Window Log, Sliding Window Counter, Token Bucket, Leaky Bucket — see [Algorithms](#algorithms) for the exact semantics.
- Configurable limits and windows per algorithm via `application.yml` or environment variables.
- **Resilient to Redis outages**: circuit breaker + 500 ms command timeout + fail-open (never 500s), with dedicated failure metrics.
- Optional API-key authentication (`X-Api-Key` header); published Docker config fails closed, see [Authentication](#authentication).
- Micrometer metrics with a Prometheus exporter.
- TLS profiles (`dev`/`prod`) using an embedded PKCS12 keystore; recommended edge TLS via Caddy (verified working).
- Swagger UI at `/swagger-ui.html`.
- Docker Compose bringing up the app + Redis with healthchecks (optional Redis AUTH password).
- Redis **AOF persistence** (everysec) + data volumes on master and replica — state survives restarts and failover.
- Horizontal scaling: stateless app replicas sharing Redis state, Caddy load balancing, Prometheus DNS discovery per replica (all verified live).
- Testcontainers-based integration tests, including a Redis-down fail-open test (require a running Docker daemon).
- GitHub Actions CI: full `mvn clean verify` (tests included) + Docker image build + **Trivy vulnerability scan + CycloneDX SBOM**.
- Operations runbook: [`RUNBOOK.md`](RUNBOOK.md) — per-alert procedures, failover drill, backup/restore, scaling, upgrades.

## Build & Run

Requires Java 21 and Maven. Redis is needed at runtime — either a local one (`SPRING_DATA_REDIS_HOST`/`SPRING_DATA_REDIS_PORT`) or the compose container.

### Maven

```bash
mvn clean package              # builds the jar (skips tests)
java -jar target/rate-limiter-service-0.0.1-SNAPSHOT.jar
# reachable at http://localhost:8080, Redis at localhost:6379
```

Run the full test suite (starts Redis via Testcontainers, so Docker must be running):

```bash
mvn clean verify
```

### Docker Compose

```bash
cp .env.example .env           # then set a strong RATELIMITER_API_KEY
docker compose up --build      # starts the app (8080) and a Redis container
```

The app refuses to start under compose unless `RATELIMITER_API_KEY` is set — this is deliberate, see [Authentication](#authentication). Optionally set `REDIS_PASSWORD` in `.env` to start Redis with `--requirepass` (the app authenticates automatically).

For the high-availability Redis topology (master + replica + Sentinel) and the observability stack, always pass the **full compose file set** so Compose reconciles every container:

```bash
docker compose -f docker-compose.yml -f docker-compose.redis-ha.yml -f docker-compose.monitoring.yml up -d --build
```

For a **production Sentinel quorum** (3 sentinels, quorum 2 — failover decided by a majority, not one process) add `docker-compose.sentinel-ha.yml`. The app learns all three sentinel nodes, so losing any one never breaks topology discovery:

```bash
docker compose -f docker-compose.yml -f docker-compose.redis-ha.yml \
  -f docker-compose.sentinel-ha.yml -f docker-compose.monitoring.yml up -d
```

### TLS (dev / prod profiles)

**Recommended: terminate TLS at the edge proxy** (the app stays plain HTTP on the internal network):

```bash
docker compose -f docker-compose.yml -f docker-compose.redis-ha.yml \
  -f docker-compose.monitoring.yml -f docker-compose.tls.yml up -d --build --scale app=2
# https://localhost (Caddy issues an internal cert; for a real domain set
# TLS_DOMAIN in .env and remove `tls internal` from caddy/Caddyfile for
# automatic Let's Encrypt certificates)
```

The TLS overlay is load-tested (see [Load testing](#load-testing)) and fails over: HTTPS serves through Caddy, the app publishes **no host port** in this mode (edge-only exposure), and Caddy round-robins between scaled replicas (kill a replica and traffic continues on the survivor). In TLS mode the app is NOT reachable on `http://localhost:8080` — use `https://localhost`.

Alternatively the app can serve TLS itself via the `dev`/`prod` profiles, using `src/main/resources/keystore.p12`. It is checked in for development convenience only — regenerate it for any real deployment and keep the password in a secret store:

```bash
keytool -genkeypair -alias ratelimiter -keyalg RSA -keysize 2048 -validity 3650 \
  -storetype PKCS12 -keystore src/main/resources/keystore.p12 \
  -storepass changeit -keypass changeit \
  -dname "CN=localhost, OU=Rate Limiter, O=Example, C=US"
```

```bash
java -jar target/rate-limiter-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
# HTTPS on port 8080 (keystore password `changeit`)
```

The `prod` profile refuses to start without `KEYSTORE_PASSWORD` (no fallback) — inject it from your secret store (Docker secrets / K8s secret / vault), never from the repo. Health details are hidden in `prod` and only `health,metrics,prometheus` are exposed.

## Authentication

Auth is optional at the application level and controlled by `ratelimiter.apiKey` (env: `RATELIMITER_API_KEY`):

- **Empty / unset** — open access. Every request on `/api/v1/**` is authenticated as a generic API client.
- **Set** — every request to `/api/v1/**` must send the value in the `X-Api-Key` header; otherwise a 401 `{"error":"Invalid API key"}` is returned. Invalid attempts increment the `ratelimiter_auth_invalid_api_key_total` counter.

Actuator health/prometheus endpoints and Swagger remain public in both modes.

> **Compose default (fail-closed):** `docker-compose.yml` requires `RATELIMITER_API_KEY` from your environment (`.env`) and errors out if it is missing, so a fresh clone can never silently run with authentication disabled. Run `openssl rand -hex 32` to generate a key. Local Maven runs remain open by default (`apiKey: ''` in `application.yml`).

## API

### Check rate limit

`POST /api/v1/check`

```json
{
  "clientId": "user123",
  "algorithm": "FIXED"
}
```

`clientId` is required; `algorithm` is one of `FIXED`, `SLIDING_LOG`, `SLIDING_COUNTER`, `TOKEN_BUCKET`, `LEAKY_BUCKET` (case-insensitive).


```json
{ "allowed": true, "message": "Request allowed" }
```

**429 – rejected** (plus the same rate-limit headers as a 200)

```json
{ "allowed": false, "message": "Rate limit exceeded for client user123" }
```

**400** – missing/invalid `clientId` or `algorithm`, or an unknown algorithm name.

Response headers on both 200 and 429:

- `X-RateLimit-Limit` – configured limit for the algorithm.
- `X-RateLimit-Remaining` – remaining requests in the current window (200 only; estimated for `SLIDING_COUNTER`, drained water for `LEAKY_BUCKET`).
- `Retry-After` – seconds until the window resets (429 only).
- `X-Request-Id` – unique correlation id generated per request.

## Algorithms

All state transitions run atomically in Redis Lua scripts; no `INCR`-without-`EXPIRE` races.

| Algorithm | Exact? | Semantics |
|---|---|---|
| `FIXED` | yes | Per-client counter, atomic `INCR`+`EXPIRE`; resets at each window boundary. |
| `SLIDING_LOG` | yes | Redis sorted set of request timestamps; expired entries are trimmed, then the window count is checked and the request appended — all in one script. |
| `SLIDING_COUNTER` | approximate | Weights the previous window's counter by how much of the current window has elapsed: `estimated = prevCount × (1 − elapsed/window) + currentCount`. |
| `TOKEN_BUCKET` | yes | Bucket holds up to `limit` tokens and refills by the full capacity each window (lazy refill on access); a request consumes one token. |
| `LEAKY_BUCKET` | yes | **Water-level model (not a queue):** water leaks out of the bucket at one unit per window, and a request is only allowed while `water < capacity`. When the bucket is full, requests are rejected until a leak period elapses. |

Note the leaky-bucket rewrite: it tracks how much in-flight "water" has drained over time rather than buffering a FIFO queue of requests — rejection happens immediately when the bucket is full, there is no waiting queue.

## Resilience

The rate limiter must not become a hard dependency of your API. Every Redis operation is guarded by a `RedisGuard` (Resilience4j circuit breaker + the 500 ms Lettuce command timeout from `spring.data.redis.timeout`):

- **Circuit open / Redis down / command timeout** → the limiter **fails open**: the request is allowed and logged, the breaker records the failure, and `ratelimiter.redis.fallback` is incremented. The API stays up during a Redis outage instead of returning 500s.
- **Recovery** — after `waitDurationInOpenState` (default 30 s) the breaker probes Redis in half-open state and closes again once calls succeed.
- **Tunable** via the `resilience4j.circuitbreaker.instances.redis.*` block in `application.yml` (sliding window size, failure-rate threshold, open-state duration).
- Alert on `ratelimiter_redis_fallback_total` (or the breaker's own `resilience4j_circuitbreaker_*` metrics) to detect limiter degradation.

> **Trade-off:** fail-open means clients can exceed their limit while Redis is down. That is the deliberate choice — availability over strict rate limiting. If you need fail-closed instead, treat a `null` guard result in the limiters as "reject".

## Configuration

Base config in `src/main/resources/application.yml` (profile overrides in `application-dev.yml` / `application-prod.yml`):

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password: ${REDIS_PASSWORD:}   # optional Redis AUTH
      timeout: 500ms                  # bound on every Redis command
      ssl:
        enabled: ${REDIS_SSL:false}   # optional TLS to Redis

ratelimiter:
  apiKey: ''            # optional; see Authentication
  limits:
    default: 100        # per-algorithm overrides possible, e.g. TOKEN_BUCKET: 50
  windows:
    default: 60s        # per-algorithm overrides, e.g. SLIDING_LOG: PT2M
```

> **Gotcha:** durations must use ISO-8601/Spring units (`60s`, `PT1M`, `2h`). A bare number (`60`) is interpreted as **milliseconds**, not seconds.

Available environment variable overrides:

| Variable | Meaning |
|---|---|
| `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` | Redis connection (default `localhost:6379`) |
| `REDIS_PASSWORD` | Redis AUTH password; blank = no auth |
| `REDIS_SSL` | `true` to use TLS to Redis |
| `RATELIMITER_API_KEY` | API key required on `/api/v1/**`; blank = open access |
| `RATELIMITER_DEFAULT_LIMIT` | Default per-client limit (prod default 200) |
| `RATELIMITER_DEFAULT_WINDOW_SEC` | Default window in seconds (prod default 30) |
| `KEYSTORE_PASSWORD` | Keystore password for the `prod` profile |
| `RECEIVER_URL` | Alertmanager webhook receiver URL (rendered into its config at start) |
| `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD` | Grafana admin credentials (default `admin`/`admin`) |

### Redis production hardening

- **AUTH/ACL**: set `REDIS_PASSWORD` (or `SPRING_DATA_REDIS_USERNAME`/`SPRING_DATA_REDIS_PASSWORD` for ACL users). Compose applies `--requirepass` automatically when set — on the standalone Redis **and** on the HA replica (`--masterauth`) and Sentinel (`sentinel auth-pass`), so the whole topology authenticates. Verified live: unauthenticated `PING` gets `NOAUTH`, and failover works with auth enabled.
- **TLS**: `REDIS_SSL=true` (and the standard `SPRING_DATA_REDIS_SSL_*` properties) for encrypted transport.
- **High availability**: run the Sentinel topology (master + replica + Sentinel) with the additive compose file:
  ```bash
  docker compose -f docker-compose.yml -f docker-compose.redis-ha.yml up -d --build
  ```
  The app connects via `SPRING_DATA_REDIS_SENTINEL_MASTER`/`SPRING_DATA_REDIS_SENTINEL_NODES`; failover is automatic and was live-tested (killed master → replica promoted in ~15 s → app kept serving, limits kept enforcing, old master rejoined as replica on restart). Sentinel must monitor the master by **static IP or stable VIP, never a hostname** — a stopped container's DNS name disappears and breaks the failover.
- **State across failover**: with a warm replica (as in this topology), rate-limit state survives failover because the promoted node holds replicated data — limits keep enforcing seamlessly. State resets only if the new primary comes up cold (e.g., full cluster loss). Either way limits re-learn instantly; both behaviors are acceptable, just be aware of which one you get.
- **Persistence**: AOF is enabled (`--appendonly yes --appendfsync everysec`) on the master and the replica, and both hold named data volumes (`redis-data`, `redis-replica-data`) — state survives container restarts and recreation. Back up regularly (see the [runbook](RUNBOOK.md#backup--restore) for the exact BGSAVE + tar procedure).
- **Horizontal scaling**: the app is stateless (all state lives in Redis), so replicas scale cleanly:
  ```bash
  docker compose -f docker-compose.yml -f docker-compose.redis-ha.yml \
    -f docker-compose.monitoring.yml -f docker-compose.tls.yml up -d --scale app=2
  ```
  Caddy round-robins across replicas (`caddy/Caddyfile`), Prometheus scrapes each replica via DNS discovery, and each appears as its own `container` in Loki. See the [runbook](RUNBOOK.md#scaling).

## Monitoring & Alerting

Run the full observability stack (Prometheus, Alertmanager, Loki, promtail, Grafana) alongside the app:

```bash
docker compose -f docker-compose.yml -f docker-compose.redis-ha.yml -f docker-compose.monitoring.yml up -d --build
# Prometheus:   http://localhost:9090 (alerts at /alerts, targets at /targets)
# Alertmanager: http://localhost:9093
# Grafana:      http://localhost:3000 (admin / GRAFANA_ADMIN_PASSWORD — REQUIRED env, no default)
# Loki API:     http://localhost:3100
```

All monitoring ports bind to **127.0.0.1 only** (not exposed to the LAN). Grafana fails closed: `docker compose up` errors out unless `GRAFANA_ADMIN_USER`/`GRAFANA_ADMIN_PASSWORD` are set in `.env` (same pattern as the API key).

`monitoring/alerts.yml` ships six rules (validated against the live deployment):

| Alert | Condition | Severity |
|---|---|---|
| `RateLimiterDown` | `up == 0` for 1m | critical |
| `RedisCircuitBreakerOpen` | breaker `redis` open for 1m | critical |
| `RedisFailingOpen` | `ratelimiter_redis_fallback_total` increasing | warning |
| `HighThrottleRate` | >50% of requests are 429 for 5m | warning |
| `HighApiKeyFailures` | sustained bad API keys (credential stuffing) | warning |
| `HighLatencyP99` | p99 `http_server_requests_seconds` > 1s for 5m | warning |

- **Logs**: promtail ships every `rate-*` container's stdout to Loki via the Docker socket. Logs are labeled with `container`, `container_id`, `service` (compose service name) and `job="containers"` — e.g. query `{container="rate-app-1"}` in Grafana Explore or at `http://localhost:3100/loki/api/v1/query_range?query=%7Bcontainer%3D%22rate-app-1%22%7D`.
- **Dashboards**: Grafana auto-provisions a `Rate Limiter` dashboard (`monitoring/grafana/dashboards/rate-limiter.json`): request rate per algorithm, throttled (429) rate, throttle ratio, fail-open rate, breaker state, p95/p99 latency, 5xx rate.
- **Alertmanager receiver**: `monitoring/alertmanager.yml` is a template whose `__RECEIVER_URL__` placeholder is replaced at container start from the `RECEIVER_URL` environment variable — point it at your Slack/PagerDuty/Opsgenie webhook instead of editing the config:
  ```bash
  RECEIVER_URL=https://hooks.slack.com/services/... docker compose ... up -d
  ```
  Routing groups by alert/severity/instance, pages criticals immediately (10s, 1h repeat) and adds an inhibit rule so `RedisFailingOpen` is silenced while `RedisCircuitBreakerOpen` is open.

Full operational procedures (alert-by-alert responses, failover drill, backup/restore, scaling, upgrade, incident template) live in [`RUNBOOK.md`](RUNBOOK.md).

## SLOs (defaults)

| SLO | Definition | Backing alert |
|---|---|---|
| Availability | 99.9% of `/api/v1/check` calls succeed (non-5xx) | `RateLimiterDown`, `RedisCircuitBreakerOpen` |
| Latency | p95 < 50 ms, p99 < 1 s | `HighLatencyP99` |
| Correctness | no 5xx due to limiter failure; fail-open only during Redis outages | `RedisFailingOpen` |
| Error budget | 429s count toward availability error budget; track `ratelimiter_requests_rejected_total / total` | `HighThrottleRate` |

## Load testing

- **JUnit soak** (excluded from the default run so CI stays fast):
  ```bash
  mvn test -Dtest=SoakTest "-Dexcluded.test.groups="
  # prints e.g. "SOAK: 13027 calls in PT10S (1303 req/s), 0 failures"
  ```
- **k6** (for sustained production-scale runs — not bundled, install from k6.io, or use the Docker image):
  ```bash
  k6 run --env API_KEY=... --vus 100 --duration 2m loadtest/check.js
  # or, against the TLS edge from inside the compose network:
  docker run --rm --network rate_default \
    -v "$PWD/loadtest:/loadtest" -e API_KEY=... -e BASE_URL=https://localhost \
    -e K6_INSECURE_SKIP_TLS_VERIFY=true grafana/k6 run /loadtest/check.js
  ```
  Fails (exit ≠ 0) if the **5xx rate ≥ 0.1%**, p95 ≥ 100 ms (edge), or checks fail. 429 responses are expected (the limiter doing its job) and are reported separately as `http_429`.

**Measured on the production shape** (TLS edge → 2 app replicas → 3-sentinel Redis HA, 100 VUs ramping, 2 min):

| Metric | Result |
|---|---|
| Throughput | ~2,400 req/s (290k requests in 2 min) |
| Server errors (5xx) | **0.00%** (k6 and Prometheus agree) |
| p95 latency | 44.9 ms at the edge (k6), 13.5 ms in-app (Prometheus) |
| Throttle ratio | 95.2% (deliberate — per-client windows far below load; k6 and Prometheus agree) |
| Replica balance | ~50/50 across both app instances (Prometheus per-instance rates) |
| Redis fail-open | 0 (`ratelimiter_redis_fallback_total` stayed 0 for the whole run) |

## Metrics

Prometheus endpoint: `http://localhost:8080/actuator/prometheus` (also `health`, `info`, `metrics`).

| Metric | Meaning |
|---|---|
| `ratelimiter_requests_total` | All requests to `/api/v1/check` |
| `ratelimiter_requests_allowed` | Requests allowed by the limiter |
| `ratelimiter_requests_rejected` | Requests rejected (HTTP 429) |
| `ratelimiter_algorithm_calls_total{algorithm="FIXED"}` | Calls per algorithm |
| `ratelimiter_auth_invalid_api_key_total` | Requests rejected by API-key auth (401) |
| `ratelimiter_redis_fallback_total` | Redis calls that failed and were allowed through (fail-open) — **alert on this** |
| `resilience4j_circuitbreaker_*` | Circuit-breaker state (`state` gauge, calls, failure rate) for the `redis` breaker |

Health details are always shown in the default profile; the `prod` profile hides them.

## Testing

```bash
mvn test        # 34 tests: unit + concurrency tests for all 5 algorithms, resilience tests,
                # Redis-down fail-open test, controller e2e
mvn verify      # full build including tests (Docker required for Testcontainers)
```

## CI

`.github/workflows/ci.yml` runs on every push/PR:

1. JDK 21 + Maven setup (cached dependencies).
2. `mvn --batch-mode clean verify` — Testcontainers uses the runner's Docker daemon.
3. `docker build` to prove the Dockerfile is healthy.
4. **Trivy scan** of the built image (fails on unfixed HIGH/CRITICAL) — the scan found and blocked CVE-2026-59901 (netty-codec) and base-image Alpine CVEs during development; the Dockerfile now pins the netty fix and runs `apk upgrade` at build time.
5. **CycloneDX SBOM** generated from the image and uploaded as a CI artifact.
6. On push to `main`, **publish to GHCR** as `ghcr.io/<owner>/rate-limiter-service:<sha>` + `:latest` (needs the repo's `packages: write` permission, granted automatically to `GITHUB_TOKEN`).

Dependabot (`.github/dependabot.yml`) opens weekly PRs for Maven, GitHub Actions and Docker image updates.

## Logging

Logback config (`logback-spring.xml`) logs **structured JSON** to the console with MDC context (including the `X-Request-Id` correlation id). Ship it to any JSON log collector as-is.

## Stack notes

- **Spring Boot 3.5.x** (supported line) on Java 21, Spring Data Redis (Lettuce), Resilience4j 2.x, Micrometer, Testcontainers.
- Boot 4.x is the next major line; the app deliberately pins the current 3.x line — plan a migration when convenient, nothing in this codebase depends on Boot-3-specific internals beyond standard auto-configuration.

## License

MIT
