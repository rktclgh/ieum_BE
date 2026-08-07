#!/usr/bin/env bash
set -euo pipefail
umask 077

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
HELPER="$SCRIPT_DIR/../scripts/install-production-nginx.sh"
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ieum-production-nginx.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT

release_id='r-29430000000-1-0123456789abcdef0123456789abcdef01234567'
release_root="$TMP_DIR/release-root"
candidate_dir="$TMP_DIR/candidate"
available_dir="$TMP_DIR/available"
enabled_dir="$TMP_DIR/enabled"
cert_file="$TMP_DIR/cert.pem"
key_file="$TMP_DIR/key.pem"
fake_bin="$TMP_DIR/bin"
journal_file="$TMP_DIR/journal/activation.env"
systemctl_log="$TMP_DIR/systemctl.log"
nginx_log="$TMP_DIR/nginx.log"
mkdir -p "$fake_bin"

cat >"$fake_bin/curl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$FAKE_CURL_LOG"
case "${@: -1}" in
  https://ieum.rktclgh.site/api/places/search)
    if [[ "${FAKE_PUBLIC_SMOKE:-up}" == flaky && "$(grep -Fc -- 'https://ieum.rktclgh.site/api/places/search' "$FAKE_CURL_LOG")" -le 2 ]]; then
      exit 52
    fi
    [[ "${FAKE_PUBLIC_SMOKE:-up}" == up || "${FAKE_PUBLIC_SMOKE:-up}" == flaky ]] && { printf '{}\n'; exit 0; }
    exit 22
    ;;
  https://files.rktclgh.site/minio/health/live)
    if [[ "${FAKE_FILES_SMOKE:-up}" == flaky && "$(grep -Fc -- 'https://files.rktclgh.site/minio/health/live' "$FAKE_CURL_LOG")" -le 2 ]]; then
      exit 52
    fi
    [[ "${FAKE_FILES_SMOKE:-up}" == up || "${FAKE_FILES_SMOKE:-up}" == flaky ]] && { printf '{}\n'; exit 0; }
    exit 22
    ;;
