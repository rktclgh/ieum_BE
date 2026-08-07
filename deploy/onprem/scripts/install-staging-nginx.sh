#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly SITE='ieum1.rktclgh.site'
readonly HEALTH_URL='http://127.0.0.1:18080/actuator/health'
if [[ "$EUID" -eq 0 ]]; then
  IS_PRODUCTION=true
  EXPECTED_OWNER=root
  PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'; export PATH
  RELEASE_ROOT='/srv/ieum'
  CANDIDATE_DIR='/var/lib/ieum/nginx-staging'
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
  [[ "${IEUM_STAGING_NGINX_TEST_MODE:-}" == 1 ]] || { printf 'install-staging-nginx: must run as root\n' >&2; exit 1; }
  EXPECTED_OWNER=$(id -un)
  RELEASE_ROOT="${IEUM_STAGING_RELEASE_ROOT:-}"
  CANDIDATE_DIR="${IEUM_STAGING_CANDIDATE_DIR:-}"
  AVAILABLE_DIR="${IEUM_STAGING_AVAILABLE_DIR:-}"
  ENABLED_DIR="${IEUM_STAGING_ENABLED_DIR:-}"
  CERT_FILE="${IEUM_STAGING_CERT_FILE:-}"
  KEY_FILE="${IEUM_STAGING_KEY_FILE:-}"
  NGINX_BIN="${IEUM_STAGING_NGINX_BIN:-}"
  SYSTEMCTL_BIN="${IEUM_STAGING_SYSTEMCTL_BIN:-}"
  CURL_BIN="${IEUM_STAGING_CURL_BIN:-}"
  OPENSSL_BIN="${IEUM_STAGING_OPENSSL_BIN:-}"
  for path in "$RELEASE_ROOT" "$CANDIDATE_DIR" "$AVAILABLE_DIR" "$ENABLED_DIR" "$CERT_FILE" "$KEY_FILE" "$NGINX_BIN" "$SYSTEMCTL_BIN" "$CURL_BIN" "$OPENSSL_BIN"; do
    [[ "$path" = /* ]] || { printf 'install-staging-nginx: test paths must be absolute\n' >&2; exit 1; }
  done
fi

die() { printf 'install-staging-nginx: %s\n' "$1" >&2; exit 1; }
usage() { printf 'usage: %s --release-id r-<run>-<attempt>-<backend-sha> | %s --remove\n' "$0" "$0" >&2; exit 64; }
stat_mode() { stat -c '%a' -- "$1" 2>/dev/null || stat -f '%Lp' -- "$1"; }
stat_owner() { stat -c '%U' -- "$1" 2>/dev/null || stat -f '%Su' -- "$1"; }
remove_mode=false
if [[ $# -eq 1 && "$1" == --remove ]]; then
  [[ "$IS_PRODUCTION" == true || "${IEUM_STAGING_ALLOW_REMOVE_TEST:-}" == 1 ]] || usage
  remove_mode=true
  release_id=''
else
  [[ $# -eq 2 && "$1" == --release-id ]] || usage
  release_id=$2
  [[ "$release_id" =~ ^r-[0-9]+-[1-9][0-9]*-[0-9a-f]{40}$ ]] || die 'invalid release id'
fi

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
atomic_move() {
  if [[ "$IS_PRODUCTION" == true ]]; then mv -Tf -- "$1" "$2"; else mv -f -- "$1" "$2"; fi
}

private_dir "$RELEASE_ROOT"
private_dir "$RELEASE_ROOT/releases"
private_dir "$CANDIDATE_DIR"
system_dir "$AVAILABLE_DIR"
system_dir "$ENABLED_DIR"
certificate_file "$CERT_FILE"
private_file "$KEY_FILE"
if [[ "$remove_mode" == true ]]; then
  available="$AVAILABLE_DIR/$SITE.conf"
  enabled="$ENABLED_DIR/$SITE.conf"
  [[ -f "$available" && ! -L "$available" ]] || die 'staging config is not an existing regular file'
  grep -Fq 'server_name ieum1.rktclgh.site' "$available" || die 'staging config identity is unexpected'
  if [[ -e "$enabled" || -L "$enabled" ]]; then
    [[ -L "$enabled" && "$(readlink "$enabled")" == "$available" ]] || die 'staging enabled link is unsafe'
  fi
  rm -f -- "$enabled" "$available"
  "$NGINX_BIN" -t || die 'nginx configuration test failed after staging removal'
  "$SYSTEMCTL_BIN" reload nginx || die 'nginx reload failed after staging removal'
  printf 'staging nginx removed: %s\n' "$SITE"
  exit 0
fi
release_dir="$RELEASE_ROOT/releases/$release_id"
private_dir "$release_dir"
private_dir "$release_dir/deploy"
private_dir "$release_dir/deploy/onprem"
private_dir "$release_dir/deploy/onprem/nginx"
source_config="$release_dir/deploy/onprem/nginx/$SITE.conf"
private_file "$source_config"
release_header_marker='__IEUM_RELEASE_ID__'
marker_count=$(awk -v marker="$release_header_marker" '{ count += gsub(marker, marker) } END { print count + 0 }' "$source_config")
[[ "$marker_count" -eq 1 ]] || die 'staging config must contain exactly one release header marker'

health=$("$CURL_BIN" --fail --silent --show-error --max-time 5 "$HEALTH_URL") || die 'app-main health check failed'
printf '%s' "$health" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' || die 'app-main health is not UP'
cert_text=$("$OPENSSL_BIN" x509 -in "$CERT_FILE" -noout -text) || die 'unable to inspect TLS certificate'
printf '%s\n' "$cert_text" | grep -Eq 'DNS:\*\.rktclgh\.site([,[:space:]]|$)' || die 'TLS certificate SAN does not cover *.rktclgh.site'

available="$AVAILABLE_DIR/$SITE.conf"
enabled="$ENABLED_DIR/$SITE.conf"
if [[ -L "$available" ]]; then die 'available config must not be a symlink'; fi
if [[ -e "$available" && ! -f "$available" ]]; then die 'available config is not a regular file'; fi
if [[ -e "$enabled" || -L "$enabled" ]]; then
  [[ -L "$enabled" ]] || die 'enabled path must be a symlink'
  enabled_target=$(readlink "$enabled") || die 'enabled link is unreadable'
  [[ "$enabled_target" == "$available" ]] || die 'enabled link escapes the staging site'
fi

backup_dir=$(mktemp -d "$CANDIDATE_DIR/.install.XXXXXX")
backup_available="$backup_dir/available"
backup_enabled="$backup_dir/enabled"
had_available=false; had_enabled=false
if [[ -f "$available" ]]; then cp -p -- "$available" "$backup_available"; had_available=true; fi
if [[ -L "$enabled" ]]; then cp -P -- "$enabled" "$backup_enabled"; had_enabled=true; fi

restore() {
  local status=$? restore_failed=0 restore_detail=''
  trap - EXIT ERR
  if [[ "$had_available" == true ]]; then
    if ! cp -p -- "$backup_available" "$available"; then
      restore_failed=1; restore_detail+=' available-config-copy'
    fi
  elif ! rm -f -- "$available"; then
    restore_failed=1; restore_detail+=' available-config-remove'
  fi
  if [[ "$had_enabled" == true ]]; then
    if ! rm -f -- "$enabled" || ! ln -s -- "$available" "$enabled"; then
      restore_failed=1; restore_detail+=' enabled-link-restore'
    fi
  elif ! rm -f -- "$enabled"; then
    restore_failed=1; restore_detail+=' enabled-link-remove'
  fi
  if [[ "$restore_failed" -eq 0 ]]; then
    if ! "$NGINX_BIN" -t >/dev/null 2>&1; then
      restore_failed=1; restore_detail+=' nginx-config-test'
    elif ! "$SYSTEMCTL_BIN" reload nginx >/dev/null 2>&1; then
      restore_failed=1; restore_detail+=' nginx-reload'
    fi
  fi
  if [[ "$restore_failed" -eq 0 ]]; then
    if [[ "$had_available" == true ]]; then
      cmp -s -- "$backup_available" "$available" || { restore_failed=1; restore_detail+=' available-config-verify'; }
    else
      [[ ! -e "$available" && ! -L "$available" ]] || { restore_failed=1; restore_detail+=' available-config-verify'; }
    fi
    if [[ "$had_enabled" == true ]]; then
      [[ -L "$enabled" && "$(readlink -- "$enabled")" == "$available" ]] || { restore_failed=1; restore_detail+=' enabled-link-verify'; }
    else
      [[ ! -e "$enabled" && ! -L "$enabled" ]] || { restore_failed=1; restore_detail+=' enabled-link-verify'; }
    fi
  fi
  if ! rm -rf -- "$backup_dir"; then
    restore_failed=1; restore_detail+=' rollback-artifact-cleanup'
  fi
  if [[ "$restore_failed" -ne 0 ]]; then
    printf 'install-staging-nginx: ROLLBACK FAILED (%s); candidate configuration may still be active; inspect %s and Nginx immediately\n' \
      "${restore_detail# }" "$available" >&2
    exit 70
  fi
  exit "$status"
}
trap restore EXIT

candidate=$(mktemp "$CANDIDATE_DIR/.$SITE.XXXXXX")
if ! sed "s/${release_header_marker}/${release_id}/g" "$source_config" > "$candidate"; then
  die 'unable to render staging release header'
fi
if [[ "$IS_PRODUCTION" == true ]]; then chown root:root -- "$candidate"; fi
chmod 0644 "$candidate"
! grep -Fq "$release_header_marker" "$candidate" || die 'staging release header marker was not rendered'
atomic_move "$candidate" "$available"
new_link=$(mktemp "$CANDIDATE_DIR/.$SITE.link.XXXXXX")
rm -f -- "$new_link"
ln -s -- "$available" "$new_link"
atomic_move "$new_link" "$enabled"

"$NGINX_BIN" -t || die 'nginx configuration test failed'
"$SYSTEMCTL_BIN" reload nginx || die 'nginx reload failed'

trap - EXIT ERR
rm -rf -- "$backup_dir"
printf 'staging nginx installed: %s (%s)\n' "$SITE" "$release_id"
