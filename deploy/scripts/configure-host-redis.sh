#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 1 ]]; then
  echo "usage: configure-host-redis.sh <app-main-runtime-env>" >&2
  exit 2
fi

if [[ "$EUID" -ne 0 ]]; then
  echo "configure-host-redis.sh must run with sudo." >&2
  exit 1
fi

config=/etc/redis/redis.conf
backup="${config}.before-ieum-host-redis"
runtime_env="$1"

command -v redis-cli >/dev/null || { echo "redis-cli is not installed." >&2; exit 1; }
systemctl is-active --quiet redis-server || { echo "redis-server is not active." >&2; exit 1; }
test -f "$config" || { echo "Redis config is missing: $config" >&2; exit 1; }
test -f "$runtime_env" || { echo "app-main runtime env is missing: $runtime_env" >&2; exit 1; }

bridge_gateway="$(docker network inspect bridge --format '{{(index .IPAM.Config 0).Gateway}}')"
[[ "$bridge_gateway" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || {
  echo "Invalid Docker bridge gateway: $bridge_gateway" >&2
  exit 1
}

[[ "$(redis-cli -h 127.0.0.1 -p 6379 ping)" == "PONG" ]] || {
  echo "Host Redis did not answer PING on loopback." >&2
  exit 1
}

# Redis stays private: only loopback and Docker's bridge gateway can reach it.
# Protected mode must be disabled because the app container is not a loopback client.
if [[ ! -f "$backup" ]]; then
  cp -p "$config" "$backup"
fi

if grep -Eq '^[[:space:]]*bind[[:space:]]+' "$config"; then
  sed -i -E "s|^[[:space:]]*bind[[:space:]]+.*$|bind 127.0.0.1 ${bridge_gateway}|" "$config"
else
  printf '\nbind 127.0.0.1 %s\n' "$bridge_gateway" >> "$config"
fi

if grep -Eq '^[[:space:]]*protected-mode[[:space:]]+' "$config"; then
  sed -i -E 's|^[[:space:]]*protected-mode[[:space:]]+.*$|protected-mode no|' "$config"
else
  printf 'protected-mode no\n' >> "$config"
fi

systemctl restart redis-server
systemctl is-active --quiet redis-server || { echo "redis-server failed to restart." >&2; exit 1; }
[[ "$(redis-cli -h 127.0.0.1 -p 6379 ping)" == "PONG" ]] || { echo "Redis loopback PING failed." >&2; exit 1; }
[[ "$(redis-cli -h "$bridge_gateway" -p 6379 ping)" == "PONG" ]] || { echo "Redis Docker bridge PING failed." >&2; exit 1; }

if grep -Eq '^REDIS_HOST=' "$runtime_env"; then
  sed -i -E 's|^REDIS_HOST=.*$|REDIS_HOST=host.docker.internal|' "$runtime_env"
else
  printf '\nREDIS_HOST=host.docker.internal\n' >> "$runtime_env"
fi
if grep -Eq '^REDIS_PORT=' "$runtime_env"; then
  sed -i -E 's|^REDIS_PORT=.*$|REDIS_PORT=6379|' "$runtime_env"
else
  printf 'REDIS_PORT=6379\n' >> "$runtime_env"
fi

echo "Host Redis is reachable on loopback and the Docker bridge only."