esac
if [[ "${FAKE_HEALTH:-up}" == up ]]; then printf '{"status":"UP"}\n'; exit 0; fi
exit 22
EOF
cat >"$fake_bin/openssl" <<'EOF'
#!/usr/bin/env bash
if [[ "${FAKE_CERT:-valid}" == valid ]]; then printf 'X509v3 Subject Alternative Name:\n    DNS:*.rktclgh.site\n'; exit 0; fi
printf 'DNS:other.example\n'
exit 0
EOF
cat >"$fake_bin/nginx" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$FAKE_NGINX_LOG"
if [[ "${FAKE_NGINX:-ok}" == always-fail ]]; then exit 1; fi
if [[ "${FAKE_NGINX:-ok}" == test-fail && "$*" == '-t' && "$(wc -l <"$FAKE_NGINX_LOG")" -eq 1 ]]; then exit 1; fi
exit 0
EOF
cat >"$fake_bin/systemctl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$FAKE_SYSTEMCTL_LOG"
if [[ "${FAKE_NGINX:-ok}" == reload-fail && "$(wc -l <"$FAKE_SYSTEMCTL_LOG")" -eq 1 ]]; then exit 1; fi
exit 0
EOF
chmod 700 "$fake_bin"/*

run_helper() {
  IEUM_PRODUCTION_NGINX_TEST_MODE=1 \
  IEUM_PRODUCTION_RELEASE_ROOT="$release_root" \
  IEUM_PRODUCTION_CANDIDATE_DIR="$candidate_dir" \
  IEUM_PRODUCTION_AVAILABLE_DIR="$available_dir" \
  IEUM_PRODUCTION_ENABLED_DIR="$enabled_dir" \
  IEUM_PRODUCTION_CERT_FILE="$cert_file" \
  IEUM_PRODUCTION_KEY_FILE="$key_file" \
  IEUM_PRODUCTION_NGINX_BIN="$fake_bin/nginx" \
  IEUM_PRODUCTION_SYSTEMCTL_BIN="$fake_bin/systemctl" \
  IEUM_PRODUCTION_CURL_BIN="$fake_bin/curl" \
  IEUM_PRODUCTION_OPENSSL_BIN="$fake_bin/openssl" \
  IEUM_PRODUCTION_JOURNAL_FILE="$journal_file" \
  FAKE_HEALTH="${FAKE_HEALTH:-up}" FAKE_CERT="${FAKE_CERT:-valid}" FAKE_NGINX="${FAKE_NGINX:-ok}" \
  FAKE_PUBLIC_SMOKE="${FAKE_PUBLIC_SMOKE:-up}" FAKE_FILES_SMOKE="${FAKE_FILES_SMOKE:-up}" \
  FAKE_CURL_LOG="$TMP_DIR/curl.log" \
  FAKE_NGINX_LOG="$nginx_log" FAKE_SYSTEMCTL_LOG="$systemctl_log" \
  "$HELPER" --release-id "$release_id" --confirm-public-ingress "$@"
}

setup_state() {
  rm -rf "$release_root" "$candidate_dir" "$available_dir" "$enabled_dir" "$journal_file" "$systemctl_log" "$nginx_log"
  mkdir -p \
    "$release_root/releases/$release_id/deploy/onprem/nginx" \
    "$candidate_dir" "$available_dir" "$enabled_dir" "$(dirname "$journal_file")"
  chmod 700 "$release_root" "$release_root/releases" "$release_root/releases/$release_id" \
    "$release_root/releases/$release_id/deploy" "$release_root/releases/$release_id/deploy/onprem" \
    "$release_root/releases/$release_id/deploy/onprem/nginx" "$candidate_dir" "$available_dir" "$enabled_dir" \
    "$(dirname "$journal_file")"
  printf 'RELEASE_ID=%s\n' "$release_id" >"$release_root/releases/$release_id/state.env"
  printf 'PHASE=ACTIVE\nRELEASE_ID=%s\n' "$release_id" >"$journal_file"
  chmod 600 "$release_root/releases/$release_id/state.env" "$journal_file"
  printf 'server { server_name ieum.rktclgh.site; }\n' >"$release_root/releases/$release_id/deploy/onprem/nginx/ieum.rktclgh.site.conf"
  printf 'server { server_name files.rktclgh.site; }\n' >"$release_root/releases/$release_id/deploy/onprem/nginx/files.rktclgh.site.conf"
  chmod 600 "$release_root/releases/$release_id/deploy/onprem/nginx"/*.conf
  printf 'old-app\n' >"$available_dir/ieum.rktclgh.site.conf"
  printf 'old-files\n' >"$available_dir/files.rktclgh.site.conf"
  chmod 600 "$available_dir"/*.conf
  ln -s "$available_dir/ieum.rktclgh.site.conf" "$enabled_dir/ieum.rktclgh.site.conf"
  ln -s "$available_dir/files.rktclgh.site.conf" "$enabled_dir/files.rktclgh.site.conf"
  ln -s "$release_root/releases/$release_id" "$release_root/current"
  printf certificate >"$cert_file"; printf key >"$key_file"; chmod 600 "$cert_file" "$key_file"
  : >"$systemctl_log"; : >"$nginx_log"; : >"$TMP_DIR/curl.log"
  unset FAKE_HEALTH FAKE_CERT FAKE_NGINX FAKE_FILES_SMOKE
}

assert_old_state() {
  grep -Fqx old-app "$available_dir/ieum.rktclgh.site.conf"
  grep -Fqx old-files "$available_dir/files.rktclgh.site.conf"
  [[ "$(readlink "$enabled_dir/ieum.rktclgh.site.conf")" == "$available_dir/ieum.rktclgh.site.conf" ]]
  [[ "$(readlink "$enabled_dir/files.rktclgh.site.conf")" == "$available_dir/files.rktclgh.site.conf" ]]
}

setup_state
if run_helper >/dev/null 2>"$TMP_DIR/stderr"; then :; else exit 1; fi
grep -Fq 'server_name ieum.rktclgh.site' "$available_dir/ieum.rktclgh.site.conf"
grep -Fq 'server_name files.rktclgh.site' "$available_dir/files.rktclgh.site.conf"
[[ "$(readlink "$enabled_dir/ieum.rktclgh.site.conf")" == "$available_dir/ieum.rktclgh.site.conf" ]]
[[ "$(readlink "$enabled_dir/files.rktclgh.site.conf")" == "$available_dir/files.rktclgh.site.conf" ]]
grep -F -- '--cacert '"$cert_file"' --resolve ieum.rktclgh.site:443:127.0.0.1' "$TMP_DIR/curl.log" >/dev/null
grep -F -- '--cacert '"$cert_file"' --resolve files.rktclgh.site:443:127.0.0.1' "$TMP_DIR/curl.log" >/dev/null
[[ "$(grep -Fc -- '--max-time 5 --noproxy *' "$TMP_DIR/curl.log")" -eq 2 ]] \
  || { printf 'origin smokes must use the bounded post-reload retry path\n' >&2; exit 1; }

setup_state
FAKE_PUBLIC_SMOKE=flaky FAKE_FILES_SMOKE=flaky
if run_helper >/dev/null 2>"$TMP_DIR/stderr"; then :; else exit 1; fi
[[ "$(grep -Fc -- 'https://ieum.rktclgh.site/api/places/search' "$TMP_DIR/curl.log")" -eq 3 ]]
[[ "$(grep -Fc -- 'https://files.rktclgh.site/minio/health/live' "$TMP_DIR/curl.log")" -eq 3 ]]
unset FAKE_PUBLIC_SMOKE FAKE_FILES_SMOKE

setup_state
FAKE_PUBLIC_SMOKE=down
if run_helper >/dev/null 2>"$TMP_DIR/stderr"; then exit 1; fi
assert_old_state

setup_state
FAKE_HEALTH=down
if run_helper >/dev/null 2>"$TMP_DIR/stderr"; then exit 1; fi
assert_old_state

setup_state
FAKE_NGINX=test-fail
if run_helper >/dev/null 2>"$TMP_DIR/stderr"; then exit 1; fi
assert_old_state
[[ "$(wc -l <"$systemctl_log")" -eq 1 ]]

setup_state
FAKE_NGINX=always-fail
if run_helper >/dev/null 2>"$TMP_DIR/stderr"; then exit 1; fi
grep -Fq 'ROLLBACK FAILED' "$TMP_DIR/stderr"
grep -Fq 'candidate configuration may still be active' "$TMP_DIR/stderr"
assert_old_state

setup_state
rm "$enabled_dir/files.rktclgh.site.conf"
ln -s "$TMP_DIR/escape.conf" "$enabled_dir/files.rktclgh.site.conf"
if run_helper >/dev/null 2>"$TMP_DIR/stderr"; then exit 1; fi
[[ "$(readlink "$enabled_dir/files.rktclgh.site.conf")" == "$TMP_DIR/escape.conf" ]]

setup_state
if "$HELPER" --release-id "$release_id" >/dev/null 2>"$TMP_DIR/stderr"; then exit 1; fi
assert_old_state

setup_state
if run_helper --allow-pending-activation >/dev/null 2>"$TMP_DIR/stderr"; then exit 1; fi
assert_old_state

printf 'install-production-nginx test: PASS\n'
