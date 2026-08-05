# Architecture deep-dive

Detailed diagrams for the Rate Limiter Service. The recruiter-friendly overview lives in the [README](../README.md).

## 1. System architecture

```mermaid
flowchart LR
    subgraph Internet
        C[Client / API consumer]
    end

    subgraph "Edge"
        CAD["Caddy :80/:443\nTLS termination, auto Let's Encrypt\nround-robin LB to app"]
    end

    subgraph "Application tier — stateless replicas"
        A1["App replica 1 :8080"]
        A2["App replica 2 :8080"]
        GUARD["RedisGuard\nResilience4j breaker\n500ms Lettuce timeout\nFAIL-OPEN policy"]
    end

    subgraph "Redis HA tier"
        S1["Sentinel 1 :26379"]
        S2["Sentinel 2 :26379"]
        S3["Sentinel 3 :26379"]
        M["Redis master :6379\nAOF everysec\nvolume: redis-data"]
        R["Redis replica :6379\nAOF everysec\nvolume: redis-replica-data"]
    end

    subgraph "Observability — loopback-only (127.0.0.1)"
        PROM["Prometheus :9090"]
        AM["Alertmanager :9093"]
        GRAF["Grafana :3000"]
        LOKI["Loki :3100"]
        PT["promtail (docker socket)"]
    end

    C -->|"HTTPS"| CAD
    CAD --> A1
    CAD --> A2
    A1 --> GUARD
    A2 --> GUARD
    GUARD --> M
    M --> R
    S1 --> M
    S2 --> M
    S3 --> M
    A1 -. "sentinel discovery" .-> S1
    A1 -. "sentinel discovery" .-> S2
    A1 -. "sentinel discovery" .-> S3
    A2 -. "sentinel discovery" .-> S1
    A2 -. "sentinel discovery" .-> S2
    A2 -. "sentinel discovery" .-> S3
    M -. "failover promotes" .-> R
    A1 -->|"/actuator/prometheus"| PROM
    A2 -->|"/actuator/prometheus"| PROM
    A1 -->|"JSON logs"| PT
    A2 -->|"JSON logs"| PT
    PROM --> AM
    PROM --> GRAF
    LOKI --> GRAF
    AM -->|"webhook (RECEIVER_URL)"| WEBHOOK[Slack / PagerDuty / Opsgenie]
```

Key properties:

- **Single point of entry**: Caddy. In TLS mode the app publishes no host port — nothing reaches replicas except through the edge.
- **Stateless replicas**: all limit state lives in Redis; `--scale app=N` is the only scaling knob.
- **Loopback-only monitoring**: Prometheus, Alertmanager, Loki, Grafana bind `127.0.0.1`, so the observability stack is not LAN-exposed. Grafana requires `GRAFANA_ADMIN_USER`/`GRAFANA_ADMIN_PASSWORD` — compose fails closed without them.

## 2. Request flow

```mermaid
sequenceDiagram
    participant C as Client
    participant CAD as Caddy
    participant A as App replica
    participant G as RedisGuard
    participant RD as Redis

    C->>CAD: POST /api/v1/check {clientId, algorithm}
    Note over C: + X-Api-Key (when enabled)
    CAD->>A: reverse_proxy (round_robin)
    A->>A: authenticate API key (if configured)
    A->>G: guarded Redis call (breaker state?)
    alt breaker closed
        G->>RD: EVALSHA <algorithm Lua script>
        Note over RD: single atomic script: read state,\napply transition, set TTL, return decision
        RD-->>G: {allowed: boolean, remaining, retryAfter}
    else breaker open / Redis down / timeout
        G-->>A: fail-open → allow
        Note over G: ratelimiter_redis_fallback_total++
    end
    A-->>CAD: 200 or 429 (+ X-RateLimit-* headers, X-Request-Id)
    CAD-->>C: HTTPS response
```

The `RedisGuard` wraps every Redis operation with a Resilience4j circuit breaker (sliding window 10, 50% failure threshold, 30 s open state, 3 half-open probes) plus the 500 ms Lettuce command timeout. Two escape hatches, both fail-open.

## 3. Redis Sentinel failover

```mermaid
sequenceDiagram
    participant S1 as Sentinel 1
    participant S2 as Sentinel 2
    participant S3 as Sentinel 3
    participant M as Master 172.28.0.10
    participant R as Replica 172.28.0.6
    participant A as App replicas

    S1->>M: PING
    S2->>M: PING
    S3->>M: PING
    M--x S1: (master dies)
    M--x S2:
    M--x S3:
    Note over S1: marks master ODOWN (5s down-after)
    S1->>S2: vote: start failover? (quorum 2)
    S2->>S1: yes
    S1->>R: SLAVEOF NO ONE
    Note over R: promoted, config-epoch 1
    A->>S1: get-master-addr-by-name mymaster
    A->>S2: get-master-addr-by-name mymaster
    A->>S3: get-master-addr-by-name mymaster
    S1-->>A: 172.28.0.6:6379
    S2-->>A: 172.28.0.6:6379
    S3-->>A: 172.28.0.6:6379
    A->>R: new master — limits keep enforcing
    Note over M: old master restarts
    S1->>M: reconfigure → replica of 172.28.0.6
    M->>R: PSYNC full resync
```

Verified drill (`RUNBOOK.md` → failover drill):

