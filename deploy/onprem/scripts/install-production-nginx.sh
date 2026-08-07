#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly APP_SITE='ieum.rktclgh.site'
readonly FILES_SITE='files.rktclgh.site'
readonly APP_HEALTH_URL='http://127.0.0.1:18080/actuator/health'
readonly MINIO_HEALTH_URL='http://127.0.0.1:19000/minio/health/live'
readonly ORIGIN_SMOKE_MAX_ATTEMPTS=3
readonly ORIGIN_SMOKE_RETRY_DELAY_SECONDS=1

if [[ "$EUID" -eq 0 ]]; then
  IS_PRODUCTION=true
  EXPECTED_OWNER=root
  PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'; export PATH
  RELEASE_ROOT='/srv/ieum'
  CANDIDATE_DIR='/var/lib/ieum/nginx-production'
  AVAILABLE_DIR='/etc/nginx/sites-available'
  ENABLED_DIR='/etc/nginx/sites-enabled'
  CERT_FILE='/etc/cloudflare/rktclgh.site.pem'
  KEY_FILE='/etc/cloudflare/rktclgh.site.key'
  NGINX_BIN='/usr/sbin/nginx'
  SYSTEMCTL_BIN='/usr/bin/systemctl'
  CURL_BIN='/usr/bin/curl'
  OPENSSL_BIN='/usr/bin/openssl'
