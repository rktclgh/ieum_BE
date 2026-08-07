#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
helper="$root/deploy/onprem/scripts/deploy-release.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
export HOME="$tmp/home"
mkdir -p "$HOME"

fail() { echo "deploy release test failed: $*" >&2; exit 1; }

bin="$tmp/bin"
mkdir "$bin"
call_log="$tmp/calls.log"
: > "$call_log"

cat > "$bin/sha256sum" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == --check ]]; then
  while read -r expected file; do
    actual="$(shasum -a 256 "$file" | awk '{print $1}')"
    [[ "$actual" == "$expected" ]] || exit 1
  done < "${2:-/dev/stdin}"
  exit 0
fi
if [[ "$#" == 0 ]]; then
  shasum -a 256 | awk '{print $1 "  -"}'
  exit 0
fi
for file in "$@"; do
  shasum -a 256 "$file" | awk '{print $1 "  " $2}'
done
EOF
cat > "$bin/ssh-keygen" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'ssh-keygen %s\n' "$*" >> "$FAKE_CALL_LOG"
[[ "${FAKE_SIGNATURE_FAIL:-0}" != 1 ]]
EOF
cat > "$bin/flock" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
cat > "$bin/mv" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == -Tf ]]; then
  shift
  rm -f "$2"
fi
/bin/mv "$@"
EOF
chmod +x "$bin/sha256sum" "$bin/ssh-keygen" "$bin/flock" "$bin/mv"

cat > "$bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'docker %s\n' "$*" >> "$FAKE_CALL_LOG"
printf 'docker-config=%s command=%s\n' "${DOCKER_CONFIG:-}" "$*" >> "$FAKE_CALL_LOG"
if [[ "${1:-}" == login ]]; then
  config_dir="${DOCKER_CONFIG:-$HOME/.docker}"
  mkdir -p "$config_dir"
  printf 'fixture-auth\n' > "$config_dir/config.json"
  cat >/dev/null
  exit 0
fi
if [[ "${1:-}" == network && "${2:-}" == inspect ]]; then
  exit 0
fi
if [[ "${1:-}" == image && "${2:-}" == inspect ]]; then
  exit 0
fi
if [[ "${1:-}" != compose ]]; then
  exit 1
fi
shift
while [[ $# -gt 0 && "${1:-}" != config && "${1:-}" != pull && "${1:-}" != up && "${1:-}" != ps && "${1:-}" != down ]]; do shift; done
case "${1:-}" in
  config)
    if [[ "${2:-}" == --format && "${3:-}" == json ]]; then
      printf '%s\n' '{"services":{"app-main":{"image":"docker.io/songchih/ieum-app-main@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},"app-ai":{"image":"docker.io/songchih/ieum-app-ai@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}}'
    fi
    ;;
  ps)
    [[ "${2:-}" == -q ]] || exit 1
    case "${3:-}" in app-main) printf 'main-container\n' ;; app-ai) printf 'ai-container\n' ;; *) exit 1 ;; esac
    ;;
  down)
    [[ "${FAKE_DOCKER_DOWN_FAIL:-0}" != 1 ]] || exit 42
    ;;
  pull)
    [[ "${FAKE_DOCKER_PULL_FAIL:-0}" != 1 ]] || exit 88
    if [[ "${FAKE_DOCKER_PULL_REQUIRE_AUTH:-0}" == 1 ]]; then
      [[ -n "${DOCKER_CONFIG:-}" && -f "$DOCKER_CONFIG/config.json" ]] || exit 91
    fi
    ;;
  *) ;;
esac
EOF
cat > "$bin/docker-inspect" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${@: -1}" in
  main-container) printf '%s\n' 'docker.io/songchih/ieum-app-main@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' ;;
  ai-container) printf '%s\n' 'docker.io/songchih/ieum-app-ai@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' ;;
  *) exit 1 ;;
esac
EOF
cat > "$bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'curl %s\n' "$*" >> "$FAKE_CALL_LOG"
case "${@: -1}" in
  http://127.0.0.1:18080/actuator/health|http://127.0.0.1:18084/actuator/health)
    if [[ "${FAKE_CURL_FAIL_ONCE:-0}" == 1 ]]; then
      [[ -n "${FAKE_CURL_FAIL_MARKER:-}" ]] || exit 2
      if [[ ! -e "$FAKE_CURL_FAIL_MARKER" ]]; then
        : >"$FAKE_CURL_FAIL_MARKER"
        exit 22
      fi
    fi
    printf '{"status":"UP"}\n'
    ;;
  https://ieum1.rktclgh.site/api/places/search) printf '{}' ;;
  https://ieum.rktclgh.site/api/places/search) printf '{}' ;;
  *) exit 22 ;;
esac
EOF
cat > "$bin/db-preflight" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'db-preflight %s\n' "$*" >> "$FAKE_CALL_LOG"
EOF
cat > "$bin/stage-nginx" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'stage-nginx %s\n' "$*" >> "$FAKE_CALL_LOG"
if [[ "${1:-}" == --remove && "${FAKE_STAGE_REMOVE_FAIL:-0}" == 1 ]]; then exit 74; fi
EOF
cat > "$bin/production-nginx" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'production-nginx %s\n' "$*" >> "$FAKE_CALL_LOG"
[[ "${FAKE_PRODUCTION_NGINX_FAIL:-0}" != 1 ]]
EOF
cat > "$bin/minio-presign-smoke" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'minio-presign-smoke %s\n' "$*" >> "$FAKE_CALL_LOG"
[[ "${FAKE_MINIO_PRESIGN_FAIL:-0}" != 1 ]]
EOF
chmod +x "$bin/docker" "$bin/docker-inspect" "$bin/curl" "$bin/db-preflight" "$bin/stage-nginx" "$bin/production-nginx" "$bin/minio-presign-smoke"