1. `docker stop rate-sentinel-3-1` — 2/3 sentinels still form the quorum.
2. `docker stop rate-limiter-redis` — master dies with only 2 sentinels alive.
3. Failover completed; app kept serving 200/429 throughout.
4. `docker compose up -d` — old master rejoins as a replica, `master-link-status: ok`, offsets catch up.

Requirements baked into the compose files:

- Sentinels monitor the master by **static IP** (`172.28.0.10`), never a hostname — a stopped container's DNS name vanishes, which breaks monitoring.
- All three sentinels share the same entrypoint script (`monitoring/sentinel-entrypoint.sh`), quorum via `SENTINEL_QUORUM` (1 in dev, 2 with `docker-compose.sentinel-ha.yml`).
- The app learns all three sentinel nodes (`sentinel:26379,sentinel-2:26379,sentinel-3:26379`) so losing one never breaks topology discovery.
- Redis AUTH is propagated: `--requirepass` on master/replica, `--masterauth` on the replica, `sentinel auth-pass` on sentinels.

## 4. Rate limiter algorithm flow

```mermaid
flowchart TD
    REQ["POST /api/v1/check\n{clientId, algorithm}"] --> AUTH{API key set?\nX-Api-Key matches?}
    AUTH -->|no| E401["401 Invalid API key"]
    AUTH -->|yes| PICK["resolve algorithm bean\n(FIXED / SLIDING_LOG / SLIDING_COUNTER /\nTOKEN_BUCKET / LEAKY_BUCKET)"]
    PICK --> LUA["compile + cache Lua script per algorithm"]
    LUA --> BREAKER{RedisGuard: breaker closed?}
    BREAKER -->|open / timeout / Redis down| FO["FAIL-OPEN\nallow request\nratelimiter_redis_fallback_total++"]
    BREAKER -->|closed| EXEC["EVALSHA (atomic)"]
    EXEC --> DEC{decision}
    DEC -->|allow| OK["200 allowed:true\nX-RateLimit-Remaining"]
    DEC -->|reject| TOO["429 allowed:false\nRetry-After"]
    OK --> MET["ratelimiter_requests_allowed_total\nhttp_server_requests_seconds"]
    TOO --> MET2["ratelimiter_requests_rejected_total\nhttp_server_requests_seconds"]
```

### What each algorithm does atomically in Redis

| Algorithm | Redis data | Script steps |
|---|---|---|
| FIXED | `rl:{clientId}:fixed:{windowStart}` | INCR + EXPIRE (first call sets TTL) |
| SLIDING_LOG | sorted set `rl:{clientId}:log:{algo}` | ZREMRANGEBYSCORE (expired), ZCARD, ZADD |
| SLIDING_COUNTER | two counters + last-window timestamp | weighted estimate `prev×(1−elapsed/window)+current` |
| TOKEN_BUCKET | hash: `tokens`, `lastRefill` | compute refill since last access, consume 1 |
| LEAKY_BUCKET | hash: `water`, `lastLeak` | drain by elapsed windows, allow if `water < capacity` |

## 5. CI/CD pipeline

```mermaid
flowchart LR
    EV{"event"}
    EV -->|push / PR| J1
    EV -->|weekly schedule| DEP[".github/dependabot.yml\nopens update PRs"]

    subgraph J1["Job 1: build"]
        S1["JDK 21 + Maven cache"] --> S2["mvn clean verify — 34 tests\n(Testcontainers: Docker daemon)"]
    end

    subgraph J2["Job 2: image + security"]
        S3["docker build"] --> S4["Trivy scan image\nHIGH/CRITICAL → exit 1"]
        S4 --> S5["CycloneDX SBOM from image"]
    end

    subgraph J3["Job 3: publish"]
        S6["docker login GHCR (GITHUB_TOKEN)"] --> S7["push :<sha> + :latest"]
    end

    J1 --> J2
    J2 -->|"push to main"| J3
    J2 -->|"PR (non-main)"| END["done"]
    J3 --> END
    DEP --> EV
```

Why an image-based SBOM: Trivy's `fs` mode hit Maven Central rate limits during development; scanning the built image produces the same CycloneDX inventory with one source of truth.

## Data flow / ports matrix

| Port | Service | Bind | Purpose |
|---|---|---|---|
| 80 / 443 | Caddy | host | HTTPS edge (TLS mode) |
| 8080 | app | **none in TLS mode** / 8080 in dev | `/api/v1/check`, actuator |
| 6379 | Redis master + replica | internal | data |
| 26379 | Sentinels ×3 | internal | monitoring + discovery |
| 9090 | Prometheus | 127.0.0.1 | queries, alerts, targets |
| 9093 | Alertmanager | 127.0.0.1 | routing to webhook |
| 3000 | Grafana | 127.0.0.1 | dashboards |
| 3100 | Loki | 127.0.0.1 | log storage + query API |

## Resilience summary

| Failure | Behavior | Metric / alert |
|---|---|---|
| Redis master down | Sentinel failover ~15 s; app keeps serving (fail-open during the gap) | `RedisCircuitBreakerOpen` / `RedisFailingOpen` |
| All of Redis down | Fail-open: traffic allowed, breaker open, alerts fire | `ratelimiter_redis_fallback_total` |
| One sentinel down | 2/3 quorum still elects | none (topology degrades gracefully) |
| One app replica down | Caddy fails over to the survivor | `RateLimiterDown` per-target |
| Sustained abuse | Per-client 429s, `HighThrottleRate` if >50% | `HighThrottleRate` |
