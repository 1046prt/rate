#!/bin/sh
# Sentinel bootstrap: writes /tmp/sentinel.conf from container env, then execs redis-sentinel.
# Mounted read-only into every sentinel replica (see docker-compose.redis-ha.yml and
# docker-compose.sentinel-ha.yml). SENTINEL_QUORUM / REDIS_PASSWORD come from the container.
set -e

{
  echo "port 26379"
  echo "sentinel monitor mymaster 172.28.0.10 6379 ${SENTINEL_QUORUM:-1}"
  echo "sentinel down-after-milliseconds mymaster 5000"
  echo "sentinel failover-timeout mymaster 10000"
  echo "sentinel parallel-syncs mymaster 1"
} > /tmp/sentinel.conf

if [ -n "${REDIS_PASSWORD}" ]; then
  echo "sentinel auth-pass mymaster ${REDIS_PASSWORD}" >> /tmp/sentinel.conf
fi

exec redis-sentinel /tmp/sentinel.conf