release_root="$tmp/srv/ieum"
mkdir -p "$release_root/staging" "$release_root/releases" "$release_root/locks"
chmod 700 "$release_root/staging" "$release_root/releases" "$release_root/locks"
state_root="$tmp/var/lib/ieum"
mkdir -p "$state_root/deployments" "$state_root/locks" "$state_root/maintenance"
chmod 700 "$state_root" "$state_root/deployments" "$state_root/locks" "$state_root/maintenance"
app_main_env="$tmp/etc/ieum/app-main.env"
app_ai_env="$tmp/etc/ieum/app-ai.env"
docker_registry_env="$tmp/etc/ieum/docker-registry.env"
pg_service="$tmp/etc/ieum/postgres.pg_service.conf"
pg_pass="$tmp/etc/ieum/postgres.pgpass"
origin_ca="$tmp/etc/cloudflare/rktclgh.site.pem"
mkdir -p "$(dirname "$app_main_env")"
mkdir -p "$(dirname "$origin_ca")"
printf 'APP_AI_INTERNAL_CALLBACK_TOKEN=fixture\n' > "$app_main_env"
printf 'APP_AI_INTERNAL_CALLBACK_TOKEN=fixture\n' > "$app_ai_env"
printf 'DOCKER_REGISTRY_USERNAME=fixture-user\nDOCKER_REGISTRY_PASSWORD=fixture-password\n' > "$docker_registry_env"
printf '[ieum_target_admin]\n' > "$pg_service"
printf 'fixture\n' > "$pg_pass"
printf '%s\n' '-----BEGIN CERTIFICATE-----' 'fixture' '-----END CERTIFICATE-----' > "$origin_ca"
printf 'IEUM_PRODUCTION_WRITE_FENCE=enabled\n' > "$state_root/maintenance/write-fence"
chmod 600 "$app_main_env" "$app_ai_env" "$docker_registry_env" "$pg_service" "$pg_pass" "$state_root/maintenance/write-fence"
chmod 644 "$origin_ca"
allowed_signers="$tmp/release-signing.allowed_signers"
printf 'ieum-release ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFixtureOnly fixture\n' > "$allowed_signers"
chmod 600 "$allowed_signers"

payload="$tmp/payload"
mkdir -p "$payload"
release_id="r-123456789-1-0123456789abcdef0123456789abcdef01234567"
frontend_sha="89abcdef0123456789abcdef0123456789abcdef"
cat > "$payload/release.env" <<'EOF'
APP_MAIN_IMAGE_DIGEST=docker.io/songchih/ieum-app-main@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
APP_AI_IMAGE_DIGEST=docker.io/songchih/ieum-app-ai@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
EOF
mkdir -p "$payload/deploy/onprem/nginx" "$payload/deploy/onprem/scripts" "$payload/deploy/scripts" "$payload/db/migrations"
printf '%s\n' 'services: {}' > "$payload/deploy/onprem/compose.yml"
printf '%s\n' 'server { }' > "$payload/deploy/onprem/nginx/ieum.rktclgh.site.conf"
printf '%s\n' 'server { }' > "$payload/deploy/onprem/nginx/files.rktclgh.site.conf"
printf '%s\n' 'server { }' > "$payload/deploy/onprem/nginx/ieum1.rktclgh.site.conf"
printf '%s\n' '#!/usr/bin/env bash' > "$payload/deploy/onprem/scripts/validate-runtime-env.sh"
printf '%s\n' '#!/usr/bin/env bash' 'printf '\''migration-helper\n'\'' >> "$FAKE_CALL_LOG"' '[[ "${FAKE_MIGRATION_FAIL:-0}" != 1 ]] || exit 1' ": <<'SQL'" '\i db/migrations/V999_fixture.sql' 'SQL' 'exit 0' > "$payload/deploy/scripts/apply-admin-dashboard-migrations.sh"
printf '%s\n' 'select 1;' > "$payload/db/migrations/V999_fixture.sql"
chmod 600 "$payload/release.env" "$payload/deploy/onprem/compose.yml" \
  "$payload/deploy/onprem/nginx/ieum.rktclgh.site.conf" \
  "$payload/deploy/onprem/nginx/files.rktclgh.site.conf" \
  "$payload/deploy/onprem/nginx/ieum1.rktclgh.site.conf" \
  "$payload/db/migrations/V999_fixture.sql"
chmod 700 "$payload/deploy/onprem/scripts/validate-runtime-env.sh" \
  "$payload/deploy/scripts/apply-admin-dashboard-migrations.sh"

migration_sha="$(
  cd "$payload"
  {
    shasum -a 256 deploy/scripts/apply-admin-dashboard-migrations.sh
    shasum -a 256 db/migrations/V999_fixture.sql
  } | shasum -a 256 | awk '{print $1}'
)"

build_envelope() {
  local candidate_release_id=$1 previous_json=$2 stem=$3
  local stage="$tmp/${stem}" release_tar="$tmp/${stem}/release.tar" release_sig="$tmp/${stem}/release.tar.sig" envelope="$tmp/${stem}.release-envelope.tar"
  mkdir -p "$stage"
  jq -cnS \
    --arg backend_sha "${candidate_release_id##*-}" \
    --arg frontend_sha "$frontend_sha" \
    --arg release_id "$candidate_release_id" \
    --arg migration_sha "$migration_sha" \
    --arg main 'docker.io/songchih/ieum-app-main@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
    --arg ai 'docker.io/songchih/ieum-app-ai@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' \
    --argjson previous "$previous_json" \
    '{schema:"ieum-release/v1",release_id:$release_id,backend_sha:$backend_sha,frontend_sha:$frontend_sha,github_run_id:($release_id | capture("^r-(?<id>[0-9]+)-").id | tonumber),github_run_attempt:($release_id | capture("^r-[0-9]+-(?<attempt>[1-9][0-9]*)-").attempt | tonumber),images:{app_main:$main,app_ai:$ai},rebuild:{app_main:true,app_ai:true},migration_sha256:$migration_sha,previous_release:$previous}' > "$payload/manifest.json"
  chmod 600 "$payload/manifest.json"
  (
    cd "$payload"
    shasum -a 256 \
      manifest.json release.env \
      deploy/onprem/compose.yml \
      deploy/onprem/nginx/ieum.rktclgh.site.conf \
      deploy/onprem/nginx/files.rktclgh.site.conf \
      deploy/onprem/nginx/ieum1.rktclgh.site.conf \
      deploy/onprem/scripts/validate-runtime-env.sh \
      deploy/scripts/apply-admin-dashboard-migrations.sh \
      db/migrations/V999_fixture.sql | awk '{print $1 "  " $2}' | LC_ALL=C sort > checksums.sha256
  )
  chmod 600 "$payload/checksums.sha256"
  tar -C "$payload" -cf "$release_tar" \
    manifest.json release.env checksums.sha256 \
    deploy/onprem/compose.yml \
    deploy/onprem/nginx/ieum.rktclgh.site.conf \
    deploy/onprem/nginx/files.rktclgh.site.conf \
    deploy/onprem/nginx/ieum1.rktclgh.site.conf \
    deploy/onprem/scripts/validate-runtime-env.sh \
    deploy/scripts/apply-admin-dashboard-migrations.sh \
    db/migrations/V999_fixture.sql
  chmod 600 "$release_tar"
  printf 'fixture signature\n' > "$release_sig"
  chmod 600 "$release_sig"
  tar -C "$stage" -cf "$envelope" release.tar release.tar.sig
  printf '%s\n' "$release_tar" "$envelope" "$(shasum -a 256 "$release_tar" | awk '{print $1}')"
}

