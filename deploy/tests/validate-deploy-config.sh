#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

forbid_literal() {
  local pattern="$1"
  shift
  if grep -R --exclude='validate-deploy-config.sh' -Fq -- "$pattern" "$@"; then
    echo "forbidden text found: $pattern" >&2
    exit 1
  fi
}

forbid_regex() {
  local pattern="$1"
  shift
  if grep -R --exclude='validate-deploy-config.sh' -Eq -- "$pattern" "$@"; then
    echo "forbidden pattern found: $pattern" >&2
    exit 1
  fi
}

required_files=(
  .dockerignore
  .github/workflows/deploy-app-main.yml
  .github/workflows/deploy-app-ai.yml
  deploy/app-main/Dockerfile
  deploy/app-main/compose.yml
  deploy/app-ai/Dockerfile
  deploy/app-ai/compose.yml
  deploy/env/app-main.env.example
  deploy/env/app-ai.env.example
  deploy/nginx/ieum.rktclgh.site.http.conf
  deploy/nginx/ieum.rktclgh.site.conf
  deploy/nginx/reload-nginx.sh
  deploy/scripts/bootstrap-docker.sh
  deploy/scripts/configure-nginx.sh
  deploy/scripts/deploy-compose.sh
  deploy/tests/verify-static-frontend-package.sh
  deploy/tests/verify-static-frontend-package-test.sh
  deploy/GITHUB-CONFIG.md
)

for file in "${required_files[@]}"; do
  test -s "$file" || { echo "missing: $file" >&2; exit 1; }
done

bash -n deploy/scripts/bootstrap-docker.sh deploy/scripts/configure-nginx.sh deploy/scripts/deploy-compose.sh deploy/tests/verify-static-frontend-package.sh deploy/tests/verify-static-frontend-package-test.sh
bash deploy/tests/verify-static-frontend-package-test.sh

main_workflow=.github/workflows/deploy-app-main.yml
ai_workflow=.github/workflows/deploy-app-ai.yml

grep -Fq 'types: ["frontend-updated"]' "$main_workflow"
grep -Fq 'rktclgh/ieum_FE' "$main_workflow"
grep -Fq 'ref: main' "$main_workflow"
grep -Fq '[[ "$DISPATCH_SHA" == "$FRONTEND_SHA" ]]' "$main_workflow"
grep -Fq '[[ "$latest_run_id" == "$GITHUB_RUN_ID" ]]' "$main_workflow"
grep -Fq '[[ "$latest_frontend_sha" == "$FRONTEND_SHA" ]]' "$main_workflow"
grep -Fq 'cancel-in-progress: false' "$main_workflow"
forbid_literal 'frontend_ref:' "$main_workflow"
grep -Fq 'test -s out/index.html' "$main_workflow"
grep -Fq 'test -s out/index.txt' "$main_workflow"
grep -Fq 'pnpm verify' "$main_workflow"
grep -Fq 'deploy/tests/verify-static-frontend-package.sh frontend/out' "$main_workflow"
grep -Fq 'deploy/tests/verify-static-frontend-package.sh app-main/src/main/resources/static app-main/build/libs/app-main.jar' "$main_workflow"
grep -Fq 'test ! -e "$static_dir/out"' "$main_workflow"
grep -Fq 'test ! -e "$static_dir/.next"' "$main_workflow"
grep -Fq ':app-main:test :app-main:bootJar' "$main_workflow"
grep -Fq 'docker.io/${DOCKERHUB_USERNAME}/ieum-app-main:' "$main_workflow"
grep -Fq 'deploy/nginx/' "$main_workflow"
grep -Fq 'configure-nginx.sh' "$main_workflow"
grep -Fq 'if [[ -n "$LETSENCRYPT_EMAIL" ]]; then' "$main_workflow"
grep -Fq '[[ "$status" == "200" ]]' "$main_workflow"
forbid_literal 'vars.APP_MAIN_PORT' "$main_workflow"
grep -Fq '54.116.123.11' deploy/GITHUB-CONFIG.md

