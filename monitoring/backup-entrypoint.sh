#!/bin/sh
# Scheduled Redis backup sidecar (compose service `redis-backup`).
#
# Every 24h: triggers BGSAVE on the master, waits for completion, tars the
# Redis data volume into /backup with rotation (BACKUP_KEEP archives), and
# publishes a textfile metric for Prometheus (scraped via node-exporter's
# textfile collector) so a stale/failed backup pages as RedisBackupStale.
#
# ENV:
#   REDIS_PASSWORD  optional AUTH password for redis-cli
#   BACKUP_KEEP     number of daily archives to keep (default 7)
#   BACKUP_INTERVAL seconds between runs (default 86400; 3600 in dev if set)

set -eu

# Files must be group/world-readable: node-exporter reads the .prom via a
# separate ro bind mount and Docker Desktop denies cross-container reads of
# 600 files (chmod below is belt-and-braces).
umask 022

AUTH_ARGS=""
if [ -n "${REDIS_PASSWORD:-}" ]; then
  AUTH_ARGS="-a ${REDIS_PASSWORD}"
fi

KEEP="${BACKUP_KEEP:-7}"
INTERVAL="${BACKUP_INTERVAL:-86400}"

# Wait for the master to accept commands before the first backup.
until redis-cli $AUTH_ARGS -h redis ping 2>/dev/null | grep -q PONG; do
  echo "redis-backup: waiting for redis..."
  sleep 2
done

while true; do
  TS="$(date +%Y%m%d-%H%M%S)"
  OK=0
  if redis-cli $AUTH_ARGS -h redis BGSAVE >/dev/null 2>&1; then
    # BGSAVE is async: wait for the in-progress flag to clear, then check
    # status. INFO output is CRLF-terminated, so strip \r before comparing.
    while [ "$(redis-cli $AUTH_ARGS -h redis info persistence 2>/dev/null \
        | tr -d '\r' | sed -n 's/^rdb_bgsave_in_progress://p')" != "0" ]; do
      sleep 1
    done
    STATUS="$(redis-cli $AUTH_ARGS -h redis info persistence 2>/dev/null \
        | tr -d '\r' | sed -n 's/^rdb_last_bgsave_status://p')"
    if [ "$STATUS" = "ok" ] && tar czf "/backup/redis-${TS}.tgz" -C /data .; then
      printf 'redis_backup_success_timestamp_seconds %s\n' "$(date +%s)" \
        > /backup/redis-backup.prom
      chmod 644 /backup/redis-backup.prom
      echo "redis-backup: ok ${TS}"
      # Rotate: keep the newest $KEEP archives.
      ls -1t /backup/redis-*.tgz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f
      OK=1
    else
      echo "redis-backup: FAILED ${TS} (bgsave status=${STATUS:-unknown} or tar failed)" >&2
    fi
  else
    echo "redis-backup: FAILED ${TS} (BGSAVE command rejected)" >&2
  fi
  if [ "$OK" -eq 0 ]; then
    # Delete the stale metric so node-exporter stops exporting it and the
    # RedisBackupStale alert fires.
    rm -f /backup/redis-backup.prom
  fi
  sleep "$INTERVAL"
done
