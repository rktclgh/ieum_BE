#!/usr/bin/env bash
set -euo pipefail
umask 077

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
HELPER="$SCRIPT_DIR/../scripts/install-staging-nginx.sh"
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ieum-staging-nginx.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT

release_id='r-29430000000-1-0123456789abcdef0123456789abcdef01234567'
release_root="$TMP_DIR/release-root"
candidate_dir="$TMP_DIR/candidate"
available_dir="$TMP_DIR/available"
enabled_dir="$TMP_DIR/enabled"
cert_file="$TMP_DIR/cert.pem"
key_file="$TMP_DIR/key.pem"
fake_bin="$TMP_DIR/bin"
systemctl_log="$TMP_DIR/systemctl.log"
nginx_log="$TMP_DIR/nginx.log"
mkdir -p "$fake_bin"

cat >"$fake_bin/curl" <<'EOF'
#!/usr/bin/env bash
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
printf '%s\n' test >>"$FAKE_NGINX_LOG"
if [[ "${FAKE_NGINX:-ok}" == always-fail ]]; then exit 1; fi
if [[ "${FAKE_NGINX:-ok}" == test-fail && "$(wc -l <"$FAKE_NGINX_LOG")" -eq 1 ]]; then exit 1; fi
exit 0
EOF
cat >"$fake_bin/systemctl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' reload >>"$FAKE_SYSTEMCTL_LOG"
if [[ "${FAKE_NGINX:-ok}" == reload-fail && "$(wc -l <"$FAKE_SYSTEMCTL_LOG")" -eq 1 ]]; then exit 1; fi
exit 0
EOF
chmod 700 "$fake_bin"/*

run_helper() {
  local args=(--release-id "$release_id")
  if [[ $# -gt 0 ]]; then args=("$@"); fi
  IEUM_STAGING_NGINX_TEST_MODE=1 \
  IEUM_STAGING_ALLOW_REMOVE_TEST=1 \
  IEUM_STAGING_RELEASE_ROOT="$release_root" \
  IEUM_STAGING_CANDIDATE_DIR="$candidate_dir" \
  IEUM_STAGING_AVAILABLE_DIR="$available_dir" \
  IEUM_STAGING_ENABLED_DIR="$enabled_dir" \
  IEUM_STAGING_CERT_FILE="$cert_file" \
  IEUM_STAGING_KEY_FILE="$key_file" \
  IEUM_STAGING_NGINX_BIN="$fake_bin/nginx" \
  IEUM_STAGING_SYSTEMCTL_BIN="$fake_bin/systemctl" \
  IEUM_STAGING_CURL_BIN="$fake_bin/curl" \
  IEUM_STAGING_OPENSSL_BIN="$fake_bin/openssl" \
  FAKE_HEALTH="${FAKE_HEALTH:-up}" \
  FAKE_CERT="${FAKE_CERT:-valid}" \
  FAKE_NGINX="${FAKE_NGINX:-ok}" \
  FAKE_NGINX_LOG="$nginx_log" \
  FAKE_SYSTEMCTL_LOG="$systemctl_log" \
  "$HELPER" "${args[@]}"
}

setup_state() {
  rm -rf "$release_root" "$candidate_dir" "$available_dir" "$enabled_dir"
  : >"$systemctl_log"
  : >"$nginx_log"
  mkdir -p \
    "$release_root/releases/$release_id/deploy/onprem/nginx" \
    "$candidate_dir" "$available_dir" "$enabled_dir"
  chmod 700 "$release_root" "$release_root/releases" \
    "$release_root/releases/$release_id" "$release_root/releases/$release_id/deploy" \
    "$release_root/releases/$release_id/deploy/onprem" \
    "$release_root/releases/$release_id/deploy/onprem/nginx" \
    "$candidate_dir" "$available_dir" "$enabled_dir"
  printf 'server { server_name ieum1.rktclgh.site; add_header X-Ieum-Release-ID "__IEUM_RELEASE_ID__" always; }\n' \
    >"$release_root/releases/$release_id/deploy/onprem/nginx/ieum1.rktclgh.site.conf"
  chmod 600 "$release_root/releases/$release_id/deploy/onprem/nginx/ieum1.rktclgh.site.conf"
  printf 'old-config\n' >"$available_dir/ieum1.rktclgh.site.conf"
  chmod 600 "$available_dir/ieum1.rktclgh.site.conf"
  ln -s "$available_dir/ieum1.rktclgh.site.conf" "$enabled_dir/ieum1.rktclgh.site.conf"
  printf 'certificate\n' >"$cert_file"
  printf 'private-key\n' >"$key_file"
  chmod 600 "$cert_file" "$key_file"
  unset FAKE_HEALTH FAKE_CERT FAKE_NGINX
}

assert_old_state() {
  grep -Fqx 'old-config' "$available_dir/ieum1.rktclgh.site.conf"
  [[ "$(readlink "$enabled_dir/ieum1.rktclgh.site.conf")" == "$available_dir/ieum1.rktclgh.site.conf" ]]
}

setup_state
run_helper >/dev/null
grep -Fq 'server_name ieum1.rktclgh.site' "$available_dir/ieum1.rktclgh.site.conf"
grep -Fq "add_header X-Ieum-Release-ID \"$release_id\" always;" "$available_dir/ieum1.rktclgh.site.conf"
! grep -Fq '__IEUM_RELEASE_ID__' "$available_dir/ieum1.rktclgh.site.conf"
[[ "$(readlink "$enabled_dir/ieum1.rktclgh.site.conf")" == "$available_dir/ieum1.rktclgh.site.conf" ]]

setup_state
FAKE_HEALTH=down
if run_helper >/dev/null 2>"$TMP_DIR/stderr"; then exit 1; fi
assert_old_state

setup_state
FAKE_CERT=invalid
if run_helper >/dev/null 2>"$TMP_DIR/stderr"; then exit 1; fi
assert_old_state

setup_state
rm "$enabled_dir/ieum1.rktclgh.site.conf"
ln -s "$TMP_DIR/escape.conf" "$enabled_dir/ieum1.rktclgh.site.conf"
if run_helper >/dev/null 2>"$TMP_DIR/stderr"; then exit 1; fi
[[ "$(readlink "$enabled_dir/ieum1.rktclgh.site.conf")" == "$TMP_DIR/escape.conf" ]]

setup_state
FAKE_NGINX=test-fail
if run_helper >/dev/null 2>"$TMP_DIR/stderr"; then exit 1; fi
grep -Fqx 'old-config' "$available_dir/ieum1.rktclgh.site.conf"
[[ "$(readlink "$enabled_dir/ieum1.rktclgh.site.conf")" == "$available_dir/ieum1.rktclgh.site.conf" ]]

setup_state
FAKE_NGINX=reload-fail
if run_helper >/dev/null 2>"$TMP_DIR/stderr"; then exit 1; fi
grep -Fqx 'old-config' "$available_dir/ieum1.rktclgh.site.conf"
[[ "$(readlink "$enabled_dir/ieum1.rktclgh.site.conf")" == "$available_dir/ieum1.rktclgh.site.conf" ]]
[[ "$(wc -l <"$systemctl_log")" -eq 2 ]]

setup_state
FAKE_NGINX=always-fail
if run_helper >/dev/null 2>"$TMP_DIR/stderr"; then exit 1; fi
grep -Fq 'ROLLBACK FAILED' "$TMP_DIR/stderr"
grep -Fq 'candidate configuration may still be active' "$TMP_DIR/stderr"
assert_old_state

setup_state
run_helper >/dev/null
run_helper --remove >/dev/null
[[ ! -e "$available_dir/ieum1.rktclgh.site.conf" && ! -L "$available_dir/ieum1.rktclgh.site.conf" ]]
[[ ! -e "$enabled_dir/ieum1.rktclgh.site.conf" && ! -L "$enabled_dir/ieum1.rktclgh.site.conf" ]]

printf 'install-staging-nginx test: PASS\n'
