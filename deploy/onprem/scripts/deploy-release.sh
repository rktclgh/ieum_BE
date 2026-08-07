#!/usr/bin/env bash
set -euo pipefail
umask 077

MAX_ENVELOPE_BYTES=268435456
ALLOWED_MAIN_REPOSITORY='docker.io/songchih/ieum-app-main'
ALLOWED_AI_REPOSITORY='docker.io/songchih/ieum-app-ai'

MANIFEST_BACKEND_SHA=''
MANIFEST_FRONTEND_SHA=''
MANIFEST_MIGRATION_SHA256=''
MANIFEST_APP_MAIN_REBUILT=''
MANIFEST_APP_AI_REBUILT=''
MANIFEST_PREVIOUS_RELEASE_ID=''
MANIFEST_PREVIOUS_BUNDLE_SHA256=''

die() { printf 'ieum deploy release: %s\n' "$*" >&2; exit 2; }
usage() {
  printf '%s\n' "usage: $0 current --json | $0 apply --release-id r-<run>-<attempt>-<backend-sha> --expected-current <release-id|none> --bundle-sha256 <64-lowercase-hex> | $0 rollback --expected-current <release-id>" >&2
  exit 2
}
is_absolute() { [[ "$1" = /* ]]; }
mode_of() { stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1"; }
owner_of() { stat -c '%u' "$1" 2>/dev/null || stat -f '%u' "$1"; }
same_owner() { [[ "$(owner_of "$1")" == "$EUID" ]]; }
private_file() { [[ -f "$1" && ! -L "$1" ]] && same_owner "$1" && [[ "$(mode_of "$1")" == 600 ]]; }
private_dir() { [[ -d "$1" && ! -L "$1" ]] && same_owner "$1" && [[ "$(mode_of "$1")" == 700 ]]; }

if [[ "$EUID" -eq 0 ]]; then
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
  export PATH
  RELEASE_ROOT=/srv/ieum
  ALLOWED_SIGNERS=/etc/ieum/release-signing.allowed_signers
  STATE_ROOT=/var/lib/ieum
  APP_MAIN_ENV=/etc/ieum/app-main.env
  APP_AI_ENV=/etc/ieum/app-ai.env
  DOCKER_REGISTRY_ENV=/etc/ieum/docker-registry.env
  PGSERVICEFILE=/etc/ieum/postgres.pg_service.conf
  PGPASSFILE=/etc/ieum/postgres.pgpass
  WRITE_FENCE_PATH=/var/lib/ieum/maintenance/write-fence
  PUBLIC_WRITE_COMMITTED_PATH=/var/lib/ieum/state/public-write-committed
  ORIGIN_CA_CERT=/etc/cloudflare/rktclgh.site.pem
  DOCKER_BIN=/usr/bin/docker
  DOCKER_INSPECT_BIN=/usr/bin/docker
  CURL_BIN=/usr/bin/curl
  DB_PREFLIGHT_BIN=/usr/local/sbin/ieum-db-preflight
  STAGE_NGINX_BIN=/usr/local/sbin/ieum-install-staging-nginx
  PRODUCTION_NGINX_BIN=/usr/local/sbin/ieum-install-production-nginx
  MINIO_PRESIGN_SMOKE_BIN=/usr/local/sbin/ieum-minio-presign-smoke
  HEALTH_ATTEMPTS=30
else
  [[ "${IEUM_RELEASE_TEST_MODE:-}" == 1 ]] || die "must run as root"
  RELEASE_ROOT="${IEUM_RELEASE_ROOT:-}"
  ALLOWED_SIGNERS="${IEUM_RELEASE_ALLOWED_SIGNERS:-}"
  STATE_ROOT="${IEUM_RELEASE_STATE_ROOT:-}"
  APP_MAIN_ENV="${IEUM_RELEASE_APP_MAIN_ENV:-}"
  APP_AI_ENV="${IEUM_RELEASE_APP_AI_ENV:-}"
  DOCKER_REGISTRY_ENV="${IEUM_RELEASE_DOCKER_REGISTRY_ENV:-}"
  PGSERVICEFILE="${IEUM_RELEASE_PGSERVICEFILE:-}"
  PGPASSFILE="${IEUM_RELEASE_PGPASSFILE:-}"
  WRITE_FENCE_PATH="${IEUM_RELEASE_WRITE_FENCE_PATH:-}"
  PUBLIC_WRITE_COMMITTED_PATH="${IEUM_RELEASE_PUBLIC_WRITE_COMMITTED_PATH:-}"
  ORIGIN_CA_CERT="${IEUM_RELEASE_ORIGIN_CA_CERT:-}"
  DOCKER_BIN="${IEUM_RELEASE_DOCKER_BIN:-}"
  DOCKER_INSPECT_BIN="${IEUM_RELEASE_DOCKER_INSPECT_BIN:-}"
  CURL_BIN="${IEUM_RELEASE_CURL_BIN:-}"
  DB_PREFLIGHT_BIN="${IEUM_RELEASE_DB_PREFLIGHT_BIN:-}"
  STAGE_NGINX_BIN="${IEUM_RELEASE_STAGE_NGINX_BIN:-}"
  PRODUCTION_NGINX_BIN="${IEUM_RELEASE_PRODUCTION_NGINX_BIN:-}"
  MINIO_PRESIGN_SMOKE_BIN="${IEUM_RELEASE_MINIO_PRESIGN_SMOKE_BIN:-}"
  HEALTH_ATTEMPTS="${IEUM_RELEASE_HEALTH_ATTEMPTS:-}"
  is_absolute "$RELEASE_ROOT" || die "test release root must be absolute"
  is_absolute "$ALLOWED_SIGNERS" || die "test allowed-signers path must be absolute"
  for path in "$STATE_ROOT" "$APP_MAIN_ENV" "$APP_AI_ENV" "$DOCKER_REGISTRY_ENV" "$PGSERVICEFILE" "$PGPASSFILE" "$WRITE_FENCE_PATH" "$PUBLIC_WRITE_COMMITTED_PATH" "$ORIGIN_CA_CERT" "$DOCKER_BIN" "$DOCKER_INSPECT_BIN" "$CURL_BIN" "$DB_PREFLIGHT_BIN" "$STAGE_NGINX_BIN" "$PRODUCTION_NGINX_BIN" "$MINIO_PRESIGN_SMOKE_BIN"; do
    is_absolute "$path" || die "test runtime path must be absolute"
  done
  [[ "$HEALTH_ATTEMPTS" =~ ^[1-9][0-9]*$ ]] || die "test health attempts must be a positive integer"
fi

STAGING_DIR="$RELEASE_ROOT/staging"
RELEASES_DIR="$RELEASE_ROOT/releases"
CURRENT_LINK="$RELEASE_ROOT/current"
DEPLOYMENTS_DIR="$STATE_ROOT/deployments"
LOCK_DIR="$STATE_ROOT/locks"
LOCK_PATH="$LOCK_DIR/deploy.lock"
DOCKER_AUTH_DIR=''

require_control_plane() {
  private_dir "$STAGING_DIR" || die "staging directory must be a private non-symlink directory"
  private_dir "$RELEASES_DIR" || die "releases directory must be a private non-symlink directory"
  private_dir "$DEPLOYMENTS_DIR" || die "deployment journal directory must be a private non-symlink directory"
  private_dir "$LOCK_DIR" || die "lock directory must be a private non-symlink directory"
  private_file "$ALLOWED_SIGNERS" || die "release signing allowed-signers file must be a private regular file"
}

acquire_lock() {
  if [[ -e "$LOCK_PATH" || -L "$LOCK_PATH" ]]; then
    private_file "$LOCK_PATH" || die "deployment lock must be a private regular file"
  else
    ( set -o noclobber; : > "$LOCK_PATH" ) 2>/dev/null || die "unable to create deployment lock"
    chmod 600 "$LOCK_PATH" || die "unable to secure deployment lock"
    private_file "$LOCK_PATH" || die "deployment lock must be a private regular file"
  fi
  exec 9>>"$LOCK_PATH" || die "unable to open deployment lock"
  command -v flock >/dev/null 2>&1 || die "flock is required"
  flock -n 9 || die "deployment lock is unavailable"
}

valid_release_id() { [[ "$1" =~ ^r-[0-9]+-[1-9][0-9]*-[0-9a-f]{40}$ ]]; }
valid_sha256() { [[ "$1" =~ ^[0-9a-f]{64}$ ]]; }
valid_git_sha() { [[ "$1" =~ ^[0-9a-f]{40}$ ]]; }
valid_boolean() { [[ "$1" == true || "$1" == false ]]; }
release_state_value() {
  local state=$1 wanted=$2
  awk -F= -v wanted="$wanted" '$1 == wanted { value=substr($0, index($0, "=") + 1); found=1 } END { if (found) printf "%s", value }' "$state"
}
current_state_file() {
  local target
  [[ ! -e "$CURRENT_LINK" && ! -L "$CURRENT_LINK" ]] && return 3
  [[ -L "$CURRENT_LINK" ]] || die "current release path must be a symlink"
  target="$(readlink "$CURRENT_LINK")" || die "current release link is unreadable"
  [[ "$target" == "$RELEASES_DIR/"* && -d "$target" && ! -L "$target" ]] || die "current release link is unsafe"
  printf '%s' "$target/state.env"
}

validate_release_state() {
  local state=$1 release_id bundle_sha backend_sha frontend_sha migration_sha main_image ai_image main_rebuilt ai_rebuilt previous_release previous_bundle
  private_file "$state" || die "current state file is unsafe"
  awk '
    !/^(RELEASE_ID|BUNDLE_SHA256|BACKEND_SHA|FRONTEND_SHA|MIGRATION_SHA256|APP_MAIN_IMAGE_DIGEST|APP_AI_IMAGE_DIGEST|APP_MAIN_REBUILT|APP_AI_REBUILT|PREVIOUS_RELEASE_ID|PREVIOUS_BUNDLE_SHA256)=.*$/ { exit 1 }
    {
      key = substr($0, 1, index($0, "=") - 1)
      if (++seen[key] != 1) exit 1
    }
    END {
      if (seen["RELEASE_ID"] != 1 || seen["BUNDLE_SHA256"] != 1 || seen["BACKEND_SHA"] != 1 || seen["FRONTEND_SHA"] != 1 || seen["MIGRATION_SHA256"] != 1 || seen["APP_MAIN_IMAGE_DIGEST"] != 1 || seen["APP_AI_IMAGE_DIGEST"] != 1 || seen["APP_MAIN_REBUILT"] != 1 || seen["APP_AI_REBUILT"] != 1 || seen["PREVIOUS_RELEASE_ID"] != 1 || seen["PREVIOUS_BUNDLE_SHA256"] != 1) exit 1
    }
  ' "$state" || die "current state has an invalid key set"
  release_id="$(release_state_value "$state" RELEASE_ID)"
  bundle_sha="$(release_state_value "$state" BUNDLE_SHA256)"
  backend_sha="$(release_state_value "$state" BACKEND_SHA)"
  frontend_sha="$(release_state_value "$state" FRONTEND_SHA)"
  migration_sha="$(release_state_value "$state" MIGRATION_SHA256)"
  main_image="$(release_state_value "$state" APP_MAIN_IMAGE_DIGEST)"
  ai_image="$(release_state_value "$state" APP_AI_IMAGE_DIGEST)"
  main_rebuilt="$(release_state_value "$state" APP_MAIN_REBUILT)"
  ai_rebuilt="$(release_state_value "$state" APP_AI_REBUILT)"
  previous_release="$(release_state_value "$state" PREVIOUS_RELEASE_ID)"
  previous_bundle="$(release_state_value "$state" PREVIOUS_BUNDLE_SHA256)"
  valid_release_id "$release_id" || die "current state has an invalid release id"
  valid_sha256 "$bundle_sha" || die "current state has an invalid bundle checksum"
  valid_git_sha "$backend_sha" && [[ "$backend_sha" == "${release_id##*-}" ]] || die "current state has an invalid backend source"
  valid_git_sha "$frontend_sha" || die "current state has an invalid frontend source"
  valid_sha256 "$migration_sha" || die "current state has an invalid migration checksum"
  valid_image_digest "$main_image" "$ALLOWED_MAIN_REPOSITORY" || die "current state has an invalid app-main image"
  valid_image_digest "$ai_image" "$ALLOWED_AI_REPOSITORY" || die "current state has an invalid app-ai image"
  valid_boolean "$main_rebuilt" || die "current state has an invalid app-main rebuild flag"
  valid_boolean "$ai_rebuilt" || die "current state has an invalid app-ai rebuild flag"
  if [[ -z "$previous_release" && -z "$previous_bundle" ]]; then
    :
  elif valid_release_id "$previous_release" && valid_sha256 "$previous_bundle"; then
    :
  else
    die "current state has invalid previous-release metadata"
  fi
  [[ "$state" == "$RELEASES_DIR/$release_id/state.env" ]] || die "current state does not match its release target"
}

journal_dir_for() { printf '%s/%s' "$DEPLOYMENTS_DIR" "$1"; }
journal_file_for() { printf '%s/activation.env' "$(journal_dir_for "$1")"; }
valid_activation_phase() {
  case "$1" in
    INSTALLED|MIGRATION_STARTED|MIGRATION_SUCCEEDED|SERVICES_HEALTHY|NGINX_STAGED|COMMIT_PENDING|ACTIVE|FAILED_PRE_MIGRATION_ROLLED_BACK|MANUAL_INTERVENTION) return 0 ;;
    *) return 1 ;;
  esac
}

validate_activation_journal() {
  local release=$1 file expected_phase migration_started
  file="$(journal_file_for "$release")"
  private_dir "$(journal_dir_for "$release")" || die "activation journal directory is unsafe"
  private_file "$file" || die "activation journal is unsafe"
  awk '
    !/^(RELEASE_ID|PHASE|MIGRATION_STARTED)=.*$/ { exit 1 }
    { key = substr($0, 1, index($0, "=") - 1); if (++seen[key] != 1) exit 1 }
    END { exit(seen["RELEASE_ID"] == 1 && seen["PHASE"] == 1 && seen["MIGRATION_STARTED"] == 1 ? 0 : 1) }
  ' "$file" || die "activation journal has an invalid key set"
  [[ "$(release_state_value "$file" RELEASE_ID)" == "$release" ]] || die "activation journal release id does not match"
  expected_phase="$(release_state_value "$file" PHASE)"
  valid_activation_phase "$expected_phase" || die "activation journal has an invalid phase"
  migration_started="$(release_state_value "$file" MIGRATION_STARTED)"
  valid_boolean "$migration_started" || die "activation journal has an invalid migration marker"
  printf '%s' "$expected_phase"
}

activation_journal_migration_started() {
  local release=$1 file
  validate_activation_journal "$release" >/dev/null
  file="$(journal_file_for "$release")"
  release_state_value "$file" MIGRATION_STARTED
}

write_activation_journal() {
  local release=$1 phase=$2 journal_dir journal_file tmp migration_started=false
  valid_release_id "$release" || die "activation journal release id is invalid"
  valid_activation_phase "$phase" || die "activation journal phase is invalid"
  journal_dir="$(journal_dir_for "$release")"
  journal_file="$journal_dir/activation.env"
  if [[ -e "$journal_dir" || -L "$journal_dir" ]]; then
    private_dir "$journal_dir" || die "activation journal directory is unsafe"
    if [[ -e "$journal_file" || -L "$journal_file" ]]; then
      migration_started="$(activation_journal_migration_started "$release")"
    fi
  else
    mkdir "$journal_dir" || die "unable to create activation journal directory"
    chmod 700 "$journal_dir" || die "unable to secure activation journal directory"
    private_dir "$journal_dir" || die "activation journal directory is unsafe"
  fi
  [[ "$phase" == MIGRATION_STARTED ]] && migration_started=true
  tmp="$(mktemp "$journal_dir/.activation.XXXXXX")" || die "unable to create activation journal"
  printf 'RELEASE_ID=%s\nPHASE=%s\nMIGRATION_STARTED=%s\n' "$release" "$phase" "$migration_started" > "$tmp"
  chmod 600 "$tmp" || die "unable to secure activation journal"
  private_file "$tmp" || die "activation journal is unsafe"
  mv -Tf "$tmp" "$journal_file" || die "unable to update activation journal"
  private_file "$journal_file" || die "activation journal is unsafe"
}

validate_current_state() {
  local state=$1 release phase
  validate_release_state "$state"
  release="$(release_state_value "$state" RELEASE_ID)"
  phase="$(validate_activation_journal "$release")"
  [[ "$phase" == ACTIVE ]] || die "current release is not marked active"
}

current_release_id() {
  local state release_id
  state="$(current_state_file)" || return $?
  validate_current_state "$state"
  release_id="$(release_state_value "$state" RELEASE_ID)"
  printf '%s' "$release_id"
}

print_current_json() {
  local state release_id bundle_sha backend_sha frontend_sha migration_sha main_image ai_image main_rebuilt ai_rebuilt previous_release previous_bundle status previous_json
  if state="$(current_state_file)"; then
    :
  else
    status=$?
    [[ "$status" == 3 ]] || return "$status"
    printf '%s\n' '{"release_id":null,"bundle_sha256":null,"backend_sha":null,"frontend_sha":null,"migration_sha256":null,"images":null,"rebuild":null,"previous_release":null}'
    return
  fi
  validate_current_state "$state"
  release_id="$(release_state_value "$state" RELEASE_ID)"
  bundle_sha="$(release_state_value "$state" BUNDLE_SHA256)"
  backend_sha="$(release_state_value "$state" BACKEND_SHA)"
  frontend_sha="$(release_state_value "$state" FRONTEND_SHA)"
  migration_sha="$(release_state_value "$state" MIGRATION_SHA256)"
  main_image="$(release_state_value "$state" APP_MAIN_IMAGE_DIGEST)"
  ai_image="$(release_state_value "$state" APP_AI_IMAGE_DIGEST)"
  main_rebuilt="$(release_state_value "$state" APP_MAIN_REBUILT)"
  ai_rebuilt="$(release_state_value "$state" APP_AI_REBUILT)"
  previous_release="$(release_state_value "$state" PREVIOUS_RELEASE_ID)"
  previous_bundle="$(release_state_value "$state" PREVIOUS_BUNDLE_SHA256)"
  if [[ -z "$previous_release" ]]; then
    previous_json=null
  else
    previous_json="{\"release_id\":\"$previous_release\",\"bundle_sha256\":\"$previous_bundle\"}"
  fi
  printf '{"release_id":"%s","bundle_sha256":"%s","backend_sha":"%s","frontend_sha":"%s","migration_sha256":"%s","images":{"app_main":"%s","app_ai":"%s"},"rebuild":{"app_main":%s,"app_ai":%s},"previous_release":%s}\n' \
    "$release_id" "$bundle_sha" "$backend_sha" "$frontend_sha" "$migration_sha" "$main_image" "$ai_image" "$main_rebuilt" "$ai_rebuilt" "$previous_json"
}

verify_exact_tar_members() {
  local archive=$1 expected=$2 listed verbose mode member
  listed="$(tar -tf "$archive")" || return 1
  [[ "$listed" == "$expected" ]] || return 1
  verbose="$(tar -tvf "$archive")" || return 1
  awk 'NF && substr($0, 1, 1) != "-" { exit 1 }' <<< "$verbose"
  while IFS= read -r line; do
    mode="${line%%[[:space:]]*}"
    member="${line##* }"
    [[ "$mode" == '-rw-------' && ( "$member" == release.tar || "$member" == release.tar.sig ) ]] || return 1
  done <<< "$verbose"
}

verify_payload_members() {
  local archive=$1 member listed verbose duplicate_members mode
  local manifest=0 release_env=0 checksums=0 compose=0 app_nginx=0 files_nginx=0 staging_nginx=0 validator=0 migration_helper=0 migration_count=0
  listed="$(tar -tf "$archive")" || return 1
  duplicate_members="$(printf '%s\n' "$listed" | LC_ALL=C sort | uniq -d)"
  [[ -z "$duplicate_members" ]] || return 1
  while IFS= read -r member; do
    case "$member" in
      manifest.json) manifest=$((manifest + 1)) ;;
      release.env) release_env=$((release_env + 1)) ;;
      checksums.sha256) checksums=$((checksums + 1)) ;;
      deploy/onprem/compose.yml) compose=$((compose + 1)) ;;
      deploy/onprem/nginx/ieum.rktclgh.site.conf) app_nginx=$((app_nginx + 1)) ;;
      deploy/onprem/nginx/files.rktclgh.site.conf) files_nginx=$((files_nginx + 1)) ;;
      deploy/onprem/nginx/ieum1.rktclgh.site.conf) staging_nginx=$((staging_nginx + 1)) ;;
      deploy/onprem/scripts/validate-runtime-env.sh) validator=$((validator + 1)) ;;
      deploy/scripts/apply-admin-dashboard-migrations.sh) migration_helper=$((migration_helper + 1)) ;;
      db/migrations/[A-Za-z0-9_.-]*.sql)
        [[ "$member" =~ ^db/migrations/[A-Za-z0-9_.-]+\.sql$ ]] || return 1
        migration_count=$((migration_count + 1))
        ;;
      *) return 1 ;;
    esac
  done <<< "$listed"
  [[ "$manifest" == 1 && "$release_env" == 1 && "$checksums" == 1 && "$compose" == 1 && "$app_nginx" == 1 && "$files_nginx" == 1 && "$staging_nginx" == 1 && "$validator" == 1 && "$migration_helper" == 1 && "$migration_count" -gt 0 ]] || return 1
  verbose="$(tar -tvf "$archive")" || return 1
  awk 'NF && substr($0, 1, 1) != "-" { exit 1 }' <<< "$verbose"
  while IFS= read -r line; do
    mode="${line%%[[:space:]]*}"
    member="${line##* }"
    case "$member" in
      deploy/onprem/scripts/validate-runtime-env.sh|deploy/scripts/apply-admin-dashboard-migrations.sh)
        [[ "$mode" == '-rwx------' ]] || return 1
        ;;
      *)
        [[ "$mode" == '-rw-------' ]] || return 1
        ;;
    esac
  done <<< "$verbose"
}

verify_payload_checksum_set() {
  local payload_dir=$1 archive=$2 member_list checksum_list
  member_list="$(tar -tf "$archive" | awk '$0 != "checksums.sha256" { print }' | LC_ALL=C sort)" || return 1
  checksum_list="$(awk 'NF == 2 && $1 ~ /^[0-9a-f]{64}$/ && $2 !~ /^\// && $2 !~ /\.\./ { if (seen[$2]++) exit 1; print $2; next } { exit 1 }' "$payload_dir/checksums.sha256" | LC_ALL=C sort)" || return 1
  [[ "$member_list" == "$checksum_list" ]]
}

valid_image_digest() {
  local image=$1 repository=$2 digest
  case "$image" in
    "$repository"@sha256:*)
      digest="${image#"$repository"@sha256:}"
      valid_sha256 "$digest"
      ;;
    *) return 1 ;;
  esac
}

parse_release_env() {
  local file=$1 main ai count
  private_file "$file" || die "release env file is unsafe"
  awk -F= '
    /^[[:space:]]*(#|$)/ { next }
    !/^(APP_MAIN_IMAGE_DIGEST|APP_AI_IMAGE_DIGEST)=/ { exit 1 }
    { if (++seen[$1] != 1) exit 1; count++ }
    END { exit(count == 2 && seen["APP_MAIN_IMAGE_DIGEST"] == 1 && seen["APP_AI_IMAGE_DIGEST"] == 1 ? 0 : 1) }
  ' "$file" || die "release env has an invalid key set"
  main="$(release_state_value "$file" APP_MAIN_IMAGE_DIGEST)"
  ai="$(release_state_value "$file" APP_AI_IMAGE_DIGEST)"
  valid_image_digest "$main" "$ALLOWED_MAIN_REPOSITORY" || die "release env has an invalid app-main image"
  valid_image_digest "$ai" "$ALLOWED_AI_REPOSITORY" || die "release env has an invalid app-ai image"
  APP_MAIN_IMAGE_DIGEST="$main"
  APP_AI_IMAGE_DIGEST="$ai"
}

verify_migration_member_set() {
  local payload_dir=$1 expected actual duplicate_members
  awk '
    /^[ \t]*\\i(r)?([ \t]|$)/ && $0 !~ /^[ \t]*\\i[ \t]+db\/migrations\/[A-Za-z0-9_.-]+\.sql[ \t]*$/ { exit 1 }
  ' "$payload_dir/deploy/scripts/apply-admin-dashboard-migrations.sh" || return 1
  expected="$(awk '
    /^[ \t]*\\i[ \t]+db\/migrations\/[A-Za-z0-9_.-]+\.sql[ \t]*$/ {
      value = $0
      sub(/^[ \t]*\\i[ \t]+/, "", value)
      sub(/[ \t]*$/, "", value)
      print value
    }
  ' "$payload_dir/deploy/scripts/apply-admin-dashboard-migrations.sh" | LC_ALL=C sort)" || return 1
  [[ -n "$expected" ]] || return 1
  duplicate_members="$(printf '%s\n' "$expected" | uniq -d)"
  [[ -z "$duplicate_members" ]] || return 1
  actual="$(
    cd "$payload_dir"
    find db/migrations -maxdepth 1 -type f -name '*.sql' -print | LC_ALL=C sort
  )" || return 1
  [[ "$actual" == "$expected" ]]
}

calculate_migration_digest() {
  local payload_dir=$1 digest
  digest="$(
    cd "$payload_dir"
    {
      sha256sum deploy/scripts/apply-admin-dashboard-migrations.sh
      while IFS= read -r migration; do
        sha256sum "$migration"
      done < <(find db/migrations -maxdepth 1 -type f -name '*.sql' -print | LC_ALL=C sort)
    } | sha256sum | awk '{print $1}'
  )" || return 1
  valid_sha256 "$digest" || return 1
  printf '%s' "$digest"
}

parse_manifest() {
  local file=$1 parsed field
  local -a fields
  private_file "$file" || die "release manifest is unsafe"
  command -v python3 >/dev/null 2>&1 || die "python3 is required to parse release manifest"
  parsed="$(python3 - "$file" "$release_id" <<'PY'
import json
import re
import sys

path = sys.argv[1]
expected_release_id = sys.argv[2]
try:
    raw = open(path, 'r', encoding='utf-8').read()
    value = json.loads(raw)
except (OSError, UnicodeError, json.JSONDecodeError):
    sys.exit(2)

required = {
    'backend_sha', 'frontend_sha', 'github_run_attempt', 'github_run_id',
    'images', 'migration_sha256', 'previous_release', 'rebuild',
    'release_id', 'schema',
}
if not isinstance(value, dict) or set(value) != required:
    sys.exit(2)
if json.dumps(value, ensure_ascii=True, separators=(',', ':'), sort_keys=True) + '\n' != raw:
    sys.exit(2)
if value['schema'] != 'ieum-release/v1':
    sys.exit(2)
if value['release_id'] != expected_release_id:
    sys.exit(2)
release_match = re.fullmatch(r'r-([0-9]+)-([1-9][0-9]*)-([0-9a-f]{40})', value['release_id'])
if release_match is None:
    sys.exit(2)
if not isinstance(value['github_run_id'], int) or isinstance(value['github_run_id'], bool) or value['github_run_id'] <= 0:
    sys.exit(2)
if not isinstance(value['github_run_attempt'], int) or isinstance(value['github_run_attempt'], bool) or value['github_run_attempt'] <= 0:
    sys.exit(2)
if str(value['github_run_id']) != release_match.group(1) or str(value['github_run_attempt']) != release_match.group(2):
    sys.exit(2)
if value['backend_sha'] != release_match.group(3) or not re.fullmatch(r'[0-9a-f]{40}', value['backend_sha']):
    sys.exit(2)
if not isinstance(value['frontend_sha'], str) or not re.fullmatch(r'[0-9a-f]{40}', value['frontend_sha']):
    sys.exit(2)
if not isinstance(value['migration_sha256'], str) or not re.fullmatch(r'[0-9a-f]{64}', value['migration_sha256']):
    sys.exit(2)
if not isinstance(value['images'], dict) or set(value['images']) != {'app_main', 'app_ai'}:
    sys.exit(2)
if not all(isinstance(value['images'][key], str) for key in ('app_main', 'app_ai')):
    sys.exit(2)
if not isinstance(value['rebuild'], dict) or set(value['rebuild']) != {'app_main', 'app_ai'}:
    sys.exit(2)
if not all(isinstance(value['rebuild'][key], bool) for key in ('app_main', 'app_ai')):
    sys.exit(2)
previous = value['previous_release']
if previous is None:
    previous_id = 'none'
    previous_bundle = 'none'
elif isinstance(previous, dict) and set(previous) == {'release_id', 'bundle_sha256'} and isinstance(previous['release_id'], str) and isinstance(previous['bundle_sha256'], str) and re.fullmatch(r'r-[0-9]+-[1-9][0-9]*-[0-9a-f]{40}', previous['release_id']) and re.fullmatch(r'[0-9a-f]{64}', previous['bundle_sha256']):
    previous_id = previous['release_id']
    previous_bundle = previous['bundle_sha256']
else:
    sys.exit(2)

print(value['backend_sha'])
print(value['frontend_sha'])
print(value['migration_sha256'])
print(value['images']['app_main'])
print(value['images']['app_ai'])
print('true' if value['rebuild']['app_main'] else 'false')
print('true' if value['rebuild']['app_ai'] else 'false')
print(previous_id)
print(previous_bundle)
PY
)" || die "release manifest has an invalid schema or encoding"
  fields=()
  while IFS= read -r field; do
    fields[${#fields[@]}]="$field"
  done <<< "$parsed"
  [[ "${#fields[@]}" == 9 ]] || die "release manifest has an invalid field count"
  MANIFEST_BACKEND_SHA="${fields[0]}"
  MANIFEST_FRONTEND_SHA="${fields[1]}"
  MANIFEST_MIGRATION_SHA256="${fields[2]}"
  MANIFEST_APP_MAIN_REBUILT="${fields[5]}"
  MANIFEST_APP_AI_REBUILT="${fields[6]}"
  MANIFEST_PREVIOUS_RELEASE_ID="${fields[7]}"
  MANIFEST_PREVIOUS_BUNDLE_SHA256="${fields[8]}"
  [[ "$MANIFEST_BACKEND_SHA" == "${release_id##*-}" ]] || die "release manifest backend source does not match release id"
  valid_git_sha "$MANIFEST_FRONTEND_SHA" || die "release manifest has an invalid frontend source"
  valid_sha256 "$MANIFEST_MIGRATION_SHA256" || die "release manifest has an invalid migration checksum"
  valid_image_digest "${fields[3]}" "$ALLOWED_MAIN_REPOSITORY" || die "release manifest has an invalid app-main image"
  valid_image_digest "${fields[4]}" "$ALLOWED_AI_REPOSITORY" || die "release manifest has an invalid app-ai image"
  [[ "${fields[3]}" == "$APP_MAIN_IMAGE_DIGEST" ]] || die "release manifest app-main image does not match release env"
  [[ "${fields[4]}" == "$APP_AI_IMAGE_DIGEST" ]] || die "release manifest app-ai image does not match release env"
  valid_boolean "$MANIFEST_APP_MAIN_REBUILT" || die "release manifest has an invalid app-main rebuild flag"
  valid_boolean "$MANIFEST_APP_AI_REBUILT" || die "release manifest has an invalid app-ai rebuild flag"
  if [[ "$MANIFEST_PREVIOUS_RELEASE_ID" == none && "$MANIFEST_PREVIOUS_BUNDLE_SHA256" == none ]]; then
    MANIFEST_PREVIOUS_RELEASE_ID=''
    MANIFEST_PREVIOUS_BUNDLE_SHA256=''
  elif valid_release_id "$MANIFEST_PREVIOUS_RELEASE_ID" && valid_sha256 "$MANIFEST_PREVIOUS_BUNDLE_SHA256"; then
    :
  else
    die "release manifest has invalid previous-release metadata"
  fi
}

validate_manifest_previous_release() {
  local current_id=$1 current_bundle=$2
  if [[ -z "$current_id" ]]; then
    [[ -z "$MANIFEST_PREVIOUS_RELEASE_ID" && -z "$MANIFEST_PREVIOUS_BUNDLE_SHA256" ]] || die "release manifest previous release does not match an empty current state"
  else
    [[ "$MANIFEST_PREVIOUS_RELEASE_ID" == "$current_id" && "$MANIFEST_PREVIOUS_BUNDLE_SHA256" == "$current_bundle" ]] || die "release manifest previous release does not match current state"
  fi
}

release_state_matches_payload() {
  local state=$1
  validate_release_state "$state"
  [[ "$(release_state_value "$state" RELEASE_ID)" == "$release_id" ]] || return 1
  [[ "$(release_state_value "$state" BUNDLE_SHA256)" == "$bundle_sha256" ]] || return 1
  [[ "$(release_state_value "$state" BACKEND_SHA)" == "$MANIFEST_BACKEND_SHA" ]] || return 1
  [[ "$(release_state_value "$state" FRONTEND_SHA)" == "$MANIFEST_FRONTEND_SHA" ]] || return 1
  [[ "$(release_state_value "$state" MIGRATION_SHA256)" == "$MANIFEST_MIGRATION_SHA256" ]] || return 1
  [[ "$(release_state_value "$state" APP_MAIN_IMAGE_DIGEST)" == "$APP_MAIN_IMAGE_DIGEST" ]] || return 1
  [[ "$(release_state_value "$state" APP_AI_IMAGE_DIGEST)" == "$APP_AI_IMAGE_DIGEST" ]] || return 1
  [[ "$(release_state_value "$state" APP_MAIN_REBUILT)" == "$MANIFEST_APP_MAIN_REBUILT" ]] || return 1
  [[ "$(release_state_value "$state" APP_AI_REBUILT)" == "$MANIFEST_APP_AI_REBUILT" ]] || return 1
  [[ "$(release_state_value "$state" PREVIOUS_RELEASE_ID)" == "$MANIFEST_PREVIOUS_RELEASE_ID" ]] || return 1
  [[ "$(release_state_value "$state" PREVIOUS_BUNDLE_SHA256)" == "$MANIFEST_PREVIOUS_BUNDLE_SHA256" ]]
}

promoted_payload_members() {
  local target=$1
  {
    printf '%s\n' \
      manifest.json \
      release.env \
      checksums.sha256 \
      deploy/onprem/compose.yml \
      deploy/onprem/nginx/ieum.rktclgh.site.conf \
      deploy/onprem/nginx/files.rktclgh.site.conf \
      deploy/onprem/nginx/ieum1.rktclgh.site.conf \
      deploy/onprem/scripts/validate-runtime-env.sh \
      deploy/scripts/apply-admin-dashboard-migrations.sh
    (
      cd "$target"
      find db/migrations -maxdepth 1 -type f -name '*.sql' -print
    )
  } | LC_ALL=C sort
}

integrity_fail() {
  die "release integrity check failed: $1"
}

verify_promoted_release_integrity() {
  local target=$1 state="$target/state.env" bundle_file="$target/bundle.sha256"
  local payload_members checksum_expected checksum_members actual_members expected_members actual_bundle
  local file directory
  private_dir "$target" || integrity_fail "target directory is unsafe"
  while IFS= read -r directory; do
    private_dir "$directory" || integrity_fail "payload directory is unsafe"
  done < <(find "$target" -type d -print)
  verify_migration_member_set "$target" || integrity_fail "migration member set is unsafe"
  payload_members="$(promoted_payload_members "$target")" || integrity_fail "payload member inventory failed"
  checksum_expected="$(printf '%s\n' "$payload_members" | awk '$0 != "checksums.sha256"')"
  checksum_members="$(awk 'NF == 2 && $1 ~ /^[0-9a-f]{64}$/ && $2 !~ /^\// && $2 !~ /\.\./ { if (seen[$2]++) exit 1; print $2; next } { exit 1 }' "$target/checksums.sha256" | LC_ALL=C sort)" || integrity_fail "checksum manifest syntax is unsafe"
  [[ "$checksum_members" == "$checksum_expected" ]] || integrity_fail "checksum member set differs"
  actual_members="$(
    cd "$target"
    find . -type f -print | sed 's#^\./##' | LC_ALL=C sort
  )" || integrity_fail "payload file inventory failed"
  expected_members="$(printf '%s\n%s\n%s\n' "$payload_members" state.env bundle.sha256 | LC_ALL=C sort)"
  [[ "$actual_members" == "$expected_members" ]] || integrity_fail "payload file set differs"
  for file in manifest.json release.env checksums.sha256 deploy/onprem/compose.yml \
    deploy/onprem/nginx/ieum.rktclgh.site.conf \
    deploy/onprem/nginx/files.rktclgh.site.conf \
    deploy/onprem/nginx/ieum1.rktclgh.site.conf \
    state.env bundle.sha256; do
    private_file "$target/$file" || integrity_fail "payload regular file is unsafe: $file"
  done
  while IFS= read -r file; do
    private_file "$target/$file" || integrity_fail "payload migration is unsafe: $file"
  done < <(
    cd "$target"
    find db/migrations -maxdepth 1 -type f -name '*.sql' -print | LC_ALL=C sort
  )
  for file in deploy/onprem/scripts/validate-runtime-env.sh deploy/scripts/apply-admin-dashboard-migrations.sh; do
    [[ -f "$target/$file" && ! -L "$target/$file" && "$(mode_of "$target/$file")" == 700 && "$(owner_of "$target/$file")" == "$EUID" ]] || integrity_fail "payload helper is unsafe: $file"
  done
  ( cd "$target" && sha256sum --check checksums.sha256 >/dev/null ) || integrity_fail "payload checksum verification failed"
  actual_bundle="$(awk 'NR == 1 && $0 ~ /^[0-9a-f]{64}$/ { value = $0; valid = 1; next } { valid = 0; exit } END { if (valid && NR == 1) print value; else exit 1 }' "$bundle_file")" || integrity_fail "bundle checksum file is unsafe"
  [[ "$actual_bundle" == "$bundle_sha256" ]] || integrity_fail "bundle checksum differs"
}

write_state() {
  local state=$1
  printf 'RELEASE_ID=%s\nBUNDLE_SHA256=%s\nBACKEND_SHA=%s\nFRONTEND_SHA=%s\nMIGRATION_SHA256=%s\nAPP_MAIN_IMAGE_DIGEST=%s\nAPP_AI_IMAGE_DIGEST=%s\nAPP_MAIN_REBUILT=%s\nAPP_AI_REBUILT=%s\nPREVIOUS_RELEASE_ID=%s\nPREVIOUS_BUNDLE_SHA256=%s\n' \
    "$release_id" "$bundle_sha256" "$MANIFEST_BACKEND_SHA" "$MANIFEST_FRONTEND_SHA" "$MANIFEST_MIGRATION_SHA256" "$APP_MAIN_IMAGE_DIGEST" "$APP_AI_IMAGE_DIGEST" "$MANIFEST_APP_MAIN_REBUILT" "$MANIFEST_APP_AI_REBUILT" "$MANIFEST_PREVIOUS_RELEASE_ID" "$MANIFEST_PREVIOUS_BUNDLE_SHA256" > "$state"
  chmod 600 "$state" || die "unable to secure release state"
  private_file "$state" || die "release state is unsafe"
}

require_runtime_control_files() {
  private_file "$APP_MAIN_ENV" || { printf 'ieum deploy release: app-main runtime env is unsafe\n' >&2; return 1; }
  private_file "$APP_AI_ENV" || { printf 'ieum deploy release: app-ai runtime env is unsafe\n' >&2; return 1; }
  private_file "$PGSERVICEFILE" || { printf 'ieum deploy release: PostgreSQL service file is unsafe\n' >&2; return 1; }
  private_file "$PGPASSFILE" || { printf 'ieum deploy release: PostgreSQL passfile is unsafe\n' >&2; return 1; }
  [[ -f "$ORIGIN_CA_CERT" && ! -L "$ORIGIN_CA_CERT" && "$(owner_of "$ORIGIN_CA_CERT")" == "$EUID" && ("$(mode_of "$ORIGIN_CA_CERT")" == 600 || "$(mode_of "$ORIGIN_CA_CERT")" == 644) ]] || { printf 'ieum deploy release: origin CA certificate is unsafe\n' >&2; return 1; }
  for path in "$DOCKER_BIN" "$DOCKER_INSPECT_BIN" "$CURL_BIN" "$DB_PREFLIGHT_BIN" "$STAGE_NGINX_BIN" "$PRODUCTION_NGINX_BIN" "$MINIO_PRESIGN_SMOKE_BIN"; do
    [[ -f "$path" && -x "$path" && ! -L "$path" ]] || { printf 'ieum deploy release: runtime executable is unsafe\n' >&2; return 1; }
  done
}

require_docker_pull_credentials() {
  local username password auth_dir
  private_file "$DOCKER_REGISTRY_ENV" || { printf 'ieum deploy release: Docker registry credential file is unsafe\n' >&2; return 1; }
  awk -F= '
    BEGIN { valid = 1 }
    !/^[A-Za-z_][A-Za-z0-9_]*=/ { valid = 0; next }
    { key = $1; if (key != "DOCKER_REGISTRY_USERNAME" && key != "DOCKER_REGISTRY_PASSWORD") valid = 0; if (++seen[key] != 1) valid = 0 }
    END { exit(valid && seen["DOCKER_REGISTRY_USERNAME"] == 1 && seen["DOCKER_REGISTRY_PASSWORD"] == 1 ? 0 : 1) }
  ' "$DOCKER_REGISTRY_ENV" || { printf 'ieum deploy release: Docker registry credential file has an invalid key set\n' >&2; return 1; }
  username="$(awk -F= '$1 == "DOCKER_REGISTRY_USERNAME" { print substr($0, index($0, "=") + 1) }' "$DOCKER_REGISTRY_ENV")"
  password="$(awk -F= '$1 == "DOCKER_REGISTRY_PASSWORD" { print substr($0, index($0, "=") + 1) }' "$DOCKER_REGISTRY_ENV")"
  [[ -n "$username" && -n "$password" ]] || { printf 'ieum deploy release: Docker registry credentials must be nonblank\n' >&2; return 1; }
  auth_dir="$(mktemp -d "$STAGING_DIR/.docker-auth.XXXXXX")" || { printf 'ieum deploy release: unable to create Docker credential workspace\n' >&2; return 1; }
  chmod 700 "$auth_dir" || { rm -rf -- "$auth_dir"; printf 'ieum deploy release: unable to secure Docker credential workspace\n' >&2; return 1; }
  private_dir "$auth_dir" || { rm -rf -- "$auth_dir"; printf 'ieum deploy release: Docker credential workspace is unsafe\n' >&2; return 1; }
  if ! printf '%s\n' "$password" | DOCKER_CONFIG="$auth_dir" "$DOCKER_BIN" login docker.io --username "$username" --password-stdin >/dev/null 2>&1; then
    rm -rf -- "$auth_dir" || printf 'ieum deploy release: Docker credential workspace cleanup failed after login failure\n' >&2
    printf 'ieum deploy release: Docker registry login failed\n' >&2
    return 1
  fi
  DOCKER_AUTH_DIR="$auth_dir"
}

clear_docker_pull_credentials() {
  local auth_dir="${DOCKER_AUTH_DIR:-}"
  [[ -n "$auth_dir" ]] || return 0
  [[ "$auth_dir" == "$STAGING_DIR"/.docker-auth.* ]] || { printf 'ieum deploy release: Docker credential workspace path is unsafe\n' >&2; return 1; }
  private_dir "$auth_dir" || { printf 'ieum deploy release: Docker credential workspace is unsafe\n' >&2; return 1; }
  rm -rf -- "$auth_dir" || { printf 'ieum deploy release: unable to remove Docker credential workspace\n' >&2; return 1; }
  [[ ! -e "$auth_dir" && ! -L "$auth_dir" ]] || { printf 'ieum deploy release: Docker credential workspace remains after cleanup\n' >&2; return 1; }
  DOCKER_AUTH_DIR=''
}

pull_private_images() {
  local target=$1 pull_status
  require_docker_pull_credentials || return 1
  if compose "$target" pull app-main app-ai >/dev/null; then
    pull_status=0
  else
    pull_status=$?
  fi
  clear_docker_pull_credentials || return 1
  return "$pull_status"
}

compose() {
  local target=$1
  shift
  if [[ -n "${DOCKER_AUTH_DIR:-}" ]]; then
    private_dir "$DOCKER_AUTH_DIR" || return 1
    DOCKER_CONFIG="$DOCKER_AUTH_DIR" "$DOCKER_BIN" compose --project-name ieum --env-file "$target/release.env" --file "$target/deploy/onprem/compose.yml" "$@"
  else
    "$DOCKER_BIN" compose --project-name ieum --env-file "$target/release.env" --file "$target/deploy/onprem/compose.yml" "$@"
  fi
}

validate_runtime_envs() {
  local target=$1 validator="$target/deploy/onprem/scripts/validate-runtime-env.sh"
  "$validator" app-main "$APP_MAIN_ENV" "$APP_AI_ENV" >/dev/null || return 1
  "$validator" app-ai "$APP_AI_ENV" "$APP_MAIN_ENV" >/dev/null
}

verify_rendered_images() {
  local target=$1 rendered
  compose "$target" config --quiet || return 1
  rendered="$(compose "$target" config --format json)" || return 1
  printf '%s' "$rendered" | python3 -c '
import json
import sys
try:
    value = json.load(sys.stdin)
    services = value["services"]
    assert set(services) >= {"app-main", "app-ai"}
    assert services["app-main"]["image"] == sys.argv[1]
    assert services["app-ai"]["image"] == sys.argv[2]
except (AssertionError, KeyError, TypeError, ValueError, json.JSONDecodeError):
    sys.exit(1)
' "$APP_MAIN_IMAGE_DIGEST" "$APP_AI_IMAGE_DIGEST"
}

verify_pulled_images() {
  "$DOCKER_BIN" image inspect "$APP_MAIN_IMAGE_DIGEST" >/dev/null || return 1
  "$DOCKER_BIN" image inspect "$APP_AI_IMAGE_DIGEST" >/dev/null
}

wait_for_health() {
  local url=$1 attempt body
  for ((attempt = 1; attempt <= HEALTH_ATTEMPTS; attempt++)); do
    if body="$("$CURL_BIN" --fail --silent --show-error --max-time 5 "$url" 2>/dev/null)" && printf '%s' "$body" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; then
      return 0
    fi
    [[ "$attempt" -lt "$HEALTH_ATTEMPTS" ]] && sleep 2
  done
  return 1
}

verify_running_service_image() {
  local target=$1 service=$2 expected=$3 container image
  container="$(compose "$target" ps -q "$service")" || return 1
  [[ "$container" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || return 1
  image="$("$DOCKER_INSPECT_BIN" inspect --format '{{.Config.Image}}' "$container")" || return 1
  [[ "$image" == "$expected" ]]
}

require_write_fence() {
  private_file "$WRITE_FENCE_PATH" || { printf 'ieum deploy release: production write fence is required\n' >&2; return 1; }
  grep -Fqx 'IEUM_PRODUCTION_WRITE_FENCE=enabled' "$WRITE_FENCE_PATH" || { printf 'ieum deploy release: production write fence is invalid\n' >&2; return 1; }
}

require_public_write_uncommitted() {
  if [[ -e "$PUBLIC_WRITE_COMMITTED_PATH" || -L "$PUBLIC_WRITE_COMMITTED_PATH" ]]; then
    private_file "$PUBLIC_WRITE_COMMITTED_PATH" || die "public-write commit marker is unsafe"
    die "rollback is blocked after public writes have committed"
  fi
}

stage_origin_smoke() {
  "$CURL_BIN" --fail --silent --show-error --max-time 15 \
    --cacert "$ORIGIN_CA_CERT" \
    --resolve ieum1.rktclgh.site:443:127.0.0.1 \
    https://ieum1.rktclgh.site/api/places/search >/dev/null
}

production_ingress_gate() {
  local release=$1 pending=${2:-false}
  if [[ "$pending" == true ]]; then
    "$PRODUCTION_NGINX_BIN" --release-id "$release" --confirm-public-ingress --allow-pending-activation >/dev/null || return 1
  else
    "$PRODUCTION_NGINX_BIN" --release-id "$release" --confirm-public-ingress >/dev/null || return 1
  fi
  "$MINIO_PRESIGN_SMOKE_BIN" >/dev/null || return 1
}

rollback_pre_migration_runtime() {
  local candidate=$1 previous=${2:-}
  if [[ -z "$previous" ]]; then
    if ! compose "$candidate" down --remove-orphans >/dev/null 2>&1; then
      write_activation_journal "$release_id" MANUAL_INTERVENTION || true
      printf 'ieum deploy release: candidate runtime cleanup failed; manual intervention is required\n' >&2
      return 1
    fi
    "$STAGE_NGINX_BIN" --remove >/dev/null || return 1
    return 0
  fi
  if [[ "${ACTIVATION_CANDIDATE_STARTED:-false}" == true ]]; then
    compose "$candidate" down --remove-orphans >/dev/null || return 1
  fi
  verify_promoted_release_integrity "$previous" || return 1
  validate_current_state "$previous/state.env" || return 1
  compose "$previous" up -d --remove-orphans app-ai >/dev/null || return 1
  wait_for_health http://127.0.0.1:18084/actuator/health || return 1
  compose "$previous" up -d --remove-orphans app-main >/dev/null || return 1
  wait_for_health http://127.0.0.1:18080/actuator/health || return 1
  verify_running_service_image "$previous" app-ai "$(release_state_value "$previous/state.env" APP_AI_IMAGE_DIGEST)" || return 1
  verify_running_service_image "$previous" app-main "$(release_state_value "$previous/state.env" APP_MAIN_IMAGE_DIGEST)" || return 1
  "$STAGE_NGINX_BIN" --release-id "$(release_state_value "$previous/state.env" RELEASE_ID)" >/dev/null || return 1
  stage_origin_smoke
}

ACTIVATION_MIGRATION_STARTED=false
ACTIVATION_CANDIDATE_STARTED=false
activate_candidate_runtime() {
  local target=$1 migration_required=$2 preserve_pending=${3:-false}
  require_runtime_control_files || return 1
  validate_runtime_envs "$target" || return 1
  "$DOCKER_BIN" network inspect ieum >/dev/null || return 1
  "$DOCKER_BIN" network inspect ieum-minio >/dev/null || return 1
  verify_rendered_images "$target" || return 1
  pull_private_images "$target" || return 1
  verify_pulled_images || return 1
  PGSERVICEFILE="$PGSERVICEFILE" PGPASSFILE="$PGPASSFILE" "$DB_PREFLIGHT_BIN" --kind production --admin-service ieum_target_admin >/dev/null || return 1
  if [[ "$migration_required" == true ]]; then
    require_write_fence || return 1
    compose "$target" down --remove-orphans >/dev/null || return 1
    write_activation_journal "$release_id" MIGRATION_STARTED || return 1
    ACTIVATION_MIGRATION_STARTED=true
    MIGRATION_RUNTIME_ENV="$APP_MAIN_ENV" "$target/deploy/scripts/apply-admin-dashboard-migrations.sh" >/dev/null || return 1
    write_activation_journal "$release_id" MIGRATION_SUCCEEDED || return 1
  fi
  ACTIVATION_CANDIDATE_STARTED=true
  compose "$target" up -d --remove-orphans app-ai >/dev/null || return 1
  wait_for_health http://127.0.0.1:18084/actuator/health || return 1
  verify_running_service_image "$target" app-ai "$APP_AI_IMAGE_DIGEST" || return 1
  compose "$target" up -d --remove-orphans app-main >/dev/null || return 1
  wait_for_health http://127.0.0.1:18080/actuator/health || return 1
  verify_running_service_image "$target" app-main "$APP_MAIN_IMAGE_DIGEST" || return 1
  if [[ "$preserve_pending" != true ]]; then write_activation_journal "$release_id" SERVICES_HEALTHY || return 1; fi
  "$STAGE_NGINX_BIN" --release-id "$release_id" >/dev/null || return 1
  stage_origin_smoke || return 1
  if [[ "$preserve_pending" != true ]]; then write_activation_journal "$release_id" NGINX_STAGED || return 1; fi
}

rollback_runtime() {
  local target=$1 current_target=$2 target_main target_ai current_main current_ai current_id
  require_runtime_control_files || return 1
  validate_runtime_envs "$target" || return 1
  "$DOCKER_BIN" network inspect ieum >/dev/null || return 1
  "$DOCKER_BIN" network inspect ieum-minio >/dev/null || return 1
  verify_rendered_images "$target" || return 1
  pull_private_images "$target" || return 1
  verify_pulled_images || return 1
  PGSERVICEFILE="$PGSERVICEFILE" PGPASSFILE="$PGPASSFILE" "$DB_PREFLIGHT_BIN" --kind production --admin-service ieum_target_admin >/dev/null || return 1
  current_main="$(release_state_value "$current_target/state.env" APP_MAIN_IMAGE_DIGEST)" || return 1
  current_ai="$(release_state_value "$current_target/state.env" APP_AI_IMAGE_DIGEST)" || return 1
  current_id="$(release_state_value "$current_target/state.env" RELEASE_ID)" || return 1

  # The current process must be stopped before the previous release can claim
  # the fixed ports. If the candidate cannot become healthy, restore the
  # current process and leave the current symlink untouched.
  compose "$current_target" down --remove-orphans >/dev/null || return 1
  if ! compose "$target" up -d --remove-orphans app-ai >/dev/null \
    || ! wait_for_health http://127.0.0.1:18084/actuator/health \
    || ! verify_running_service_image "$target" app-ai "$APP_AI_IMAGE_DIGEST" \
    || ! compose "$target" up -d --remove-orphans app-main >/dev/null \
    || ! wait_for_health http://127.0.0.1:18080/actuator/health \
    || ! verify_running_service_image "$target" app-main "$APP_MAIN_IMAGE_DIGEST" \
    || ! "$STAGE_NGINX_BIN" --release-id "$(release_state_value "$target/state.env" RELEASE_ID)" >/dev/null \
    || ! stage_origin_smoke; then
    compose "$target" down --remove-orphans >/dev/null 2>&1 || true
    if ! compose "$current_target" up -d --remove-orphans app-ai >/dev/null \
      || ! wait_for_health http://127.0.0.1:18084/actuator/health \
      || ! verify_running_service_image "$current_target" app-ai "$current_ai" \
      || ! compose "$current_target" up -d --remove-orphans app-main >/dev/null \
      || ! wait_for_health http://127.0.0.1:18080/actuator/health \
      || ! verify_running_service_image "$current_target" app-main "$current_main" \
      || ! "$STAGE_NGINX_BIN" --release-id "$current_id" >/dev/null \
      || ! stage_origin_smoke; then
      write_activation_journal "$current_id" MANUAL_INTERVENTION || true
      printf 'ieum deploy release: rollback candidate failed and current runtime recovery failed; manual intervention is required\n' >&2
    fi
    return 1
  fi
}

rollback_release() {
  local current_state current_id previous_id previous_bundle previous_target target_state target
  require_control_plane
  valid_release_id "$expected_id" || die "invalid expected current release"
  require_public_write_uncommitted
  acquire_lock
  current_state="$(current_state_file)" || die "no active release to roll back"
  validate_current_state "$current_state"
  current_id="$(release_state_value "$current_state" RELEASE_ID)"
  [[ "$current_id" == "$expected_id" ]] || die "current release does not match expected value"
  [[ "$(activation_journal_migration_started "$current_id")" == false ]] || die "rollback is blocked after current migration started"
  previous_id="$(release_state_value "$current_state" PREVIOUS_RELEASE_ID)"
  previous_bundle="$(release_state_value "$current_state" PREVIOUS_BUNDLE_SHA256)"
  valid_release_id "$previous_id" || die "current release has no safe previous release"
  valid_sha256 "$previous_bundle" || die "current release has invalid previous release metadata"
  target="$RELEASES_DIR/$previous_id"
  target_state="$target/state.env"
  [[ -d "$target" && ! -L "$target" ]] || die "previous release target is unavailable"
  validate_release_state "$target_state"
  [[ "$(release_state_value "$target_state" BUNDLE_SHA256)" == "$previous_bundle" ]] || die "previous release bundle does not match current metadata"
  validate_current_state "$target_state"
  bundle_sha256="$previous_bundle"
  verify_promoted_release_integrity "$target"
  APP_MAIN_IMAGE_DIGEST="$(release_state_value "$target_state" APP_MAIN_IMAGE_DIGEST)"
  APP_AI_IMAGE_DIGEST="$(release_state_value "$target_state" APP_AI_IMAGE_DIGEST)"
  rollback_runtime "$target" "${current_state%/state.env}" || die "previous release runtime failed health or stage checks; manual intervention may be required"
  local new_current="$RELEASE_ROOT/.current.rollback.tmp"
  [[ ! -e "$new_current" && ! -L "$new_current" ]] || die "temporary rollback link already exists"
  if ! ln -s "$target" "$new_current" || ! mv -Tf "$new_current" "$CURRENT_LINK"; then
    rm -f -- "$new_current"
    [[ ! -e "$new_current" && ! -L "$new_current" ]] || true
    APP_MAIN_IMAGE_DIGEST="$(release_state_value "$current_state" APP_MAIN_IMAGE_DIGEST)"
    APP_AI_IMAGE_DIGEST="$(release_state_value "$current_state" APP_AI_IMAGE_DIGEST)"
    rollback_runtime "${current_state%/state.env}" "$target" || true
    write_activation_journal "$previous_id" MANUAL_INTERVENTION || true
    die "unable to commit rollback current link; previous runtime restoration was attempted and manual intervention is required"
  fi
  [[ "$(readlink "$CURRENT_LINK")" == "$target" ]] || {
    write_activation_journal "$previous_id" MANUAL_INTERVENTION || true
    die "rollback current pointer does not match target; manual intervention is required"
  }
  if ! production_ingress_gate "$previous_id"; then
    APP_MAIN_IMAGE_DIGEST="$(release_state_value "$current_state" APP_MAIN_IMAGE_DIGEST)"
    APP_AI_IMAGE_DIGEST="$(release_state_value "$current_state" APP_AI_IMAGE_DIGEST)"
    if ! rollback_runtime "${current_state%/state.env}" "$target"; then
      write_activation_journal "$previous_id" MANUAL_INTERVENTION || true
      die "rollback production ingress gate failed and previous runtime recovery failed; manual intervention is required"
    fi
    local restore_current="$RELEASE_ROOT/.current.rollback-restore.tmp"
    if ! ln -s "${current_state%/state.env}" "$restore_current" || ! mv -Tf "$restore_current" "$CURRENT_LINK"; then
      rm -f -- "$restore_current"
      write_activation_journal "$previous_id" MANUAL_INTERVENTION || true
      die "rollback production ingress gate failed and current pointer restoration failed; manual intervention is required"
    fi
    [[ "$(readlink "$CURRENT_LINK")" == "${current_state%/state.env}" ]] || {
      write_activation_journal "$previous_id" MANUAL_INTERVENTION || true
      die "rollback current pointer restoration could not be verified; manual intervention is required"
    }
    production_ingress_gate "$current_id" || {
      write_activation_journal "$current_id" MANUAL_INTERVENTION || true
      write_activation_journal "$previous_id" MANUAL_INTERVENTION || true
      die "rollback production ingress gate failed during previous release recovery; manual intervention is required"
    }
    write_activation_journal "$previous_id" MANUAL_INTERVENTION || true
    die "rollback production ingress gate failed; previous release was restored and manual intervention may be required"
  fi
  printf 'rollback to %s activated\n' "$previous_id"
}

cleanup_apply_workspace() {
  local rc=$?
  trap - EXIT
  rm -f "${CLEANUP_SPOOL:-}"
  rm -f "${CLEANUP_CURRENT_TMP:-}"
  rm -rf "${CLEANUP_OUTER_DIR:-}" "${CLEANUP_PAYLOAD_DIR:-}" "${CLEANUP_PROMOTED_TARGET:-}"
  exit "$rc"
}

apply_release() {
  local spool outer_dir inner payload_dir actual_sha current_id current_bundle current_state target target_state new_current actual_migration_digest status
  local target_phase previous_target migration_required pending_recovered=false
  require_control_plane
  valid_release_id "$release_id" || die "invalid release id"
  valid_sha256 "$bundle_sha256" || die "invalid bundle checksum"
  if [[ "$expected_current" != none ]]; then
    valid_release_id "$expected_current" || die "invalid expected current release"
  fi
  spool="$(mktemp "$STAGING_DIR/.release-envelope.XXXXXX")" || die "unable to create release spool"
  outer_dir="$(mktemp -d "$STAGING_DIR/.release-envelope.XXXXXX")" || die "unable to create release extraction directory"
  payload_dir=''
  CLEANUP_SPOOL="$spool"
  CLEANUP_OUTER_DIR="$outer_dir"
  CLEANUP_PAYLOAD_DIR=''
  CLEANUP_CURRENT_TMP=''
  CLEANUP_PROMOTED_TARGET=''
  trap cleanup_apply_workspace EXIT
  head -c "$((MAX_ENVELOPE_BYTES + 1))" > "$spool" || die "unable to spool release envelope"
  [[ "$(wc -c < "$spool" | tr -d ' ')" -le "$MAX_ENVELOPE_BYTES" ]] || die "release envelope exceeds maximum size"
  verify_exact_tar_members "$spool" $'release.tar\nrelease.tar.sig' || die "release envelope has an invalid layout"
  tar --no-same-owner --no-same-permissions -xf "$spool" -C "$outer_dir" || die "unable to extract release envelope"
  inner="$outer_dir/release.tar"
  [[ -f "$inner" && ! -L "$inner" && -f "$outer_dir/release.tar.sig" && ! -L "$outer_dir/release.tar.sig" ]] || die "release envelope members are unsafe"
  actual_sha="$(sha256sum "$inner" | awk '{print $1}')"
  [[ "$actual_sha" == "$bundle_sha256" ]] || die "release bundle checksum does not match"
  ssh-keygen -Y verify -n ieum-release -I ieum-release -f "$ALLOWED_SIGNERS" -s "$outer_dir/release.tar.sig" < "$inner" >/dev/null 2>&1 || die "release signature verification failed"
  payload_dir="$(mktemp -d "$STAGING_DIR/.release-payload.XXXXXX")" || die "unable to create payload extraction directory"
  CLEANUP_PAYLOAD_DIR="$payload_dir"
  verify_payload_members "$inner" || die "release payload has an invalid layout"
  tar --no-same-owner --no-same-permissions -xf "$inner" -C "$payload_dir" || die "unable to extract release payload"
  if [[ "$EUID" -eq 0 ]]; then
    chown -R root:root "$payload_dir" || die "unable to own release payload"
  fi
  find "$payload_dir" -type d -exec chmod 700 {} + || die "unable to secure release directories"
  chmod 600 "$payload_dir/manifest.json" "$payload_dir/release.env" "$payload_dir/checksums.sha256" \
    "$payload_dir/deploy/onprem/compose.yml" \
    "$payload_dir/deploy/onprem/nginx/ieum.rktclgh.site.conf" \
    "$payload_dir/deploy/onprem/nginx/files.rktclgh.site.conf" \
    "$payload_dir/deploy/onprem/nginx/ieum1.rktclgh.site.conf" || die "unable to secure release payload"
  chmod 700 "$payload_dir/deploy/onprem/scripts/validate-runtime-env.sh" \
    "$payload_dir/deploy/scripts/apply-admin-dashboard-migrations.sh" || die "unable to secure release scripts"
  find "$payload_dir/db/migrations" -type f -name '*.sql' -exec chmod 600 {} + || die "unable to secure release migrations"
  verify_payload_checksum_set "$payload_dir" "$inner" || die "release payload checksum set is incomplete or unsafe"
  ( cd "$payload_dir" && sha256sum --check checksums.sha256 >/dev/null ) || die "release payload checksum verification failed"
  verify_migration_member_set "$payload_dir" || die "release payload migrations do not match the migration helper"
  parse_release_env "$payload_dir/release.env"
  parse_manifest "$payload_dir/manifest.json"
  actual_migration_digest="$(calculate_migration_digest "$payload_dir")" || die "unable to calculate release migration checksum"
  [[ "$actual_migration_digest" == "$MANIFEST_MIGRATION_SHA256" ]] || die "release manifest migration checksum does not match payload"
  acquire_lock
  [[ ! -e "$CURRENT_LINK" || -L "$CURRENT_LINK" ]] || die "current release path must be an absent or symlink"
  target="$RELEASES_DIR/$release_id"
  if [[ -e "$target" || -L "$target" ]]; then
    [[ -d "$target" && ! -L "$target" ]] || die "existing release target is unsafe"
    target_state="$target/state.env"
    release_state_matches_payload "$target_state" || die "release id already exists with different state"
    verify_promoted_release_integrity "$target" || die "existing release target integrity verification failed"
    target_phase="$(validate_activation_journal "$release_id")"
    if current_state="$(current_state_file)"; then
      if [[ "$current_state" == "$target_state" ]]; then
        validate_release_state "$current_state"
        target_phase="$(validate_activation_journal "$release_id")"
        [[ "$target_phase" == ACTIVE || "$target_phase" == MANUAL_INTERVENTION ]] || die "current release is not ready for production ingress retry"
        if ! production_ingress_gate "$release_id"; then
          write_activation_journal "$release_id" MANUAL_INTERVENTION || true
          die "production ingress gate failed; manual intervention is required"
        fi
        write_activation_journal "$release_id" ACTIVE || die "unable to restore active release journal after production ingress retry"
        printf 'release %s already accepted\n' "$release_id"
        return
      fi
      current_id="$(current_release_id)"
      current_bundle="$(release_state_value "$current_state" BUNDLE_SHA256)"
      [[ "$expected_current" != none && "$current_id" == "$expected_current" ]] || die "existing release has not completed an atomic current commit"
      validate_manifest_previous_release "$current_id" "$current_bundle"
      [[ "$target_phase" == ACTIVE || "$target_phase" == COMMIT_PENDING ]] || die "existing release has not completed activation; manual intervention required"
      if [[ "$target_phase" == COMMIT_PENDING ]]; then
        ACTIVATION_MIGRATION_STARTED=false
        ACTIVATION_CANDIDATE_STARTED=false
        activate_candidate_runtime "$target" false true || {
          if [[ "$(activation_journal_migration_started "$release_id")" == false ]]; then
            rollback_pre_migration_runtime "$target" "${current_state%/state.env}" || true
          fi
          production_ingress_gate "$current_id" || true
          write_activation_journal "$release_id" MANUAL_INTERVENTION || true
          die "pending release runtime recovery failed; manual intervention required"
        }
        write_activation_journal "$release_id" COMMIT_PENDING || die "unable to preserve pending activation state"
        production_ingress_gate "$release_id" true || {
          if [[ "$(activation_journal_migration_started "$release_id")" == false ]]; then
            rollback_pre_migration_runtime "$target" "${current_state%/state.env}" || true
          fi
          production_ingress_gate "$current_id" || true
          write_activation_journal "$release_id" MANUAL_INTERVENTION || true
          die "pending release production ingress recovery failed; manual intervention required"
        }
        write_activation_journal "$release_id" ACTIVE || die "unable to mark recovered pending release active"
        pending_recovered=true
      fi
    else
      status=$?
      [[ "$status" == 3 ]] || return "$status"
      [[ "$target_phase" == ACTIVE && "$expected_current" == none && -z "$MANIFEST_PREVIOUS_RELEASE_ID" ]] || die "existing release has not completed activation; manual intervention required"
    fi
    new_current="$RELEASE_ROOT/.current.${release_id}.tmp"
    [[ ! -e "$new_current" && ! -L "$new_current" ]] || die "temporary current release link already exists"
    CLEANUP_CURRENT_TMP="$new_current"
    ln -s "$target" "$new_current" || die "unable to prepare current release link"
    mv -Tf "$new_current" "$CURRENT_LINK" || die "unable to reconcile current release link"
    [[ "$(readlink "$CURRENT_LINK")" == "$target" ]] || {
      write_activation_journal "$release_id" MANUAL_INTERVENTION || true
      die "reconciled current pointer does not match target; manual intervention is required"
    }
    CLEANUP_CURRENT_TMP=''
    write_activation_journal "$release_id" ACTIVE || die "unable to mark reconciled release active"
    if [[ "$pending_recovered" != true ]] && ! production_ingress_gate "$release_id"; then
      write_activation_journal "$release_id" MANUAL_INTERVENTION || true
      die "production ingress gate failed; manual intervention is required"
    fi
    printf 'release %s reconciled after an interrupted promotion\n' "$release_id"
    return
  fi
  current_id=''
  current_bundle=''
  current_state=''
  if current_id="$(current_release_id)"; then
    current_state="$(current_state_file)" || return $?
    current_bundle="$(release_state_value "$current_state" BUNDLE_SHA256)"
  else
    status=$?
    [[ "$status" == 3 ]] || return "$status"
    current_id=''
  fi
  if [[ "$expected_current" == none ]]; then
    [[ -z "$current_id" ]] || die "current release does not match expected value"
  else
    [[ "$current_id" == "$expected_current" ]] || die "current release does not match expected value"
  fi
  validate_manifest_previous_release "$current_id" "$current_bundle"
  write_state "$payload_dir/state.env"
  printf '%s\n' "$bundle_sha256" > "$payload_dir/bundle.sha256"
  chmod 600 "$payload_dir/bundle.sha256" || die "unable to secure bundle checksum"
  mv "$payload_dir" "$target" || die "unable to promote release payload"
  CLEANUP_PAYLOAD_DIR=''
  write_activation_journal "$release_id" INSTALLED
  previous_target=''
  migration_required=true
  if [[ -n "$current_state" ]]; then
    previous_target="${current_state%/state.env}"
    if [[ "$(release_state_value "$current_state" MIGRATION_SHA256)" == "$MANIFEST_MIGRATION_SHA256" ]]; then
      migration_required=false
    fi
  fi
  ACTIVATION_MIGRATION_STARTED=false
  if activate_candidate_runtime "$target" "$migration_required"; then
    :
  else
    if [[ "$ACTIVATION_MIGRATION_STARTED" == true ]]; then
      write_activation_journal "$release_id" MANUAL_INTERVENTION
      die "release activation failed after migration started; current release was left unchanged and manual intervention is required"
    fi
    if ! rollback_pre_migration_runtime "$target" "$previous_target"; then
      if [[ -z "$previous_target" ]]; then
        die "release activation failed before migration; candidate runtime cleanup failed and manual intervention is required"
      fi
      die "release activation failed and previous runtime recovery failed"
    fi
    write_activation_journal "$release_id" FAILED_PRE_MIGRATION_ROLLED_BACK
    die "release activation failed before migration; previous runtime was restored"
  fi
  write_activation_journal "$release_id" COMMIT_PENDING
  if ! production_ingress_gate "$release_id" true; then
    if [[ "$ACTIVATION_MIGRATION_STARTED" == true ]]; then
      if [[ -n "$previous_target" ]]; then
        production_ingress_gate "$(release_state_value "$previous_target/state.env" RELEASE_ID)" || true
      fi
      write_activation_journal "$release_id" MANUAL_INTERVENTION || true
      die "production ingress gate failed after migration started; current release was left unchanged and manual intervention is required"
    fi
    rollback_ok=false
    if [[ -n "$previous_target" ]]; then
      rollback_pre_migration_runtime "$target" "$previous_target" && rollback_ok=true
    else
      rollback_pre_migration_runtime "$target" "" && rollback_ok=true
    fi
    if [[ "$rollback_ok" == true ]]; then
      if [[ -n "$previous_target" ]] && ! production_ingress_gate "$(release_state_value "$previous_target/state.env" RELEASE_ID)"; then
        write_activation_journal "$release_id" MANUAL_INTERVENTION || true
        die "production ingress gate failed and previous production ingress restoration failed; manual intervention is required"
      fi
      write_activation_journal "$release_id" FAILED_PRE_MIGRATION_ROLLED_BACK || true
      die "production ingress gate failed before commit; candidate runtime was rolled back"
    fi
    write_activation_journal "$release_id" MANUAL_INTERVENTION || true
    die "production ingress gate failed; previous runtime restoration failed and manual intervention is required"
  fi
  # Mark the candidate active before the final symlink swap. If the process
  # dies after this point, a retry can CAS against the still-current previous
  # release and reconcile the already-verified candidate atomically.
  write_activation_journal "$release_id" ACTIVE
  new_current="$RELEASE_ROOT/.current.${release_id}.tmp"
  [[ ! -e "$new_current" && ! -L "$new_current" ]] || die "temporary current release link already exists"
  CLEANUP_CURRENT_TMP="$new_current"
  if ! ln -s "$target" "$new_current"; then
    write_activation_journal "$release_id" MANUAL_INTERVENTION || true
    die "unable to prepare current release link; manual intervention is required"
  fi
  if ! mv -Tf "$new_current" "$CURRENT_LINK"; then
    write_activation_journal "$release_id" MANUAL_INTERVENTION || true
    die "unable to commit current release link; manual intervention is required"
  fi
  [[ "$(readlink "$CURRENT_LINK")" == "$target" ]] || {
    write_activation_journal "$release_id" MANUAL_INTERVENTION || true
    die "current pointer does not match committed target; manual intervention is required"
  }
  CLEANUP_CURRENT_TMP=''
  rm -f "$spool"
  rm -rf "$outer_dir"
  trap - EXIT
  printf 'release %s activated\n' "$release_id"
}

[[ $# -ge 1 ]] || usage
command_name=$1; shift
case "$command_name" in
  current)
    [[ "$#" == 1 && "$1" == --json ]] || usage
    require_control_plane
    acquire_lock
    print_current_json
    ;;
  apply)
    release_id=''; expected_current=''; bundle_sha256=''
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --release-id) [[ $# -ge 2 ]] || die "--release-id requires a value"; release_id=$2; shift 2 ;;
        --expected-current) [[ $# -ge 2 ]] || die "--expected-current requires a value"; expected_current=$2; shift 2 ;;
        --bundle-sha256) [[ $# -ge 2 ]] || die "--bundle-sha256 requires a value"; bundle_sha256=$2; shift 2 ;;
        *) die "unsupported argument: $1" ;;
      esac
    done
    [[ -n "$release_id" && -n "$expected_current" && -n "$bundle_sha256" ]] || usage
    apply_release
    ;;
  rollback)
    [[ "$#" == 2 && "$1" == --expected-current ]] || usage
    expected_id=$2
    rollback_release
    ;;
  *) usage ;;
esac