nginx_configure_line="$(grep -n -m1 -F '"sudo bash '\''$DEPLOY_PATH/configure-nginx.sh' "$main_workflow" | cut -d: -f1)"
compose_deploy_line="$(grep -n -m1 -F '"sudo env APP_MAIN_PRIVATE_BIND_ADDRESS=' "$main_workflow" | cut -d: -f1)"
(( nginx_configure_line < compose_deploy_line )) || {
  echo "Nginx must be configured before the app-main health-gated deployment." >&2
  exit 1
}

grep -Fq ':app-ai:test :app-ai:bootJar' "$ai_workflow"
grep -Fq 'docker.io/${DOCKERHUB_USERNAME}/ieum-app-ai:' "$ai_workflow"
grep -Fq '54.116.69.21' deploy/GITHUB-CONFIG.md
grep -Fq '[[ "$latest_run_id" == "$GITHUB_RUN_ID" ]]' "$ai_workflow"
grep -Fq 'cancel-in-progress: false' "$ai_workflow"
forbid_literal 'repository_dispatch:' "$ai_workflow"
forbid_literal 'ieum_FE' "$ai_workflow"
forbid_literal 'vars.APP_AI_PORT' "$ai_workflow"

grep -Fq 'StrictHostKeyChecking=yes' "$main_workflow"
grep -Fq 'StrictHostKeyChecking=yes' "$ai_workflow"
forbid_literal 'StrictHostKeyChecking=no' .github/workflows deploy
forbid_literal ':latest' .github/workflows deploy
forbid_regex '-----BEGIN .*PRIVATE KEY-----' .github/workflows deploy
forbid_regex 'GHCR_|ghcr\.io' .github/workflows deploy
grep -Fq 'DOCKERHUB_USERNAME' "$main_workflow"
grep -Fq 'DOCKERHUB_TOKEN' "$main_workflow"
grep -Fq 'DOCKERHUB_USERNAME' "$ai_workflow"
grep -Fq 'DOCKERHUB_TOKEN' "$ai_workflow"
grep -Fq 'docker login --username' deploy/scripts/deploy-compose.sh
grep -Fq 'SPRING_DATASOURCE_URL must target the production database' deploy/scripts/deploy-compose.sh
grep -Fq 'jdbc:postgresql://localhost:' deploy/scripts/deploy-compose.sh
grep -Fq 'jdbc:postgresql://127.0.0.1:' deploy/scripts/deploy-compose.sh
grep -Fq '*YOUR_DATABASE_HOST*' deploy/scripts/deploy-compose.sh
grep -Fq 'INQUIRY_ADMIN_EMAIL is required in .env.runtime.' deploy/scripts/deploy-compose.sh
grep -Fq 'REDIS_HOST must be redis in .env.runtime.' deploy/scripts/deploy-compose.sh

grep -Fq 'redis:7' deploy/app-main/compose.yml
grep -Fq 'image: redis:7.2-alpine' deploy/app-main/compose.yml
forbid_literal 'image: redis:7-alpine' deploy/app-main/compose.yml
grep -Fq 'appendonly' deploy/app-main/compose.yml
grep -Fq '127.0.0.1:' deploy/app-main/compose.yml
grep -Fq 'APP_MAIN_PRIVATE_BIND_ADDRESS' deploy/app-main/compose.yml
forbid_literal 'APP_MAIN_PORT' deploy/app-main/compose.yml
forbid_literal '0.0.0.0' deploy/app-main/compose.yml
grep -Fq 'APP_AI_BIND_ADDRESS' deploy/app-ai/compose.yml
grep -Fq '127.0.0.1:' deploy/app-ai/compose.yml
forbid_literal 'APP_AI_PORT' deploy/app-ai/compose.yml
grep -Fq '.env.runtime' deploy/app-main/compose.yml
grep -Fq '.env.runtime' deploy/app-ai/compose.yml

