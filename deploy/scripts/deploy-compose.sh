#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 5 ]]; then
  echo "usage: deploy-compose.sh <service> <image> <deploy-dir> <health-url> <dockerhub-user>" >&2
  exit 2
fi

service="$1"
image="$2"
deploy_dir="$3"
health_url="$4"
dockerhub_username="$5"

if [[ "$EUID" -ne 0 ]]; then
  echo "deploy-compose.sh must run with sudo." >&2
  exit 1
fi

case "$service" in
  app-main) export APP_MAIN_IMAGE="$image" ;;
  app-ai) export APP_AI_IMAGE="$image" ;;
  *) echo "unsupported service: $service" >&2; exit 2 ;;
esac

[[ "$image" == "docker.io/${dockerhub_username}/"*:* ]] || { echo "invalid image: $image" >&2; exit 2; }
[[ -f "$deploy_dir/compose.yml" ]] || { echo "compose.yml is missing" >&2; exit 1; }
[[ -f "$deploy_dir/.env.runtime" ]] || { echo ".env.runtime is missing" >&2; exit 1; }

datasource_url="$(sed -n 's/^SPRING_DATASOURCE_URL=//p' "$deploy_dir/.env.runtime" | tail -n 1)"
case "$datasource_url" in
  jdbc:postgresql://localhost:* | jdbc:postgresql://127.0.0.1:* | *YOUR_DATABASE_HOST*)
    echo "SPRING_DATASOURCE_URL must target the production database, not localhost or a placeholder." >&2
    exit 1
    ;;
  jdbc:postgresql://*) ;;
  *)
    echo "SPRING_DATASOURCE_URL is missing or invalid in .env.runtime." >&2
    exit 1
    ;;
esac

inquiry_admin_email="$(sed -n 's/^INQUIRY_ADMIN_EMAIL=//p' "$deploy_dir/.env.runtime" | tail -n 1)"
[[ -n "$inquiry_admin_email" ]] || {
  echo "INQUIRY_ADMIN_EMAIL is required in .env.runtime." >&2
  exit 1
}

redis_host="$(sed -n 's/^REDIS_HOST=//p' "$deploy_dir/.env.runtime" | tail -n 1)"
[[ "$redis_host" == "redis" ]] || {
  echo "REDIS_HOST must be redis in .env.runtime." >&2
  exit 1
}

if ! IFS= read -r dockerhub_token || [[ -z "$dockerhub_token" ]]; then
  echo "Docker Hub token is empty" >&2
  exit 1
fi

docker_config="$(mktemp -d)"
trap 'rm -rf "$docker_config"' EXIT
export DOCKER_CONFIG="$docker_config"
printf '%s' "$dockerhub_token" | docker login --username "$dockerhub_username" --password-stdin
unset dockerhub_token

cd "$deploy_dir"
docker compose -f compose.yml config -q
docker compose -f compose.yml pull "$service"
docker compose -f compose.yml up -d --remove-orphans

for _ in $(seq 1 40); do
  if curl -fsS "$health_url" | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
    echo "$service deployment is healthy."
    exit 0
  fi
  sleep 3
done

docker compose -f compose.yml logs --tail=100 "$service" >&2
echo "$service health check failed." >&2
exit 1
