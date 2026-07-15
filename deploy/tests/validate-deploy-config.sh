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
  deploy/env/admin-dashboard-migration.env.example
  deploy/env/app-main.env.example
  deploy/env/app-ai.env.example
  deploy/nginx/ieum.rktclgh.site.http.conf
  deploy/nginx/ieum.rktclgh.site.conf
  deploy/nginx/reload-nginx.sh
  deploy/scripts/bootstrap-docker.sh
  deploy/scripts/apply-admin-dashboard-migrations.sh
  deploy/scripts/configure-nginx.sh
  deploy/scripts/deploy-compose.sh
  deploy/tests/apply-admin-dashboard-migrations-postgres-test.sh
  deploy/tests/apply-admin-dashboard-migrations-test.sh
  deploy/tests/verify-static-frontend-package.sh
  deploy/tests/verify-static-frontend-package-test.sh
  deploy/GITHUB-CONFIG.md
)

for file in "${required_files[@]}"; do
  test -s "$file" || { echo "missing: $file" >&2; exit 1; }
done

bash -n \
  deploy/scripts/apply-admin-dashboard-migrations.sh \
  deploy/scripts/bootstrap-docker.sh \
  deploy/scripts/configure-nginx.sh \
  deploy/scripts/deploy-compose.sh \
  deploy/tests/apply-admin-dashboard-migrations-postgres-test.sh \
  deploy/tests/apply-admin-dashboard-migrations-test.sh \
  deploy/tests/verify-static-frontend-package.sh \
  deploy/tests/verify-static-frontend-package-test.sh
bash deploy/tests/apply-admin-dashboard-migrations-test.sh
bash deploy/tests/apply-admin-dashboard-migrations-postgres-test.sh
bash deploy/tests/verify-static-frontend-package-test.sh

main_workflow=.github/workflows/deploy-app-main.yml
ai_workflow=.github/workflows/deploy-app-ai.yml

frontend_build_line="$(grep -n -m1 -F '  frontend-build:' "$main_workflow" | cut -d: -f1 || true)"
deploy_job_line="$(grep -n -m1 -F '  deploy:' "$main_workflow" | cut -d: -f1 || true)"
test -n "$frontend_build_line" && test -n "$deploy_job_line" \
  && (( frontend_build_line < deploy_job_line )) || {
  echo "app-main must build the frontend in a separate job before deploy." >&2
  exit 1
}

frontend_build_job="$(sed -n "${frontend_build_line},$((deploy_job_line - 1))p" "$main_workflow")"
deploy_job="$(sed -n "${deploy_job_line},\$p" "$main_workflow")"

grep -Fq 'uses: actions/upload-artifact@v4' <<<"$frontend_build_job"
grep -Fq 'include-hidden-files: true' <<<"$frontend_build_job"
grep -Fq 'if-no-files-found: error' <<<"$frontend_build_job"
grep -Fq 'frontend-sha' <<<"$frontend_build_job"
if grep -Eq '^[[:space:]]+environment:|secrets\.' <<<"$frontend_build_job"; then
  echo "frontend-build must not receive a production environment or secrets." >&2
  exit 1
fi

grep -Fq 'needs: frontend-build' <<<"$deploy_job"
grep -Fq 'environment: app-main-production' <<<"$deploy_job"
grep -Fq 'uses: actions/download-artifact@v4' <<<"$deploy_job"
grep -Fq 'artifact-ids: ${{ needs.frontend-build.outputs.artifact_id }}' <<<"$deploy_job"
grep -Fq 'merge-multiple: true' <<<"$deploy_job"
grep -Fq 'unsafe_entry="$(find "$artifact_root" -mindepth 1' <<<"$deploy_job"
grep -Fq -- "-name '.*'" <<<"$deploy_job"
grep -Fq '! -type f -a ! -type d' <<<"$deploy_job"
grep -Fq '[[ "$artifact_frontend_sha" == "$EXPECTED_FRONTEND_SHA" ]]' <<<"$deploy_job"
grep -Fq 'deploy/tests/verify-static-frontend-package.sh "$artifact_root/export"' <<<"$deploy_job"
grep -Fq 'cp -a "$artifact_root/export/." "$static_dir/"' <<<"$deploy_job"

if grep -Eq 'repository:[[:space:]]+rktclgh/ieum_FE|pnpm|working-directory:[[:space:]]+frontend|git -C frontend|frontend/out' <<<"$deploy_job"; then
  echo "deploy must consume only the downloaded frontend artifact, not frontend source or tooling." >&2
  exit 1
fi
test "$(grep -Fc 'environment: app-main-production' "$main_workflow")" -eq 1 || {
  echo "Only the deploy job may receive the app-main production environment." >&2
  exit 1
}