first_bundle=()
while IFS= read -r bundle_part; do
  first_bundle[${#first_bundle[@]}]="$bundle_part"
done < <(build_envelope "$release_id" null first)
release_tar="${first_bundle[0]}"
envelope="${first_bundle[1]}"
bundle_sha="${first_bundle[2]}"

export PATH="$bin:$PATH"
export FAKE_CALL_LOG="$call_log"
export IEUM_RELEASE_TEST_MODE=1
export IEUM_RELEASE_ROOT="$release_root"
export IEUM_RELEASE_ALLOWED_SIGNERS="$allowed_signers"
export IEUM_RELEASE_STATE_ROOT="$state_root"
export IEUM_RELEASE_APP_MAIN_ENV="$app_main_env"
export IEUM_RELEASE_APP_AI_ENV="$app_ai_env"
export IEUM_RELEASE_DOCKER_REGISTRY_ENV="$docker_registry_env"
export IEUM_RELEASE_PGSERVICEFILE="$pg_service"
export IEUM_RELEASE_PGPASSFILE="$pg_pass"
export IEUM_RELEASE_WRITE_FENCE_PATH="$state_root/maintenance/write-fence"
export IEUM_RELEASE_PUBLIC_WRITE_COMMITTED_PATH="$state_root/state/public-write-committed"
export IEUM_RELEASE_ORIGIN_CA_CERT="$origin_ca"
export IEUM_RELEASE_DOCKER_BIN="$bin/docker"
export IEUM_RELEASE_DOCKER_INSPECT_BIN="$bin/docker-inspect"
export IEUM_RELEASE_CURL_BIN="$bin/curl"
export IEUM_RELEASE_DB_PREFLIGHT_BIN="$bin/db-preflight"
export IEUM_RELEASE_STAGE_NGINX_BIN="$bin/stage-nginx"
export IEUM_RELEASE_PRODUCTION_NGINX_BIN="$bin/production-nginx"
export IEUM_RELEASE_MINIO_PRESIGN_SMOKE_BIN="$bin/minio-presign-smoke"
export IEUM_RELEASE_HEALTH_ATTEMPTS=1

test -x "$helper" || fail "helper is missing or not executable"

empty_current_json="$($helper current --json)"
jq -e 'keys == ["backend_sha", "bundle_sha256", "frontend_sha", "images", "migration_sha256", "previous_release", "rebuild", "release_id"] and (.[] == null)' <<< "$empty_current_json" >/dev/null \
  || fail "empty current status did not return the stable null schema"

# Docker Hub images are public.  An absent optional credential file must use
# anonymous pull rather than preventing a first deployment before any runtime
# or Nginx state exists.
anonymous_release_root="$tmp/anonymous/srv/ieum"
anonymous_state_root="$tmp/anonymous/var/lib/ieum"
anonymous_registry_env="$tmp/anonymous/etc/ieum/docker-registry.env"
mkdir -p "$anonymous_release_root/staging" "$anonymous_release_root/releases" "$anonymous_release_root/locks" \
  "$anonymous_state_root/deployments" "$anonymous_state_root/locks" "$anonymous_state_root/maintenance"
chmod 700 "$anonymous_release_root/staging" "$anonymous_release_root/releases" "$anonymous_release_root/locks" \
  "$anonymous_state_root" "$anonymous_state_root/deployments" "$anonymous_state_root/locks" "$anonymous_state_root/maintenance"
printf 'IEUM_PRODUCTION_WRITE_FENCE=enabled\n' > "$anonymous_state_root/maintenance/write-fence"
chmod 600 "$anonymous_state_root/maintenance/write-fence"
anonymous_release_id="r-123456788-1-0123456789abcdef0123456789abcdef01234566"
anonymous_bundle=()
while IFS= read -r bundle_part; do
  anonymous_bundle[${#anonymous_bundle[@]}]="$bundle_part"
done < <(build_envelope "$anonymous_release_id" null anonymous-pull)
: > "$call_log"
if ! IEUM_RELEASE_ROOT="$anonymous_release_root" \
  IEUM_RELEASE_STATE_ROOT="$anonymous_state_root" \
  IEUM_RELEASE_DOCKER_REGISTRY_ENV="$anonymous_registry_env" \
  IEUM_RELEASE_WRITE_FENCE_PATH="$anonymous_state_root/maintenance/write-fence" \
  IEUM_RELEASE_PUBLIC_WRITE_COMMITTED_PATH="$anonymous_state_root/state/public-write-committed" \
  DOCKER_CONFIG="$tmp/inherited-docker-auth" \
  "$helper" apply \
    --release-id "$anonymous_release_id" \
    --expected-current none \
    --bundle-sha256 "${anonymous_bundle[2]}" < "${anonymous_bundle[1]}" >"$tmp/stdout" 2>"$tmp/stderr"; then
  cat "$tmp/stderr" >&2
  fail "public image release was rejected without optional registry credentials"
fi
[[ -L "$anonymous_release_root/current" ]] || fail "anonymous pull release did not promote current"
grep -F 'docker compose --project-name ieum' "$call_log" | grep -F ' pull app-main app-ai' >/dev/null \
  || fail "anonymous image pull was not invoked"
if grep -Fq 'docker login docker.io' "$call_log"; then
  fail "anonymous image pull unexpectedly logged into Docker Hub"
fi
if grep -Fq "docker-config=$tmp/inherited-docker-auth command=compose" "$call_log"; then
  fail "anonymous image pull inherited an ambient Docker credential directory"
fi
: > "$call_log"

# A migration helper can fail after its write fence has been entered.  That
# leaves a MANUAL_INTERVENTION journal with MIGRATION_STARTED=true, but it is
# not evidence that the schema reached a usable state.  A retry must refuse
# to promote that first release without an explicit success record.
failed_migration_release_root="$tmp/failed-migration/srv/ieum"
failed_migration_state_root="$tmp/failed-migration/var/lib/ieum"
mkdir -p "$failed_migration_release_root/staging" "$failed_migration_release_root/releases" "$failed_migration_release_root/locks" \
  "$failed_migration_state_root/deployments" "$failed_migration_state_root/locks" "$failed_migration_state_root/maintenance"
chmod 700 "$failed_migration_release_root/staging" "$failed_migration_release_root/releases" "$failed_migration_release_root/locks" \
  "$failed_migration_state_root" "$failed_migration_state_root/deployments" "$failed_migration_state_root/locks" "$failed_migration_state_root/maintenance"
printf 'IEUM_PRODUCTION_WRITE_FENCE=enabled\n' > "$failed_migration_state_root/maintenance/write-fence"
chmod 600 "$failed_migration_state_root/maintenance/write-fence"
failed_migration_release_id="r-123456797-1-0123456789abcdef0123456789abcdef01234573"
failed_migration_bundle=()
while IFS= read -r bundle_part; do
  failed_migration_bundle[${#failed_migration_bundle[@]}]="$bundle_part"
done < <(build_envelope "$failed_migration_release_id" null failed-migration)
: > "$call_log"
if FAKE_MIGRATION_FAIL=1 \
  IEUM_RELEASE_ROOT="$failed_migration_release_root" \
  IEUM_RELEASE_STATE_ROOT="$failed_migration_state_root" \
  IEUM_RELEASE_WRITE_FENCE_PATH="$failed_migration_state_root/maintenance/write-fence" \
  IEUM_RELEASE_PUBLIC_WRITE_COMMITTED_PATH="$failed_migration_state_root/state/public-write-committed" \
  "$helper" apply \
    --release-id "$failed_migration_release_id" \
    --expected-current none \
    --bundle-sha256 "${failed_migration_bundle[2]}" < "${failed_migration_bundle[1]}" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "failed first-release migration was accepted"
fi
grep -Fqx 'PHASE=MANUAL_INTERVENTION' "$failed_migration_state_root/deployments/$failed_migration_release_id/activation.env" \
  || fail "failed first-release migration did not require manual intervention"
grep -Fqx 'MIGRATION_STARTED=true' "$failed_migration_state_root/deployments/$failed_migration_release_id/activation.env" \
  || fail "failed first-release migration was not marked started"
grep -Fqx 'MIGRATION_SUCCEEDED=false' "$failed_migration_state_root/deployments/$failed_migration_release_id/activation.env" \
  || fail "failed first-release migration was incorrectly marked successful"
[[ ! -e "$failed_migration_release_root/current" ]] || fail "failed first-release migration changed current release"
: > "$call_log"
if IEUM_RELEASE_ROOT="$failed_migration_release_root" \
  IEUM_RELEASE_STATE_ROOT="$failed_migration_state_root" \
  IEUM_RELEASE_WRITE_FENCE_PATH="$failed_migration_state_root/maintenance/write-fence" \
  IEUM_RELEASE_PUBLIC_WRITE_COMMITTED_PATH="$failed_migration_state_root/state/public-write-committed" \
  "$helper" apply \
    --release-id "$failed_migration_release_id" \
    --expected-current none \
    --bundle-sha256 "${failed_migration_bundle[2]}" < "${failed_migration_bundle[1]}" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "failed first-release migration was resumed without success evidence"
fi
[[ ! -e "$failed_migration_release_root/current" ]] || fail "failed first-release migration retry changed current release"

# A pull failure occurs before candidate services or staging Nginx exist.  Its
# rollback must not attempt to remove a non-existent staging configuration.
pre_runtime_release_root="$tmp/pre-runtime/srv/ieum"
pre_runtime_state_root="$tmp/pre-runtime/var/lib/ieum"
mkdir -p "$pre_runtime_release_root/staging" "$pre_runtime_release_root/releases" "$pre_runtime_release_root/locks" \
  "$pre_runtime_state_root/deployments" "$pre_runtime_state_root/locks" "$pre_runtime_state_root/maintenance"
chmod 700 "$pre_runtime_release_root/staging" "$pre_runtime_release_root/releases" "$pre_runtime_release_root/locks" \
  "$pre_runtime_state_root" "$pre_runtime_state_root/deployments" "$pre_runtime_state_root/locks" "$pre_runtime_state_root/maintenance"
printf 'IEUM_PRODUCTION_WRITE_FENCE=enabled\n' > "$pre_runtime_state_root/maintenance/write-fence"
chmod 600 "$pre_runtime_state_root/maintenance/write-fence"
pre_runtime_release_id="r-123456787-1-0123456789abcdef0123456789abcdef01234565"
pre_runtime_bundle=()
while IFS= read -r bundle_part; do
  pre_runtime_bundle[${#pre_runtime_bundle[@]}]="$bundle_part"
done < <(build_envelope "$pre_runtime_release_id" null pre-runtime-cleanup)
: > "$call_log"
if FAKE_DOCKER_PULL_FAIL=1 FAKE_STAGE_REMOVE_FAIL=1 \
  IEUM_RELEASE_ROOT="$pre_runtime_release_root" \
  IEUM_RELEASE_STATE_ROOT="$pre_runtime_state_root" \
  IEUM_RELEASE_WRITE_FENCE_PATH="$pre_runtime_state_root/maintenance/write-fence" \
  IEUM_RELEASE_PUBLIC_WRITE_COMMITTED_PATH="$pre_runtime_state_root/state/public-write-committed" \
  "$helper" apply \
    --release-id "$pre_runtime_release_id" \
    --expected-current none \
    --bundle-sha256 "${pre_runtime_bundle[2]}" < "${pre_runtime_bundle[1]}" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "pull failure was accepted"
fi
grep -Fqx 'PHASE=FAILED_PRE_MIGRATION_ROLLED_BACK' "$pre_runtime_state_root/deployments/$pre_runtime_release_id/activation.env" \
  || fail "pre-runtime pull failure did not complete a clean rollback"
if grep -Fq 'stage-nginx --remove' "$call_log"; then
  fail "pre-runtime pull failure attempted to remove an unstaged Nginx configuration"
fi
if grep -Fq 'manual intervention is required' "$tmp/stderr"; then
  fail "pre-runtime pull failure incorrectly required manual intervention"
fi
[[ ! -e "$pre_runtime_release_root/current" ]] || fail "pre-runtime pull failure changed current release"
: > "$call_log"

if FAKE_SIGNATURE_FAIL=1 "$helper" apply \
  --release-id "$release_id" \
  --expected-current none \
  --bundle-sha256 "$bundle_sha" < "$envelope" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "invalid signature was accepted"
fi

grep -F 'release signature verification failed' "$tmp/stderr" >/dev/null || fail "invalid signature rejection was not reported"
[[ ! -e "$release_root/current" ]] || fail "invalid signature promoted a current release"
grep -F 'ssh-keygen -Y verify -n ieum-release -I ieum-release' "$call_log" >/dev/null || fail "signature verifier was not invoked with the fixed namespace and identity"

: > "$call_log"
if FAKE_PRODUCTION_NGINX_FAIL=1 "$helper" apply \
  --release-id "$release_id" \
  --expected-current none \
  --bundle-sha256 "$bundle_sha" < "$envelope" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "production ingress failure was accepted before current commit"
fi

grep -F 'production ingress gate failed' "$tmp/stderr" >/dev/null || fail "pre-commit production ingress failure was not reported"
[[ ! -e "$release_root/current" ]] || fail "pre-commit production ingress failure changed current symlink"
grep -Fqx 'PHASE=MANUAL_INTERVENTION' "$state_root/deployments/$release_id/activation.env" || fail "post-migration production ingress failure did not journal manual intervention"
grep -Fqx 'MIGRATION_SUCCEEDED=true' "$state_root/deployments/$release_id/activation.env" \
  || fail "post-migration production ingress failure lost migration-success evidence"

rm "$state_root/maintenance/write-fence"
if "$helper" apply \
  --release-id "$release_id" \
  --expected-current none \
  --bundle-sha256 "$bundle_sha" < "$envelope" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "manual-intervention recovery proceeded without a write fence"
fi
grep -F 'production write fence' "$tmp/stderr" >/dev/null || fail "missing resume write fence was not reported"
[[ ! -e "$release_root/current" ]] || fail "missing resume write fence changed current symlink"
grep -Fqx 'PHASE=MANUAL_INTERVENTION' "$state_root/deployments/$release_id/activation.env" \
  || fail "missing resume write fence did not preserve manual journal"
printf 'IEUM_PRODUCTION_WRITE_FENCE=enabled\n' > "$state_root/maintenance/write-fence"
chmod 600 "$state_root/maintenance/write-fence"

: > "$call_log"
if FAKE_PRODUCTION_NGINX_FAIL=1 "$helper" apply \
  --release-id "$release_id" \
  --expected-current none \
  --bundle-sha256 "$bundle_sha" < "$envelope" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "manual-intervention recovery accepted a failed ingress gate"
fi
[[ ! -e "$release_root/current" ]] || fail "failed manual-intervention recovery changed current symlink"
grep -Fqx 'PHASE=MANUAL_INTERVENTION' "$state_root/deployments/$release_id/activation.env" \
  || fail "failed manual-intervention recovery did not preserve manual journal"
if grep -Fxq 'migration-helper' "$call_log"; then
  fail "failed manual-intervention recovery reran database migrations"
fi

if ! "$helper" apply \
  --release-id "$release_id" \
  --expected-current none \
  --bundle-sha256 "$bundle_sha" < "$envelope" >"$tmp/stdout" 2>"$tmp/stderr"; then
  cat "$tmp/stderr" >&2
  fail "manual-intervention recovery was rejected"
fi
grep -F "release $release_id resumed after manual intervention" "$tmp/stdout" >/dev/null \
  || fail "manual-intervention recovery was not reported"
[[ -L "$release_root/current" ]] || fail "manual-intervention recovery did not create current symlink"
grep -Fqx 'PHASE=ACTIVE' "$state_root/deployments/$release_id/activation.env" \
  || fail "manual-intervention recovery did not journal active"
grep -F -- "production-nginx --release-id $release_id --confirm-public-ingress --allow-pending-activation" "$call_log" >/dev/null \
  || fail "manual-intervention recovery did not use the pending ingress gate"
if grep -Fxq 'migration-helper' "$call_log"; then
  fail "manual-intervention recovery reran database migrations"
fi
rm -rf "$release_root/releases/$release_id" "$state_root/deployments/$release_id"
rm -f "$release_root/current"

if FAKE_MINIO_PRESIGN_FAIL=1 "$helper" apply \
  --release-id "$release_id" \
  --expected-current none \
  --bundle-sha256 "$bundle_sha" < "$envelope" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "MinIO presign failure was accepted before current commit"
fi
grep -F 'production ingress gate failed' "$tmp/stderr" >/dev/null || fail "MinIO presign gate failure was not reported"
grep -F 'minio-presign-smoke ' "$call_log" >/dev/null || fail "MinIO presign gate was not invoked"
[[ ! -e "$release_root/current" ]] || fail "MinIO presign failure changed current symlink"
grep -Fqx 'PHASE=MANUAL_INTERVENTION' "$state_root/deployments/$release_id/activation.env" || fail "MinIO presign failure did not journal manual intervention"
rm -rf "$release_root/releases/$release_id" "$state_root/deployments/$release_id"

if ! "$helper" apply \
  --release-id "$release_id" \
  --expected-current none \
  --bundle-sha256 "$bundle_sha" < "$envelope" >"$tmp/stdout" 2>"$tmp/stderr"; then
  cat "$tmp/stderr" >&2
  fail "valid signed release was rejected"
fi

[[ -L "$release_root/current" ]] || fail "valid signed release did not promote current symlink"
[[ "$(readlink "$release_root/current")" == "$release_root/releases/$release_id" ]] || fail "current symlink target is wrong"
[[ -f "$state_root/deployments/$release_id/activation.env" ]] || fail "runtime activation journal was not created"
grep -Fqx 'PHASE=ACTIVE' "$state_root/deployments/$release_id/activation.env" || fail "current release was not journaled active"
grep -F 'db-preflight --kind production --admin-service ieum_target_admin' "$call_log" >/dev/null || fail "database preflight did not run"
grep -F 'docker compose --project-name ieum' "$call_log" >/dev/null || fail "compose was not invoked through the fixed project"
grep -F 'docker login docker.io --username fixture-user --password-stdin' "$call_log" >/dev/null || fail "private registry login was not invoked through password-stdin"
if grep -F 'fixture-password' "$call_log" >/dev/null; then fail "registry password leaked into the Docker call log"; fi
[[ ! -e "$HOME/.docker/config.json" ]] || fail "registry login persisted credentials in HOME"
if find "$release_root/staging" -maxdepth 1 -name '.docker-auth.*' -print -quit | grep -q .; then
  fail "registry credential workspace was not removed"
fi
grep -F 'stage-nginx --release-id' "$call_log" >/dev/null || fail "staging Nginx was not invoked"
grep -F -- "--cacert $origin_ca --resolve ieum1.rktclgh.site:443:127.0.0.1" "$call_log" >/dev/null || fail "origin smoke did not use the fixed CA certificate"
grep -F 'production-nginx --release-id' "$call_log" >/dev/null || fail "production Nginx was not invoked"
grep -F 'APP_MAIN_IMAGE_DIGEST=docker.io/songchih/ieum-app-main@sha256:' "$release_root/current/release.env" >/dev/null || fail "release payload was not promoted"
current_json="$($helper current --json)"
grep -F "\"release_id\":\"$release_id\"" <<< "$current_json" >/dev/null || fail "current status omitted the release id"
grep -F "\"bundle_sha256\":\"$bundle_sha\"" <<< "$current_json" >/dev/null || fail "current status omitted the bundle checksum"

rm "$release_root/current"
if ! "$helper" apply \
  --release-id "$release_id" \
  --expected-current none \
  --bundle-sha256 "$bundle_sha" < "$envelope" >"$tmp/stdout" 2>"$tmp/stderr"; then
  cat "$tmp/stderr" >&2
  fail "interrupted first-promotion recovery was rejected"
fi
grep -F "release $release_id reconciled after an interrupted promotion" "$tmp/stdout" >/dev/null || fail "interrupted first-promotion recovery was not reported"
[[ -L "$release_root/current" ]] || fail "interrupted first-promotion recovery did not restore current"

# If a process dies after recording COMMIT_PENDING but before the current-link
# swap, the first release has no current pointer.  Its durable migration
# success record permits only a runtime/gate retry, never another migration.
rm "$release_root/current"
cat > "$state_root/deployments/$release_id/activation.env" <<EOF
RELEASE_ID=$release_id
PHASE=COMMIT_PENDING
MIGRATION_STARTED=true
MIGRATION_SUCCEEDED=true
EOF
chmod 600 "$state_root/deployments/$release_id/activation.env"
: > "$call_log"
if ! "$helper" apply \
  --release-id "$release_id" \
  --expected-current none \
  --bundle-sha256 "$bundle_sha" < "$envelope" >"$tmp/stdout" 2>"$tmp/stderr"; then
  cat "$tmp/stderr" >&2
  fail "migration-complete pending first release was not resumed"
fi
[[ -L "$release_root/current" ]] || fail "pending first-release recovery did not create current"
if grep -Fxq 'migration-helper' "$call_log"; then
  fail "pending first-release recovery reran database migrations"
fi

# A pending first release without durable migration success evidence is
# ambiguous and must stay blocked even though a migration attempt began.
rm "$release_root/current"
cat > "$state_root/deployments/$release_id/activation.env" <<EOF
RELEASE_ID=$release_id
PHASE=COMMIT_PENDING
MIGRATION_STARTED=true
MIGRATION_SUCCEEDED=false
EOF
chmod 600 "$state_root/deployments/$release_id/activation.env"
if "$helper" apply \
  --release-id "$release_id" \
  --expected-current none \
  --bundle-sha256 "$bundle_sha" < "$envelope" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "pending first release without migration-success evidence was resumed"
fi
[[ ! -e "$release_root/current" ]] || fail "unproven pending first release changed current"
cat > "$state_root/deployments/$release_id/activation.env" <<EOF
RELEASE_ID=$release_id
PHASE=ACTIVE
MIGRATION_STARTED=true
MIGRATION_SUCCEEDED=true
EOF
chmod 600 "$state_root/deployments/$release_id/activation.env"
ln -s "$release_root/releases/$release_id" "$release_root/current"

if ! "$helper" apply \
  --release-id "$release_id" \
  --expected-current none \
  --bundle-sha256 "$bundle_sha" < "$envelope" >"$tmp/stdout" 2>"$tmp/stderr"; then
  cat "$tmp/stderr" >&2
  fail "exact retry after a lost response was not idempotent"
fi
grep -F "release $release_id already accepted" "$tmp/stdout" >/dev/null || fail "idempotent retry was not reported"

: > "$call_log"
if FAKE_PRODUCTION_NGINX_FAIL=1 "$helper" apply \
  --release-id "$release_id" \
  --expected-current none \
  --bundle-sha256 "$bundle_sha" < "$envelope" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "production ingress failure was accepted"
fi
grep -F 'production ingress gate failed' "$tmp/stderr" >/dev/null || fail "production ingress failure was not reported"
grep -Fqx 'PHASE=MANUAL_INTERVENTION' "$state_root/deployments/$release_id/activation.env" || fail "production ingress failure did not journal manual intervention"
if ! "$helper" apply \
  --release-id "$release_id" \
  --expected-current none \
  --bundle-sha256 "$bundle_sha" < "$envelope" >"$tmp/stdout" 2>"$tmp/stderr"; then
  cat "$tmp/stderr" >&2
  fail "production ingress gate retry was rejected"
fi
grep -F "release $release_id already accepted" "$tmp/stdout" >/dev/null || fail "production ingress gate retry was not reported"

other_release_id="r-123456790-1-abcdef0123456789abcdef0123456789abcdef01"
previous_release_json="$(jq -cn --arg id "$release_id" --arg sha "$bundle_sha" '{release_id:$id,bundle_sha256:$sha}')"
other_bundle=()
while IFS= read -r bundle_part; do
  other_bundle[${#other_bundle[@]}]="$bundle_part"
done < <(build_envelope "$other_release_id" "$previous_release_json" stale)

credential_insecure_release_id="r-123456796-1-0123456789abcdef0123456789abcdef01234572"
credential_insecure_bundle=()
while IFS= read -r bundle_part; do
  credential_insecure_bundle[${#credential_insecure_bundle[@]}]="$bundle_part"
done < <(build_envelope "$credential_insecure_release_id" "$previous_release_json" insecure-registry-credentials)
: > "$call_log"
chmod 640 "$docker_registry_env"
if "$helper" apply \
  --release-id "$credential_insecure_release_id" \
  --expected-current "$release_id" \
  --bundle-sha256 "${credential_insecure_bundle[2]}" < "${credential_insecure_bundle[1]}" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "release activated with an insecure Docker registry credential file"
fi
grep -F 'Docker registry credential file is unsafe' "$tmp/stderr" >/dev/null || fail "insecure Docker registry credential rejection was not reported"
if grep -Eq 'docker (login|compose .* (pull|down))' "$call_log"; then fail "insecure credentials were checked after Docker login/pull/stop"; fi
[[ "$(readlink "$release_root/current")" == "$release_root/releases/$release_id" ]] || fail "insecure Docker credentials changed current release"
chmod 600 "$docker_registry_env"

fenced_release_id="r-123456792-1-0123456789abcdef0123456789abcdef01234568"
fenced_bundle=()
while IFS= read -r bundle_part; do
  fenced_bundle[${#fenced_bundle[@]}]="$bundle_part"
done < <(build_envelope "$fenced_release_id" "$previous_release_json" missing-fence)
ca_missing_release_id="r-123456794-1-0123456789abcdef0123456789abcdef01234570"
ca_missing_bundle=()
while IFS= read -r bundle_part; do
  ca_missing_bundle[${#ca_missing_bundle[@]}]="$bundle_part"
done < <(build_envelope "$ca_missing_release_id" "$previous_release_json" missing-ca)
rm "$origin_ca"
if "$helper" apply \
  --release-id "$ca_missing_release_id" \
  --expected-current "$release_id" \
  --bundle-sha256 "${ca_missing_bundle[2]}" < "${ca_missing_bundle[1]}" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "release activated without the origin CA certificate"
fi
grep -F 'origin CA certificate is unsafe' "$tmp/stderr" >/dev/null || fail "missing origin CA certificate rejection was not reported"
[[ "$(readlink "$release_root/current")" == "$release_root/releases/$release_id" ]] || fail "missing origin CA certificate changed current release"
printf '%s\n' '-----BEGIN CERTIFICATE-----' 'fixture' '-----END CERTIFICATE-----' > "$origin_ca"
chmod 644 "$origin_ca"
cp "$release_root/current/state.env" "$tmp/current-state.before"
awk '
  /^MIGRATION_SHA256=/ { print "MIGRATION_SHA256=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"; next }
  { print }
' "$release_root/current/state.env" > "$tmp/current-state.changed"
chmod 600 "$tmp/current-state.changed"
mv "$tmp/current-state.changed" "$release_root/current/state.env"
grep -Fqx 'MIGRATION_SHA256=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc' "$release_root/current/state.env" || fail "failed to create a migration-digest mismatch fixture"
rm "$state_root/maintenance/write-fence"
if "$helper" apply \
  --release-id "$fenced_release_id" \
  --expected-current "$release_id" \
  --bundle-sha256 "${fenced_bundle[2]}" < "${fenced_bundle[1]}" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "migration-changing release was accepted without a write fence"
fi
grep -Fqx 'PHASE=FAILED_PRE_MIGRATION_ROLLED_BACK' "$state_root/deployments/$fenced_release_id/activation.env" || fail "missing write fence did not journal pre-migration rollback"
[[ "$(readlink "$release_root/current")" == "$release_root/releases/$release_id" ]] || fail "missing write fence changed current release"
printf 'IEUM_PRODUCTION_WRITE_FENCE=enabled\n' > "$state_root/maintenance/write-fence"
chmod 600 "$state_root/maintenance/write-fence"
mv "$tmp/current-state.before" "$release_root/current/state.env"
if "$helper" apply \
  --release-id "$fenced_release_id" \
  --expected-current "$release_id" \
  --bundle-sha256 "${fenced_bundle[2]}" < "${fenced_bundle[1]}" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "a failed pre-migration candidate was retried as an active release"
fi

migration_failure_release_id="r-123456793-1-0123456789abcdef0123456789abcdef01234569"
migration_failure_bundle=()
while IFS= read -r bundle_part; do
  migration_failure_bundle[${#migration_failure_bundle[@]}]="$bundle_part"
done < <(build_envelope "$migration_failure_release_id" "$previous_release_json" migration-failure)
cp "$release_root/current/state.env" "$tmp/current-state.before-migration-failure"
awk '
  /^MIGRATION_SHA256=/ { print "MIGRATION_SHA256=dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"; next }
  { print }
' "$release_root/current/state.env" > "$tmp/current-state.changed-migration-failure"
chmod 600 "$tmp/current-state.changed-migration-failure"
mv "$tmp/current-state.changed-migration-failure" "$release_root/current/state.env"
if FAKE_MIGRATION_FAIL=1 "$helper" apply \
  --release-id "$migration_failure_release_id" \
  --expected-current "$release_id" \
  --bundle-sha256 "${migration_failure_bundle[2]}" < "${migration_failure_bundle[1]}" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "migration helper failure was accepted"
fi
grep -Fqx 'PHASE=MANUAL_INTERVENTION' "$state_root/deployments/$migration_failure_release_id/activation.env" || fail "migration helper failure did not prevent automatic rollback"
[[ "$(readlink "$release_root/current")" == "$release_root/releases/$release_id" ]] || fail "migration helper failure changed current release"
if grep -F "stage-nginx --release-id $migration_failure_release_id" "$call_log" >/dev/null; then
  fail "migration helper failure continued into Nginx staging"
fi
grep -F "stage-nginx --release-id $release_id" "$call_log" >/dev/null || fail "migration helper failure did not restore prior staging Nginx"
mv "$tmp/current-state.before-migration-failure" "$release_root/current/state.env"

if "$helper" apply \
  --release-id "$other_release_id" \
  --expected-current none \
  --bundle-sha256 "${other_bundle[2]}" < "${other_bundle[1]}" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "stale compare-and-swap release was accepted"
fi
grep -F 'current release does not match expected value' "$tmp/stderr" >/dev/null || fail "stale compare-and-swap rejection was not reported"

# A same-migration release is rollback-safe: it can be activated without a
# schema change, then atomically returned to the recorded previous release.
if ! "$helper" apply \
  --release-id "$other_release_id" \
  --expected-current "$release_id" \
  --bundle-sha256 "${other_bundle[2]}" < "${other_bundle[1]}" >"$tmp/stdout" 2>"$tmp/stderr"; then
  cat "$tmp/stderr" >&2
  fail "same-migration release was rejected before rollback test"
fi
[[ "$(readlink "$release_root/current")" == "$release_root/releases/$other_release_id" ]] || fail "same-migration release did not become current"
grep -Fqx 'MIGRATION_STARTED=false' "$state_root/deployments/$other_release_id/activation.env" || fail "same-migration activation was not marked rollback-safe"
rm "$release_root/current"
ln -s "$release_root/releases/$release_id" "$release_root/current"
if ! "$helper" apply \
  --release-id "$other_release_id" \
  --expected-current "$release_id" \
  --bundle-sha256 "${other_bundle[2]}" < "${other_bundle[1]}" >"$tmp/stdout" 2>"$tmp/stderr"; then
  cat "$tmp/stderr" >&2
  fail "active journal did not reconcile a crash before the current-link swap"
fi
grep -F "release $other_release_id reconciled after an interrupted promotion" "$tmp/stdout" >/dev/null || fail "active journal reconciliation was not reported"
[[ "$(readlink "$release_root/current")" == "$release_root/releases/$other_release_id" ]] || fail "active journal reconciliation restored the wrong release"
cp "$release_root/current/state.env" "$tmp/rollback-current-state"
awk '
  /^APP_MAIN_IMAGE_DIGEST=/ { print "APP_MAIN_IMAGE_DIGEST=invalid"; next }
  { print }
' "$release_root/current/state.env" > "$tmp/rollback-current-state.tampered"
chmod 600 "$tmp/rollback-current-state.tampered"
mv "$tmp/rollback-current-state.tampered" "$release_root/current/state.env"
if "$helper" rollback --expected-current "$other_release_id" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "rollback accepted a tampered current state"
fi
grep -F 'current state has an invalid app-main image' "$tmp/stderr" >/dev/null || fail "tampered current-state rejection was not reported"
[[ "$(readlink "$release_root/current")" == "$release_root/releases/$other_release_id" ]] || fail "tampered rollback changed current symlink"
mv "$tmp/rollback-current-state" "$release_root/current/state.env"
: > "$call_log"
rm -f "$tmp/rollback-health-failed"
if FAKE_CURL_FAIL_ONCE=1 FAKE_CURL_FAIL_MARKER="$tmp/rollback-health-failed" \
  "$helper" rollback --expected-current "$other_release_id" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "rollback accepted a candidate runtime health failure"
fi
[[ "$(readlink "$release_root/current")" == "$release_root/releases/$other_release_id" ]] || fail "failed rollback changed current symlink"
health_restore_calls=$(grep -Fc '/actuator/health' "$call_log" || true)
[[ "$health_restore_calls" -ge 3 ]] || fail "rollback failure did not validate restored current runtime"
if ! "$helper" rollback --expected-current "$other_release_id" >"$tmp/stdout" 2>"$tmp/stderr"; then
  cat "$tmp/stderr" >&2
  fail "safe rollback was rejected"
fi
grep -F "rollback to $release_id activated" "$tmp/stdout" >/dev/null || fail "rollback success was not reported"
[[ "$(readlink "$release_root/current")" == "$release_root/releases/$release_id" ]] || fail "rollback did not atomically restore the previous release"
grep -F "production-nginx --release-id $release_id --confirm-public-ingress" "$call_log" >/dev/null || fail "rollback did not restore production ingress for the target release"

mkdir -p "$state_root/state"
chmod 700 "$state_root/state"
printf 'IEUM_PUBLIC_WRITE_COMMITTED=enabled\n' > "$state_root/state/public-write-committed"
chmod 600 "$state_root/state/public-write-committed"
if "$helper" rollback --expected-current "$release_id" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "rollback proceeded after public writes committed"
fi
grep -F 'rollback is blocked after public writes have committed' "$tmp/stderr" >/dev/null || fail "public-write rollback fence was not reported"
rm -f "$state_root/state/public-write-committed"

printf '%s\n' '\i /tmp/unverified.sql' >> "$payload/deploy/scripts/apply-admin-dashboard-migrations.sh"
chmod 700 "$payload/deploy/scripts/apply-admin-dashboard-migrations.sh"
migration_sha="$(
  cd "$payload"
  {
    shasum -a 256 deploy/scripts/apply-admin-dashboard-migrations.sh
    shasum -a 256 db/migrations/V999_fixture.sql
  } | shasum -a 256 | awk '{print $1}'
)"
bad_include_release_id="r-123456791-1-fedcba9876543210fedcba9876543210fedcba98"
bad_include_bundle=()
while IFS= read -r bundle_part; do
  bad_include_bundle[${#bad_include_bundle[@]}]="$bundle_part"
done < <(build_envelope "$bad_include_release_id" "$previous_release_json" bad-include)
if "$helper" apply \
  --release-id "$bad_include_release_id" \
  --expected-current "$release_id" \
  --bundle-sha256 "${bad_include_bundle[2]}" < "${bad_include_bundle[1]}" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "release accepted a migration helper with an external include"
fi
grep -F 'release payload migrations do not match the migration helper' "$tmp/stderr" >/dev/null || fail "external migration include rejection was not reported"

cp "$state_root/deployments/$release_id/activation.env" "$tmp/activation.before-empty-success-marker"
awk '
  /^MIGRATION_SUCCEEDED=/ { print "MIGRATION_SUCCEEDED="; next }
  { print }
' "$state_root/deployments/$release_id/activation.env" > "$tmp/activation.empty-success-marker"
chmod 600 "$tmp/activation.empty-success-marker"
mv "$tmp/activation.empty-success-marker" "$state_root/deployments/$release_id/activation.env"
if "$helper" current --json >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "current status accepted an empty migration-success marker"
fi
grep -F 'activation journal has an invalid migration-success marker' "$tmp/stderr" >/dev/null \
  || fail "empty migration-success marker rejection was not reported"
mv "$tmp/activation.before-empty-success-marker" "$state_root/deployments/$release_id/activation.env"

printf 'tampered\n' >> "$release_root/current/release.env"
chmod 600 "$release_root/current/release.env"
if "$helper" apply \
  --release-id "$release_id" \
  --expected-current none \
  --bundle-sha256 "$bundle_sha" < "$envelope" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "tampered accepted release passed idempotent retry integrity verification"
fi
grep -F 'release integrity check failed: payload checksum verification failed' "$tmp/stderr" >/dev/null || fail "tampered target rejection was not reported"

cat > "$release_root/current/state.env" <<EOF
RELEASE_ID=$release_id
BUNDLE_SHA256=$bundle_sha
BACKEND_SHA=${release_id##*-}
FRONTEND_SHA=$frontend_sha
MIGRATION_SHA256=$migration_sha
APP_MAIN_IMAGE_DIGEST=invalid-json-breaker\"}
APP_AI_IMAGE_DIGEST=docker.io/songchih/ieum-app-ai@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
APP_MAIN_REBUILT=true
APP_AI_REBUILT=true
PREVIOUS_RELEASE_ID=
PREVIOUS_BUNDLE_SHA256=
EOF
chmod 600 "$release_root/current/state.env"
if "$helper" current --json >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "current status accepted a malformed image state value"
fi
grep -F 'current state has an invalid app-main image' "$tmp/stderr" >/dev/null || fail "malformed image state rejection was not reported"

rm "$release_root/current"
mkdir "$release_root/current"
if "$helper" apply \
  --release-id "$other_release_id" \
  --expected-current none \
  --bundle-sha256 "${other_bundle[2]}" < "${other_bundle[1]}" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "release accepted a current path that is a directory"
fi
grep -F 'current release path must be an absent or symlink' "$tmp/stderr" >/dev/null || fail "current directory rejection was not reported"
[[ ! -e "$release_root/current/$other_release_id" ]] || fail "release wrote beneath current directory"

echo "deploy release test: PASS"
