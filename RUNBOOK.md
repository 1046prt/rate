# Operations Runbook

Operational procedures for the Rate Limiter stack. Alerts are fired by Prometheus
(`monitoring/alerts.yml`) and routed by Alertmanager to the `RECEIVER_URL` webhook.

## On-call quickstart

1. Read the alert: which service, which instance, since when (`monitoring/alerts.yml`).
2. Check the dashboards (Grafana, `http://localhost:3000`): "Rate Limiter" for
   traffic/latency/fail-open/breaker; "SLO Error Budget" for error ratio, burn
   rate, backup freshness.
3. Check logs: `{container="rate-app-1"}` in Loki Explore (`http://localhost:3100`).
4. Apply the relevant procedure below. Never "fix" by restarting blindly — read first.

## Alert procedures

### RateLimiterDown (critical)
`up{job="rate-limiter"} == 0` — an app replica is not scrapable.

- `docker ps` → is the container running? `docker logs rate-app-1 --tail 200`.
- If OOMKilled: `docker inspect rate-app-1 | grep -i oom`; the app needs more
  than the default JVM heap.
- If crashed on start: `docker compose logs app | grep -i error`; the app
  **refuses to start** if `RATELIMITER_API_KEY` is missing (by design, fail-closed).
- Restart: `docker compose -f docker-compose.yml -f docker-compose.redis-ha.yml
  -f docker-compose.monitoring.yml up -d --scale app=2` (pass the FULL file set,
  a partial set reconciles other containers down).

### RedisCircuitBreakerOpen (critical)
`resilience4j_circuitbreaker_state{name="redis"} == 2` for 1m — Redis is
unreachable and the limiter is failing open.

- The API is UP by design (fail-open); limit enforcement is suspended.
- `docker ps` → redis containers: `docker logs rate-limiter-redis`, `docker logs rate-redis-replica-1`.
- Sentinel view: `docker exec rate-sentinel-1 redis-cli -p 26379 -a <REDIS_PASSWORD> sentinel master mymaster`.
- Wait for the breaker half-open probe (30s default) — it closes automatically
  once Redis answers. If it re-opens, Redis is still broken: investigate
  persistence/disk (`docker exec rate-limiter-redis redis-cli -a <pass> info persistence`).
- If a failover is needed: `docker restart rate-limiter-redis` (Sentinel promotes
  the replica; do NOT manually failover unless sentinel is down).

### RedisFailingOpen (warning)
`ratelimiter_redis_fallback_total` increasing — intermittent Redis errors
(timeouts, NOAUTH, refused).

- Look for Lettuce reconnection messages in `rate-app-1` logs.
- Check Redis latency/auth: `docker exec rate-limiter-redis redis-cli -a <pass> --latency`.
- Common causes: wrong `REDIS_PASSWORD`, replica lag, host disk saturation.

### HighThrottleRate (warning)
>50% of requests rejected for 5m.

- A client is at (or over) its limit — legitimate or an attack?
- `ratelimiter_requests_rejected_total{clientId="..."}` per client in Prometheus.
- If an abuser: block upstream (WAF/edge), the limiter is doing its job.
- If limits are wrong: `RATELIMITER_DEFAULT_LIMIT` / `RATELIMITER_DEFAULT_WINDOW_SEC`
  or the per-algorithm overrides in `application.yml`.

### HighApiKeyFailures (warning)
Sustained 401s — credential stuffing or a broken consumer.

- Count by client: `sum by (clientId) (rate(ratelimiter_auth_invalid_api_key_total[5m]))`.
- Rotate the key if leaked; contact the consumer if their key stopped validating.

### HighLatencyP99 (warning)
p99 of `http_server_requests_seconds` > 1s for 5m.

- Check Redis latency (`redis-cli --latency`); Lettuce 500ms timeout fail-opens.
- Check replica failover state; check app GC: `actuator/metrics/jvm.gc.pause`?
- Check load: `k6 run --env API_KEY=... --vus 100 --duration 2m loadtest/check.js`.

### RedisBackupStale (critical)
No successful Redis backup in 36h (or the freshness metric is absent).

- `docker ps` → is the `redis-backup` container healthy? If unhealthy, the
  `.prom` freshness file is missing or stale: `docker logs rate-redis-backup-1`.
- Check the archive: `ls -l backups/` (host dir) — expect `redis-YYYYMMDD-HHMMSS.tgz`.
- Manual one-shot backup (diagnosis): `docker exec rate-redis-backup-1 redis-cli -a <pass> -h redis BGSAVE` then `tar` the archive by hand (see Backup & restore).
- If node-exporter/prometheus broke, the alert also fires — verify the scrape target `node-exporter:9100` is up.