grep -Fq 'types: ["frontend-updated"]' "$main_workflow"
grep -Fq 'rktclgh/ieum_FE' "$main_workflow"
grep -Fq 'DISPATCH_SHA: ${{ github.event.client_payload.frontend_sha }}' "$main_workflow"
grep -Fq '[[ "$DISPATCH_SHA" =~ ^[0-9a-f]{40}$ ]]' "$main_workflow"
grep -Fq 'ref: ${{ steps.frontend-source.outputs.ref }}' "$main_workflow"
grep -Fq 'echo "ref=main" >> "$GITHUB_OUTPUT"' "$main_workflow"
grep -Fq '[[ "$DISPATCH_SHA" == "$FRONTEND_SHA" ]]' "$main_workflow"
grep -Fq 'ref: main' "$main_workflow"
grep -Fq '[[ "$latest_run_id" == "$GITHUB_RUN_ID" ]]' "$main_workflow"
grep -Fq 'cancel-in-progress: false' "$main_workflow"
forbid_literal 'frontend_ref:' "$main_workflow"
forbid_literal 'ref: ${{ github.event.client_payload.frontend_sha }}' "$main_workflow"
grep -Fq '"db/migrations/**"' "$main_workflow"
grep -Fq '"deploy/scripts/apply-admin-dashboard-migrations.sh"' "$main_workflow"
grep -Fq 'test -s out/index.html' "$main_workflow"
grep -Fq 'test -s out/index.txt' "$main_workflow"
grep -Fq 'pnpm verify' "$main_workflow"
grep -Fq 'deploy/tests/verify-static-frontend-package.sh "$artifact_root/export"' "$main_workflow"
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
grep -Fq '`client_payload.frontend_sha`' deploy/GITHUB-CONFIG.md
grep -Fq '`cancel-in-progress: false`' deploy/GITHUB-CONFIG.md
grep -Fq 'no production Environment' deploy/GITHUB-CONFIG.md
grep -Fq 'immutable artifact ID' deploy/GITHUB-CONFIG.md
grep -Fq 'reject hidden entries, symlinks, and non-file/non-directory entries' deploy/GITHUB-CONFIG.md
grep -Fq 'Frontend source, package scripts, Node, and pnpm never run inside the' deploy/GITHUB-CONFIG.md
grep -Fq '`PGHOST`' deploy/GITHUB-CONFIG.md
grep -Fq '`PGPORT`' deploy/GITHUB-CONFIG.md
grep -Fq '`PGDATABASE`' deploy/GITHUB-CONFIG.md
grep -Fq '`PGUSER`' deploy/GITHUB-CONFIG.md
grep -Fq '`PGPASSWORD`' deploy/GITHUB-CONFIG.md
grep -Fq 'before either application binary is built' deploy/GITHUB-CONFIG.md
grep -Fq 'private RDS' deploy/GITHUB-CONFIG.md
grep -Fq '`.migration.env`' deploy/GITHUB-CONFIG.md

migration_env_example=deploy/env/admin-dashboard-migration.env.example
grep -Eq '^PGHOST=' "$migration_env_example"
grep -Eq '^PGPORT=' "$migration_env_example"
grep -Eq '^PGDATABASE=' "$migration_env_example"
grep -Eq '^PGUSER=' "$migration_env_example"
grep -Eq '^PGPASSWORD=' "$migration_env_example"
grep -Eq '^# PGPASSFILE=' "$migration_env_example"

