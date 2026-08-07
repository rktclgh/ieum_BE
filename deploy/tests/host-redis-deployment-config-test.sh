#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

compose=deploy/onprem/compose.yml
runtime=deploy/onprem/scripts/validate-runtime-env.sh
host_redis=deploy/scripts/configure-host-redis.sh

grep -Fq 'host.docker.internal:host-gateway' "$compose"
! grep -q '^  redis:' "$compose"
! grep -q 'depends_on:' "$compose"
grep -Fq 'REDIS_HOST' deploy/env/app-main.env.example
grep -Fq 'host.docker.internal' "$runtime"
grep -Fq 'bind 127.0.0.1 ${bridge_gateway}' "$host_redis"
grep -Fq 'protected-mode no' "$host_redis"
grep -Fq 'REDIS_HOST=host.docker.internal' "$host_redis"

echo "Host Redis deployment contract passed."