else
  IS_PRODUCTION=false
  [[ "${IEUM_PRODUCTION_NGINX_TEST_MODE:-}" == 1 ]] || {
    printf 'install-production-nginx: must run as root\n' >&2
    exit 1
  }
  EXPECTED_OWNER=$(id -un)
  RELEASE_ROOT="${IEUM_PRODUCTION_RELEASE_ROOT:-}"
  CANDIDATE_DIR="${IEUM_PRODUCTION_CANDIDATE_DIR:-}"
  AVAILABLE_DIR="${IEUM_PRODUCTION_AVAILABLE_DIR:-}"
  ENABLED_DIR="${IEUM_PRODUCTION_ENABLED_DIR:-}"
  CERT_FILE="${IEUM_PRODUCTION_CERT_FILE:-}"
  KEY_FILE="${IEUM_PRODUCTION_KEY_FILE:-}"
  NGINX_BIN="${IEUM_PRODUCTION_NGINX_BIN:-}"
  SYSTEMCTL_BIN="${IEUM_PRODUCTION_SYSTEMCTL_BIN:-}"
  CURL_BIN="${IEUM_PRODUCTION_CURL_BIN:-}"
  OPENSSL_BIN="${IEUM_PRODUCTION_OPENSSL_BIN:-}"
  for path in "$RELEASE_ROOT" "$CANDIDATE_DIR" "$AVAILABLE_DIR" "$ENABLED_DIR" \
    "$CERT_FILE" "$KEY_FILE" "$NGINX_BIN" "$SYSTEMCTL_BIN" "$CURL_BIN" "$OPENSSL_BIN"; do
    [[ "$path" = /* ]] || { printf 'install-production-nginx: test paths must be absolute\n' >&2; exit 1; }
  done
fi

die() { printf 'install-production-nginx: %s\n' "$1" >&2; exit 1; }
usage() {
  printf 'usage: %s --release-id r-<run>-<attempt>-<backend-sha> --confirm-public-ingress [--allow-pending-activation]\n' "$0" >&2
  exit 64
}
stat_mode() { stat -c '%a' -- "$1" 2>/dev/null || stat -f '%Lp' -- "$1"; }
stat_owner() { stat -c '%U' -- "$1" 2>/dev/null || stat -f '%Su' -- "$1"; }

allow_pending=false
[[ $# -ge 3 && $# -le 4 && "$1" == --release-id && "$3" == --confirm-public-ingress ]] || usage
release_id=$2
if [[ $# -eq 4 ]]; then
  [[ "$4" == --allow-pending-activation && "$IS_PRODUCTION" == true ]] || usage
  allow_pending=true
fi
[[ "$release_id" =~ ^r-[0-9]+-[1-9][0-9]*-[0-9a-f]{40}$ ]] || die 'invalid release id'

private_dir() {
  local path=$1 mode owner
  [[ -d "$path" && ! -L "$path" ]] || die "unsafe directory: $path"
  mode=$(stat_mode "$path") || die "unable to inspect directory: $path"
  owner=$(stat_owner "$path") || die "unable to inspect directory owner: $path"
  [[ "$mode" == 700 && "$owner" == "$EXPECTED_OWNER" ]] || die "directory must be private mode 0700: $path"
}
private_file() {
  local path=$1 mode owner
  [[ -f "$path" && ! -L "$path" ]] || die "unsafe file: $path"
  mode=$(stat_mode "$path") || die "unable to inspect file: $path"
  owner=$(stat_owner "$path") || die "unable to inspect file owner: $path"
  [[ "$mode" == 600 && "$owner" == "$EXPECTED_OWNER" ]] || die "file must be private mode 0600: $path"
}
system_dir() {
  local path=$1 mode owner
  [[ -d "$path" && ! -L "$path" ]] || die "unsafe system directory: $path"
  mode=$(stat_mode "$path") || die "unable to inspect directory: $path"
  owner=$(stat_owner "$path") || die "unable to inspect directory owner: $path"
  [[ "$owner" == "$EXPECTED_OWNER" && ( "$mode" == 755 || "$mode" == 700 ) ]] || die "unsafe system directory permissions: $path"
}
certificate_file() {
  local path=$1 mode owner
  [[ -f "$path" && ! -L "$path" ]] || die "unsafe certificate file: $path"
  mode=$(stat_mode "$path") || die "unable to inspect certificate: $path"
  owner=$(stat_owner "$path") || die "unable to inspect certificate owner: $path"
  [[ "$owner" == "$EXPECTED_OWNER" && ( "$mode" == 644 || "$mode" == 600 ) ]] || die "unsafe certificate permissions: $path"
}
existing_config_file() {
  local path=$1 mode owner
  [[ -f "$path" && ! -L "$path" ]] || die "unsafe existing config: $path"
  mode=$(stat_mode "$path") || die "unable to inspect existing config: $path"
  owner=$(stat_owner "$path") || die "unable to inspect existing config owner: $path"
  [[ "$owner" == "$EXPECTED_OWNER" && ( "$mode" == 644 || "$mode" == 600 ) ]] || die "unsafe existing config permissions: $path"
}
atomic_move() {
  if [[ "$IS_PRODUCTION" == true ]]; then mv -Tf -- "$1" "$2"; else mv -f -- "$1" "$2"; fi
}
state_value() {
  awk -F= -v wanted="$2" '$1 == wanted { print substr($0, index($0, "=") + 1); found=1 } END { exit(found ? 0 : 1) }' "$1"
}

private_dir "$RELEASE_ROOT"
private_dir "$RELEASE_ROOT/releases"
private_dir "$CANDIDATE_DIR"
system_dir "$AVAILABLE_DIR"
system_dir "$ENABLED_DIR"
certificate_file "$CERT_FILE"
private_file "$KEY_FILE"
release_dir="$RELEASE_ROOT/releases/$release_id"
private_dir "$release_dir"
private_file "$release_dir/state.env"
private_dir "$release_dir/deploy"
private_dir "$release_dir/deploy/onprem"
private_dir "$release_dir/deploy/onprem/nginx"
source_app="$release_dir/deploy/onprem/nginx/$APP_SITE.conf"
source_files="$release_dir/deploy/onprem/nginx/$FILES_SITE.conf"
private_file "$source_app"
private_file "$source_files"

current_link="$RELEASE_ROOT/current"
if [[ "$allow_pending" == true ]]; then
  journal_phase=COMMIT_PENDING
else
  [[ -L "$current_link" ]] || die 'current release path must be a symlink'
  current_target=$(readlink "$current_link") || die 'current release link is unreadable'
  [[ "$current_target" == "$release_dir" ]] || die 'current release does not match requested release'
  [[ -d "$current_target" && ! -L "$current_target" ]] || die 'current release target is unsafe'
  journal_phase=ACTIVE
fi
[[ "$(state_value "$release_dir/state.env" RELEASE_ID)" == "$release_id" ]] || die 'release state id does not match'
journal="$RELEASE_ROOT/../var/lib/ieum/deployments/$release_id/activation.env"
if [[ "$IS_PRODUCTION" == true ]]; then
  journal="/var/lib/ieum/deployments/$release_id/activation.env"
else
  journal="${IEUM_PRODUCTION_JOURNAL_FILE:-$RELEASE_ROOT/deployments/$release_id/activation.env}"
fi
private_dir "$(dirname "$journal")"
private_file "$journal"
grep -Fqx "RELEASE_ID=$release_id" "$journal" || die 'activation journal release id does not match'
grep -Fqx "PHASE=$journal_phase" "$journal" || die "release is not marked $journal_phase"

health=$($CURL_BIN --fail --silent --show-error --max-time 5 "$APP_HEALTH_URL") || die 'app-main health check failed'
printf '%s' "$health" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' || die 'app-main health is not UP'
$CURL_BIN --fail --silent --show-error --max-time 5 "$MINIO_HEALTH_URL" >/dev/null || die 'MinIO health check failed'
cert_text=$($OPENSSL_BIN x509 -in "$CERT_FILE" -noout -text) || die 'unable to inspect TLS certificate'
printf '%s\n' "$cert_text" | grep -Eq 'DNS:\*\.rktclgh\.site([,[:space:]]|$)' || die 'TLS certificate SAN does not cover *.rktclgh.site'

declare -a sites=("$APP_SITE" "$FILES_SITE")
declare -a available_paths=("$AVAILABLE_DIR/$APP_SITE.conf" "$AVAILABLE_DIR/$FILES_SITE.conf")
declare -a enabled_paths=("$ENABLED_DIR/$APP_SITE.conf" "$ENABLED_DIR/$FILES_SITE.conf")
declare -a source_paths=("$source_app" "$source_files")
for i in 0 1; do
  available=${available_paths[$i]}; enabled=${enabled_paths[$i]}
  [[ ! -L "$available" ]] || die "available config must not be a symlink: ${sites[$i]}"
  if [[ -e "$available" ]]; then existing_config_file "$available"; fi
  if [[ -e "$enabled" || -L "$enabled" ]]; then
    [[ -L "$enabled" ]] || die "enabled path must be a symlink: ${sites[$i]}"
    [[ "$(readlink "$enabled")" == "$available" ]] || die "enabled link escapes site: ${sites[$i]}"
  fi
done

backup_dir=$(mktemp -d "$CANDIDATE_DIR/.install.XXXXXX")
backup_available=(); backup_enabled=(); had_available=(); had_enabled=()
for i in 0 1; do
  available=${available_paths[$i]}; enabled=${enabled_paths[$i]}
  backup_available[$i]="$backup_dir/available.$i"
  backup_enabled[$i]="$backup_dir/enabled.$i"
  had_available[$i]=false; had_enabled[$i]=false
  if [[ -f "$available" ]]; then cp -p -- "$available" "${backup_available[$i]}"; had_available[$i]=true; fi
  if [[ -L "$enabled" ]]; then cp -P -- "$enabled" "${backup_enabled[$i]}"; had_enabled[$i]=true; fi
done

restore() {
  local status=$? i available enabled restore_failed=0 restore_detail=''
  trap - EXIT ERR
  for i in 0 1; do
    available=${available_paths[$i]}; enabled=${enabled_paths[$i]}
    if [[ "${had_available[$i]}" == true ]]; then
      if ! cp -p -- "${backup_available[$i]}" "$available"; then
        restore_failed=1; restore_detail+=" available-config-copy-${sites[$i]}"
      fi
    elif ! rm -f -- "$available"; then
      restore_failed=1; restore_detail+=" available-config-remove-${sites[$i]}"
    fi
    if [[ "${had_enabled[$i]}" == true ]]; then
      if ! rm -f -- "$enabled" || ! ln -s -- "$available" "$enabled"; then
        restore_failed=1; restore_detail+=" enabled-link-restore-${sites[$i]}"
      fi
    elif ! rm -f -- "$enabled"; then
      restore_failed=1; restore_detail+=" enabled-link-remove-${sites[$i]}"
    fi
  done
  if [[ "$restore_failed" -eq 0 ]]; then
    if ! "$NGINX_BIN" -t >/dev/null 2>&1; then
      restore_failed=1; restore_detail+=' nginx-config-test'
    elif ! "$SYSTEMCTL_BIN" reload nginx >/dev/null 2>&1; then
      restore_failed=1; restore_detail+=' nginx-reload'
    fi
  fi
  if [[ "$restore_failed" -eq 0 ]]; then
    for i in 0 1; do
      available=${available_paths[$i]}; enabled=${enabled_paths[$i]}
      if [[ "${had_available[$i]}" == true ]]; then
        cmp -s -- "${backup_available[$i]}" "$available" || { restore_failed=1; restore_detail+=" available-config-verify-${sites[$i]}"; }
      else
        [[ ! -e "$available" && ! -L "$available" ]] || { restore_failed=1; restore_detail+=" available-config-verify-${sites[$i]}"; }
      fi
      if [[ "${had_enabled[$i]}" == true ]]; then
        [[ -L "$enabled" && "$(readlink -- "$enabled")" == "$available" ]] || { restore_failed=1; restore_detail+=" enabled-link-verify-${sites[$i]}"; }
      else
        [[ ! -e "$enabled" && ! -L "$enabled" ]] || { restore_failed=1; restore_detail+=" enabled-link-verify-${sites[$i]}"; }
      fi
    done
  fi
  if ! rm -rf -- "$backup_dir"; then
    restore_failed=1; restore_detail+=' rollback-artifact-cleanup'
  fi
  if [[ "$restore_failed" -ne 0 ]]; then
    printf 'install-production-nginx: ROLLBACK FAILED (%s); candidate configuration may still be active; inspect %s and Nginx immediately\n' \
      "${restore_detail# }" "${available_paths[*]}" >&2
    exit 70
  fi
  exit "$status"
}
trap restore EXIT

for i in 0 1; do
  candidate=$(mktemp "$CANDIDATE_DIR/.${sites[$i]}.XXXXXX")
  if [[ "$IS_PRODUCTION" == true ]]; then install -o root -g root -m 0644 -- "${source_paths[$i]}" "$candidate"; else install -m 0644 -- "${source_paths[$i]}" "$candidate"; fi
  atomic_move "$candidate" "${available_paths[$i]}"
  link_tmp=$(mktemp "$CANDIDATE_DIR/.${sites[$i]}.link.XXXXXX")
  rm -f -- "$link_tmp"
  ln -s -- "${available_paths[$i]}" "$link_tmp"
  atomic_move "$link_tmp" "${enabled_paths[$i]}"
done

"$NGINX_BIN" -t || die 'nginx configuration test failed'
"$SYSTEMCTL_BIN" reload nginx || die 'nginx reload failed'
origin_smoke() {
  local host=$1 url=$2 attempt
  for ((attempt = 1; attempt <= ORIGIN_SMOKE_MAX_ATTEMPTS; attempt++)); do
    if "$CURL_BIN" --fail --silent --show-error --max-time 5 \
      --noproxy '*' --http1.1 \
      --cacert "$CERT_FILE" \
      --resolve "$host:443:127.0.0.1" \
      "$url" >/dev/null; then
      return 0
    fi
    if ((attempt < ORIGIN_SMOKE_MAX_ATTEMPTS)); then
      sleep "$ORIGIN_SMOKE_RETRY_DELAY_SECONDS"
    fi
  done
  return 1
}
origin_smoke ieum.rktclgh.site https://ieum.rktclgh.site/api/places/search \
  || die 'production origin smoke failed'
origin_smoke files.rktclgh.site https://files.rktclgh.site/minio/health/live \
  || die 'files origin smoke failed'
rm -rf -- "$backup_dir" || die 'unable to clean up production nginx rollback artifact'
trap - EXIT ERR
printf 'production nginx installed: %s, %s (%s)\n' "$APP_SITE" "$FILES_SITE" "$release_id"