for workflow in "$main_workflow" "$ai_workflow"; do
  test "$(grep -Fc 'cancel-in-progress: false' "$workflow")" -eq 1 || {
    echo "Deployment cancellation policy must be declared exactly once: $workflow" >&2
    exit 1
  }
  forbid_literal 'cancel-in-progress: true' "$workflow"
  for pg_secret in PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD PGPASSFILE; do
    forbid_literal "secrets.$pg_secret" "$workflow"
  done
  forbid_regex '^[[:space:]]+PG(HOST|PORT|DATABASE|USER|PASSWORD|PASSFILE):' "$workflow"
  forbid_regex '\$\{\{[^}]*PG(HOST|PORT|DATABASE|USER|PASSWORD|PASSFILE)' "$workflow"
  forbid_regex 'ssh .*PG(PASSWORD|PASSFILE)' "$workflow"
  forbid_regex 'scp .*migration\.env' "$workflow"
  forbid_regex '^[[:space:]]+deploy/scripts/apply-admin-dashboard-migrations\.sh[[:space:]]*$' "$workflow"

  grep -Fq 'migration_env="$deploy_path/.migration.env"' "$workflow"
  grep -Fq 'test -f "$migration_env"' "$workflow"
  grep -Fq 'test ! -L "$migration_env"' "$workflow"
  grep -Fq '[[ "$(stat -c '\''%a'\'' "$migration_env")" == "600" ]]' "$workflow"
  grep -Fq 'command -v psql >/dev/null 2>&1' "$workflow"
  grep -Fq 'set -a' "$workflow"
  grep -Fq 'source "$migration_env"' "$workflow"
  grep -Fq 'set +a' "$workflow"
  grep -Fq 'if [[ -z "${PGPASSWORD:-}" ]]; then' "$workflow"
  grep -Fq 'test -n "${PGPASSFILE:-}"' "$workflow"
  grep -Fq '[[ "$PGPASSFILE" == /* ]]' "$workflow"
  grep -Fq 'test -f "$PGPASSFILE"' "$workflow"
  grep -Fq 'test ! -L "$PGPASSFILE"' "$workflow"
  grep -Fq '[[ "$(stat -c '\''%a'\'' "$PGPASSFILE")" == "600" ]]' "$workflow"

  test "$(grep -Ec '^[[:space:]]+scp .*db/migrations/' "$workflow")" -eq 1 || {
    echo "Only one exact migration copy command is allowed: $workflow" >&2
    exit 1
  }
  grep -Fq 'scp "${scp_opts[@]}" deploy/scripts/apply-admin-dashboard-migrations.sh "$remote:$DEPLOY_PATH/deploy/scripts/apply-admin-dashboard-migrations.sh"' "$workflow"
  grep -Fq 'scp "${scp_opts[@]}" db/migrations/v25_user_auth_version.sql db/migrations/v26_admin_audit_logs.sql "$remote:$DEPLOY_PATH/db/migrations/"' "$workflow"
  forbid_regex 'scp .*db/migrations/.*[?*]' "$workflow"
  test "$(grep -Fc '"$deploy_path/deploy/scripts/apply-admin-dashboard-migrations.sh"' "$workflow")" -eq 1 || {
    echo "Remote migration helper must be executed exactly once: $workflow" >&2
    exit 1
  }

  helper_copy_line="$(grep -n -m1 -F 'scp "${scp_opts[@]}" deploy/scripts/apply-admin-dashboard-migrations.sh' "$workflow" | cut -d: -f1)"
  migration_copy_line="$(grep -n -m1 -F 'scp "${scp_opts[@]}" db/migrations/v25_user_auth_version.sql db/migrations/v26_admin_audit_logs.sql' "$workflow" | cut -d: -f1)"
  migration_line="$(grep -n -m1 -F '"$deploy_path/deploy/scripts/apply-admin-dashboard-migrations.sh"' "$workflow" | cut -d: -f1)"
  binary_build_line="$(grep -n -m1 -E 'run: ./gradlew :app-(main|ai):test :app-(main|ai):bootJar' "$workflow" | cut -d: -f1)"
  image_build_line="$(grep -n -m1 -F 'docker build --file' "$workflow" | cut -d: -f1)"
  deploy_line="$(grep -n -m1 -F -- '- name: Deploy over SSH' "$workflow" | cut -d: -f1)"
  test -n "$helper_copy_line" && test -n "$migration_copy_line" && test -n "$migration_line" \
    && test -n "$binary_build_line" && test -n "$image_build_line" && test -n "$deploy_line" || {
    echo "migration/build ordering marker missing: $workflow" >&2
    exit 1
  }
  (( helper_copy_line < migration_line \
    && migration_copy_line < migration_line \
    && migration_line < binary_build_line \
    && migration_line < image_build_line \
    && migration_line < deploy_line )) || {
    echo "Remote admin migrations must be copied and run before binary/image/deployment: $workflow" >&2
    exit 1
  }
done

latest_frontend_line="$(grep -n -m1 -F '[[ "$latest_frontend_sha" == "$FRONTEND_SHA" ]]' "$main_workflow" | cut -d: -f1)"
ssh_deploy_line="$(grep -n -m1 -F -- '- name: Deploy over SSH' "$main_workflow" | cut -d: -f1)"
test -n "$latest_frontend_line" && test -n "$ssh_deploy_line" && (( latest_frontend_line < ssh_deploy_line )) || {
  echo "The latest frontend main SHA must be checked immediately before deployment." >&2
  exit 1
}
if sed -n "$((latest_frontend_line + 1)),$((ssh_deploy_line - 1))p" "$main_workflow" \
  | grep -Eq '^      - name:'; then
  echo "No deployment step may bypass the final frontend SHA check." >&2
  exit 1
fi

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
grep -Fq '"db/migrations/**"' "$ai_workflow"
grep -Fq '"deploy/scripts/apply-admin-dashboard-migrations.sh"' "$ai_workflow"
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