grep -Fq 'REDIS_HOST=redis' deploy/env/app-main.env.example
grep -Fq 'SPRING_DATASOURCE_URL=jdbc:postgresql://YOUR_DATABASE_HOST:5432/ieum' deploy/env/app-main.env.example
grep -Fq 'INQUIRY_ADMIN_EMAIL=' deploy/env/app-main.env.example
grep -Fq 'SPRING_DATASOURCE_URL=jdbc:postgresql://YOUR_DATABASE_HOST:5432/ieum' deploy/env/app-ai.env.example
forbid_regex 'rds\.amazonaws\.com' deploy/env
grep -Fq 'APP_MAIN_PRIVATE_BIND_ADDRESS=172.31.38.97' deploy/env/app-main.env.example
grep -Fq 'SERVER_FORWARD_HEADERS_STRATEGY=native' deploy/env/app-main.env.example
grep -Fq 'APP_AI_QUESTION_ANSWER_DISPATCH_BASE_URL=http://172.31.33.42:8081' deploy/env/app-main.env.example
grep -Fq 'APP_AI_QUESTION_CALLBACK_BASE_ORIGIN=http://172.31.38.97:8080' deploy/env/app-ai.env.example
grep -Fq 'APP_AI_BEDROCK_REGION=ap-southeast-2' deploy/env/app-ai.env.example
grep -Fq 'spring-boot-starter-actuator' app-main/build.gradle.kts

grep -Fq 'server_name ieum.rktclgh.site' deploy/nginx/ieum.rktclgh.site.conf
grep -Fq 'proxy_pass http://127.0.0.1:8080' deploy/nginx/ieum.rktclgh.site.conf
grep -Fq 'proxy_buffering off' deploy/nginx/ieum.rktclgh.site.conf
grep -Fq 'proxy_set_header Upgrade' deploy/nginx/ieum.rktclgh.site.conf
grep -Fq 'location ^~ /api/v1/internal/' deploy/nginx/ieum.rktclgh.site.conf
grep -Fq 'location ^~ /actuator/' deploy/nginx/ieum.rktclgh.site.conf
grep -Fq 'location = /actuator' deploy/nginx/ieum.rktclgh.site.conf
grep -Fq 'location = /swagger-ui.html' deploy/nginx/ieum.rktclgh.site.conf
grep -Fq 'location = /v3/api-docs' deploy/nginx/ieum.rktclgh.site.conf
grep -Fq 'location = /v3/api-docs.yaml' deploy/nginx/ieum.rktclgh.site.conf
grep -Fq 'ssl_certificate /etc/letsencrypt/live/ieum.rktclgh.site/fullchain.pem' deploy/nginx/ieum.rktclgh.site.conf
grep -Fq 'certbot certonly' deploy/scripts/configure-nginx.sh
grep -Fq 'nginx -t' deploy/scripts/configure-nginx.sh
grep -Fq 'renewal-hooks/deploy/reload-nginx' deploy/scripts/configure-nginx.sh
grep -Fq 'if ! IFS= read -r dockerhub_token || [[ -z "$dockerhub_token" ]]; then' deploy/scripts/deploy-compose.sh
grep -Fq 'PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' deploy/nginx/reload-nginx.sh
grep -Fq 'nginx -t' deploy/nginx/reload-nginx.sh
grep -Fq 'systemctl reload nginx' deploy/nginx/reload-nginx.sh
forbid_literal '/usr/sbin/nginx' deploy/nginx/reload-nginx.sh
forbid_literal '/bin/systemctl' deploy/nginx/reload-nginx.sh

grep -Fq 'archiveFileName.set("app-main.jar")' app-main/build.gradle.kts
grep -Fq 'archiveFileName.set("app-ai.jar")' app-ai/build.gradle.kts
grep -Fq 'COPY app-main/build/libs/app-main.jar /app/app.jar' deploy/app-main/Dockerfile
grep -Fq 'COPY app-ai/build/libs/app-ai.jar /app/app.jar' deploy/app-ai/Dockerfile
forbid_literal 'build/libs/*.jar' deploy/app-main/Dockerfile deploy/app-ai/Dockerfile

grep -Fq 'ieum.notification.sse.heartbeat-ms=15000' app-main/src/main/resources/application.properties
grep -Fq '# Spring sends an SSE heartbeat every 15s; 75s allows five missed heartbeats.' deploy/nginx/ieum.rktclgh.site.conf

echo "MVP deployment config validation passed."