### SloErrorBudgetBurnFast / BurnSlow / Exhausted
Error-budget alerts (99.9% target, 0.1% budget, 30-day window). Fast burn = >14.4x
for 15m; slow burn = >6x for 1h; exhausted = 30-day error ratio > 0.1%.

- Traffic is erroring, not throttling: check `http_server_requests_seconds_count{status=~"5.."}`
  per app instance; Loki for 5xx stack traces.
- A missing metric (no traffic yet) means no alert — these fire on real error rates only.
- Exhausted = budget consumed for the month: postmortem required, fix the 5xx
  source (typically a regression in the app or a Redis fail-open storm).

## Redis failover drill (HA topology)

1. Warm up a client key, confirm it is limited: 100 calls allowed, then 429.
2. `docker stop rate-limiter-redis`.
3. Watch: `docker exec rate-sentinel-1 redis-cli -p 26379 sentinel get-master-addr-by-name mymaster`
   → the replica becomes master within ~15s.
4. Verify: app still serves 200 for new clients, the pre-failover client still 429
   (state survives with a warm replica + AOF).
5. `docker start rate-limiter-redis` → it rejoins as a replica (`role: slave`).
6. Cleanup: state is the same; no manual steps needed.

## Backup & restore

State = Redis data (rate-limit windows). AOF (`--appendonly yes --appendfsync everysec`)
and RDB are on the `redis-data` / `redis-replica-data` volumes.

**Automatic (default):** the `redis-backup` sidecar (compose service in
`docker-compose.redis-ha.yml`) runs BGSAVE + tar every 24h into the host
`./backups` dir, keeps `BACKUP_KEEP` archives (default 7), and writes
`redis-backup.prom` (freshness metric scraped by node-exporter → `RedisBackupStale`
alert). Logs: `docker logs rate-redis-backup-1`.

**Manual one-shot:**

```bash
docker exec rate-limiter-redis redis-cli -a "$REDIS_PASSWORD" BGSAVE
docker run --rm -v rate_redis-data:/data -v "$(pwd)/backups:/backup" alpine \
  tar czf /backup/redis-$(date +%F).tar.gz -C /data .
```

Restore:

```bash
docker compose -f docker-compose.yml -f docker-compose.redis-ha.yml down   # stops everything
docker run --rm -v rate_redis-data:/data -v "$(pwd)/backups:/backup" alpine \
  sh -c "rm -rf /data/* && tar xzf /backup/redis-<date>.tar.gz -C /data"
docker compose -f docker-compose.yml -f docker-compose.redis-ha.yml -f docker-compose.monitoring.yml up -d
```

Note: the volume name includes the compose project (`rate_redis-data`); adapt if the
project name differs. Backup/restore the Grafana, Prometheus and Loki volumes
(`grafana-data`, `prometheus-data`, `loki-data`) the same way for full observability state.

## Scaling

App replicas share Redis state, so the limiter is globally consistent:

```bash
docker compose -f docker-compose.yml -f docker-compose.redis-ha.yml \
  -f docker-compose.monitoring.yml -f docker-compose.tls.yml \
  up -d --scale app=2
```

- Caddy round-robins between replicas (see `caddy/Caddyfile`); remove the duplicate
  `to app:8080` line when running a single instance.
- Prometheus discovers each replica via DNS (`dns_sd_configs`, `monitoring/prometheus.yml`).
- Replicas appear in Loki as separate `container` values (`rate-app-1`, `rate-app-2`).
- Scale down: `up -d --scale app=1`. For per-request load balancing at larger scale,
  move behind a K8s Service / nginx with explicit upstream IPs.

## Upgrade procedure

1. Backup Redis and observability volumes (see above).
2. `mvn clean verify` locally; `docker compose build`.
3. `docker compose -f <full file set> up -d` (Compose recreates changed containers).
4. Watch the deployment: Prometheus targets, `RateLimiterDown` alert, Loki logs.
5. Rollback: `docker compose ... up -d` with the previous image tag.

## Incident template

| Field | Value |
|---|---|
| Alert(s) | e.g. RedisCircuitBreakerOpen |
| Started / ended (UTC) |  |
| Impact | e.g. limits unenforced 3m, no 5xx |
| Root cause |  |
| Actions taken |  |
| Prevention | e.g. add Redis disk-space alert |
