#!/usr/bin/env bash
set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ONPREM_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
SCRIPT="$ONPREM_DIR/scripts/provision-rabbitmq.sh"
BROKER_COMPOSE="$ONPREM_DIR/rabbitmq/compose.yml"
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ieum-provision-rabbitmq.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}
assert_contains() {
  file=$1
  pattern=$2
  grep -Eq "$pattern" "$file" || fail "$file does not match $pattern"
}
assert_not_contains() {
  file=$1
  pattern=$2
  if grep -Eq "$pattern" "$file"; then
    fail "$file unexpectedly matches $pattern"
  fi
}

pass=0
fail_count=0
assert_success() {
  if "$@" >"$TMP_DIR/stdout" 2>"$TMP_DIR/stderr"; then pass=$((pass + 1)); else
    printf 'FAIL (expected success): %s\n' "$*" >&2; cat "$TMP_DIR/stderr" >&2; fail_count=$((fail_count + 1))
  fi
}
assert_failure() {
  if "$@" >"$TMP_DIR/stdout" 2>"$TMP_DIR/stderr"; then
    printf 'FAIL (expected failure): %s\n' "$*" >&2; fail_count=$((fail_count + 1))
  else pass=$((pass + 1)); fi
}

# --- Static checks on the checked-in compose file (no docker required) ---

[[ -f "$BROKER_COMPOSE" ]] || fail "missing $BROKER_COMPOSE"
assert_contains "$BROKER_COMPOSE" 'hostname: ieum-rabbitmq'
assert_contains "$BROKER_COMPOSE" '127\.0\.0\.1:15672:15672'
# Only an actual port-mapping list item (a quoted "- \"...\"" line under
# `ports:`) counts; the file's own prose comments mention "5672" by name.
assert_not_contains "$BROKER_COMPOSE" '^[[:space:]]*-[[:space:]]*"[^"]*:5672"'
assert_contains "$BROKER_COMPOSE" 'external: true'
assert_contains "$BROKER_COMPOSE" 'ieum-rabbitmq-data'

# Every port mapping under `ports:` must be loopback-only and none may
# publish 5672 (regression guard, independent of the two greps above).
port_lines=$(awk '
  /^[[:space:]]*ports:/ { capture=1; next }
  capture && /^[[:space:]]*-[[:space:]]*"/ { print; next }
  capture && /^[[:space:]]*[A-Za-z_]+:/ { exit }
' "$BROKER_COMPOSE")
[[ -n "$port_lines" ]] || fail "no port mappings found in $BROKER_COMPOSE"
while IFS= read -r port_line; do
  [[ "$port_line" =~ \"127\.0\.0\.1: ]] || fail "port mapping is not bound to 127.0.0.1: $port_line"
  [[ "$port_line" != *:5672* ]] || fail "port mapping publishes 5672 to the host: $port_line"
done <<<"$port_lines"

# Healthcheck regression guard: exec-form CMD has no shell, so a quoted
# operator token like "&&" is passed straight through as a positional
# argument instead of being interpreted — the previous bug in this file.
# CMD-SHELL is required whenever the test string uses a shell operator.
healthcheck_test_text=$(awk '
  /test:/ { capture=1 }
  capture { print }
  /interval:/ { exit }
' "$BROKER_COMPOSE")
[[ -n "$healthcheck_test_text" ]] || fail "no healthcheck test found in $BROKER_COMPOSE"
if ! printf '%s' "$healthcheck_test_text" | grep -Fq 'CMD-SHELL'; then
  if printf '%s' "$healthcheck_test_text" | grep -Eq '"(&&|\|\||;|\|)"'; then
    fail "healthcheck uses exec-form CMD with a shell operator token (needs CMD-SHELL): $healthcheck_test_text"
  fi
fi

# --- Behavioral checks via fake docker/openssl binaries ---

ETC_DIR="$TMP_DIR/etc-ieum"
BIN_DIR="$TMP_DIR/bin"
mkdir -p "$ETC_DIR" "$BIN_DIR"
chmod 700 "$ETC_DIR"

BROKER_ENV_FILE="$ETC_DIR/rabbitmq.env"
printf '%s\n' 'RABBITMQ_DEFAULT_VHOST=/ieum' 'RABBITMQ_DEFAULT_USER=ieum_broker_admin' \
  'RABBITMQ_DEFAULT_PASS=fixture-admin-password' 'RABBITMQ_IMAGE_DIGEST=rabbitmq@sha256:fixture' \
  >"$BROKER_ENV_FILE"
chmod 600 "$BROKER_ENV_FILE"

DOCKER_LOG="$TMP_DIR/docker.log"
: >"$DOCKER_LOG"

cat >"$BIN_DIR/docker" <<'EOF'
#!/usr/bin/env bash
set -u
printf '%s\n' "$*" >>"${FAKE_DOCKER_LOG:?}"
case "${1-}" in
  compose)
    shift
    sub=''
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --project-name|--file|--env-file) shift 2 ;;
        up) sub=up; shift ;;
        -d) shift ;;
        ps) sub=ps; shift ;;
        -q) shift ;;
        rabbitmq) shift ;;
        *) shift ;;
      esac
    done
    if [[ "$sub" == ps ]]; then printf 'fake-rabbitmq-cid\n'; fi
    exit 0
    ;;
  inspect)
    if [[ "${FAKE_DOCKER_HEALTH:-healthy}" == healthy ]]; then printf 'healthy\n'; else printf 'starting\n'; fi
    exit 0
    ;;
  exec)
    [[ "${FAKE_RABBITMQCTL_FAIL:-}" == 1 ]] && exit 1
    exit 0
    ;;
  *) exit 0 ;;
esac
EOF
chmod 700 "$BIN_DIR/docker"

cat >"$BIN_DIR/openssl" <<'EOF'
#!/usr/bin/env bash
set -u
if [[ "${1-}" == rand ]]; then printf 'deadbeefcafefixturehexpassword00\n'; exit 0; fi
exit 1
EOF
chmod 700 "$BIN_DIR/openssl"

run_provision() {
  IEUM_PROVISION_RABBITMQ_TEST_MODE=1 \
  IEUM_PROVISION_RABBITMQ_ETC_DIR="$ETC_DIR" \
  IEUM_PROVISION_RABBITMQ_COMPOSE_FILE="$BROKER_COMPOSE" \
  IEUM_PROVISION_RABBITMQ_BROKER_ENV_FILE="$BROKER_ENV_FILE" \
  IEUM_PROVISION_RABBITMQ_CREDENTIALS_FILE="$ETC_DIR/rabbitmq-app-credentials.env" \
  IEUM_PROVISION_RABBITMQ_DOCKER_BIN="$BIN_DIR/docker" \
  IEUM_PROVISION_RABBITMQ_OPENSSL_BIN="$BIN_DIR/openssl" \
  IEUM_PROVISION_RABBITMQ_EXPECTED_OWNER="$(id -u)" \
  FAKE_DOCKER_LOG="$DOCKER_LOG" \
  FAKE_DOCKER_HEALTH="${FAKE_DOCKER_HEALTH:-healthy}" \
  FAKE_RABBITMQCTL_FAIL="${FAKE_RABBITMQCTL_FAIL:-}" \
  "$SCRIPT" "$@"
}

: >"$DOCKER_LOG"
assert_success run_provision
grep -Fx 'ieum provision rabbitmq: PASS' "$TMP_DIR/stdout" >/dev/null || fail "missing success marker"

# project name must be the isolated ieum-broker project, never the release
# project `ieum` (which deploy-release.sh tears down with --remove-orphans).
grep -Fq -- '--project-name ieum-broker' "$DOCKER_LOG" || fail "compose was not invoked with project ieum-broker"
grep -Eq -- '--project-name ieum( |$)' "$DOCKER_LOG" && fail "compose was invoked against the release project ieum"

# guest must be deleted.
grep -Fq 'rabbitmqctl delete_user guest' "$DOCKER_LOG" || fail "guest user was not deleted"

# app accounts must be created/updated with no management tag anywhere.
grep -Fq 'rabbitmqctl add_user ieum_main' "$DOCKER_LOG" || fail "ieum_main was not created"
grep -Fq 'rabbitmqctl add_user ieum_ai' "$DOCKER_LOG" || fail "ieum_ai was not created"
grep -Fxq 'exec fake-rabbitmq-cid rabbitmqctl set_user_tags ieum_main' "$DOCKER_LOG" || fail "ieum_main tags were not explicitly cleared"
grep -Fxq 'exec fake-rabbitmq-cid rabbitmqctl set_user_tags ieum_ai' "$DOCKER_LOG" || fail "ieum_ai tags were not explicitly cleared"
if grep -Fq 'management' "$DOCKER_LOG" || grep -Fq 'administrator' "$DOCKER_LOG"; then
  fail "an app account was granted a management/administrator tag"
fi

# permissions must be scoped per spec.md §10.1.
grep -Fq 'set_permissions -p /ieum ieum_main ^ieum\.(ai|main)\..*$ ^ieum\.ai\.(jobs|retry|dlx)$ ^ieum\.main\..*$' "$DOCKER_LOG" \
  || fail "ieum_main permissions do not match spec.md §10.1"
grep -Fq 'set_permissions -p /ieum ieum_ai ^ieum\.(ai|main)\..*$ ^ieum\.ai\.(results|retry|dlx)$ ^ieum\.ai\..*$' "$DOCKER_LOG" \
  || fail "ieum_ai permissions do not match spec.md §10.1"

# memory/disk limits from spec.md §11.3.
grep -Fq 'set_vm_memory_high_watermark absolute 512MB' "$DOCKER_LOG" || fail "memory watermark was not set"
grep -Fq 'set_disk_free_limit absolute 5GB' "$DOCKER_LOG" || fail "disk free limit was not set"

# The broker admin password lives only in the env file passed by path
# (--env-file <path>), never as a literal argument, so it must never appear
# in an invocation log. (rabbitmqctl's own add_user/change_password CLI
# takes the app password as a positional argument — that argv exposure is
# inherent to the tool, not something this script can avoid; it is never
# written to this script's own stdout/stderr or to the repository.)
grep -Fq 'fixture-admin-password' "$DOCKER_LOG" && fail "broker admin password leaked into a logged command line"

# generated app credentials are written root-private, never printed.
generated="$ETC_DIR/rabbitmq-app-credentials.env"
[[ -f "$generated" ]] || fail "generated credentials file was not written"
[[ "$(stat -c '%a' "$generated" 2>/dev/null || stat -f '%Lp' "$generated")" == 600 ]] || fail "generated credentials file is not mode 0600"
grep -Fq 'IEUM_MAIN_RABBITMQ_PASSWORD=deadbeefcafefixturehexpassword00' "$generated" || fail "ieum_main password missing from generated credentials"
grep -Fq 'IEUM_AI_RABBITMQ_PASSWORD=deadbeefcafefixturehexpassword00' "$generated" || fail "ieum_ai password missing from generated credentials"
grep -Fq 'deadbeefcafefixturehexpassword00' "$TMP_DIR/stdout" && fail "generated password leaked into stdout"
grep -Fq 'deadbeefcafefixturehexpassword00' "$TMP_DIR/stderr" && fail "generated password leaked into stderr"

# --- Idempotency: re-running does not rotate an already-provisioned account ---
cat >"$BIN_DIR/docker" <<'EOF'
#!/usr/bin/env bash
set -u
printf '%s\n' "$*" >>"${FAKE_DOCKER_LOG:?}"
case "${1-}" in
  compose)
    shift
    sub=''
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --project-name|--file|--env-file) shift 2 ;;
        up) sub=up; shift ;;
        -d) shift ;;
        ps) sub=ps; shift ;;
        -q) shift ;;
        rabbitmq) shift ;;
        *) shift ;;
      esac
    done
    if [[ "$sub" == ps ]]; then printf 'fake-rabbitmq-cid\n'; fi
    exit 0
    ;;
  inspect)
    printf 'healthy\n'
    exit 0
    ;;
  exec)
    if [[ "${*}" == *'add_user'* ]]; then exit 1; fi
    exit 0
    ;;
  *) exit 0 ;;
esac
EOF
chmod 700 "$BIN_DIR/docker"
before_checksum=$(sha256sum "$generated" 2>/dev/null || shasum -a 256 "$generated")
: >"$DOCKER_LOG"
assert_success run_provision
after_checksum=$(sha256sum "$generated" 2>/dev/null || shasum -a 256 "$generated")
[[ "$before_checksum" == "$after_checksum" ]] || fail "credentials file was rewritten for an already-provisioned account"
grep -Fq 'rabbitmqctl change_password ieum_main' "$DOCKER_LOG" || fail "existing ieum_main was not reconciled via change_password"

# --- Failure: unsafe broker env file (world-readable) is rejected ---
chmod 644 "$BROKER_ENV_FILE"
assert_failure run_provision
chmod 600 "$BROKER_ENV_FILE"

# --- Failure: broker env file content must be real, not blank/placeholder ---
# compose up must never be reached with a blank or still-templated admin
# credential or image reference. run_provision() always points the script at
# the *shell variable* $BROKER_ENV_FILE (re-read on every call), so swap that
# variable's value directly rather than trying to override it through env-var
# prefixing (which would not survive the call into a nested shell function).
write_broker_env_variant() {
  local target=$1; shift
  printf '%s\n' "$@" >"$target"
  chmod 600 "$target"
}
assert_variant_rejected() {
  local label=$1
  : >"$DOCKER_LOG"
  assert_failure run_provision
  [[ -s "$DOCKER_LOG" ]] && fail "$label should fail before compose up is ever invoked"
}

variant_env="$ETC_DIR/rabbitmq-variant.env"
original_broker_env_file="$BROKER_ENV_FILE"
BROKER_ENV_FILE="$variant_env"

write_broker_env_variant "$variant_env" \
  'RABBITMQ_DEFAULT_VHOST=/ieum' 'RABBITMQ_DEFAULT_USER=' \
  'RABBITMQ_DEFAULT_PASS=fixture-admin-password' 'RABBITMQ_IMAGE_DIGEST=rabbitmq@sha256:fixture'
assert_variant_rejected "blank RABBITMQ_DEFAULT_USER"

write_broker_env_variant "$variant_env" \
  'RABBITMQ_DEFAULT_VHOST=/ieum' 'RABBITMQ_DEFAULT_USER=ieum_broker_admin' \
  'RABBITMQ_DEFAULT_PASS=' 'RABBITMQ_IMAGE_DIGEST=rabbitmq@sha256:fixture'
assert_variant_rejected "blank RABBITMQ_DEFAULT_PASS"

write_broker_env_variant "$variant_env" \
  'RABBITMQ_DEFAULT_VHOST=/ieum' 'RABBITMQ_DEFAULT_USER=ieum_broker_admin' \
  'RABBITMQ_DEFAULT_PASS=fixture-admin-password' 'RABBITMQ_IMAGE_DIGEST='
assert_variant_rejected "blank RABBITMQ_IMAGE_DIGEST"

write_broker_env_variant "$variant_env" \
  'RABBITMQ_DEFAULT_VHOST=/ieum' 'RABBITMQ_DEFAULT_USER=CHANGE_ME' \
  'RABBITMQ_DEFAULT_PASS=fixture-admin-password' 'RABBITMQ_IMAGE_DIGEST=rabbitmq@sha256:fixture'
assert_variant_rejected "placeholder RABBITMQ_DEFAULT_USER (CHANGE_ME)"

write_broker_env_variant "$variant_env" \
  'RABBITMQ_DEFAULT_VHOST=/ieum' 'RABBITMQ_DEFAULT_USER=ieum_broker_admin' \
  'RABBITMQ_DEFAULT_PASS=<fill-me-in>' 'RABBITMQ_IMAGE_DIGEST=rabbitmq@sha256:fixture'
assert_variant_rejected "placeholder RABBITMQ_DEFAULT_PASS (<...>)"

write_broker_env_variant "$variant_env" \
  'RABBITMQ_DEFAULT_VHOST=/ieum' 'RABBITMQ_DEFAULT_USER=ieum_broker_admin' \
  'RABBITMQ_DEFAULT_PASS=fixture-admin-password' 'RABBITMQ_IMAGE_DIGEST=<pending>'
assert_variant_rejected "placeholder RABBITMQ_IMAGE_DIGEST (<...>)"

# Missing key entirely (not just blank) must also fail.
write_broker_env_variant "$variant_env" \
  'RABBITMQ_DEFAULT_VHOST=/ieum' 'RABBITMQ_DEFAULT_USER=ieum_broker_admin' \
  'RABBITMQ_IMAGE_DIGEST=rabbitmq@sha256:fixture'
assert_variant_rejected "missing RABBITMQ_DEFAULT_PASS key"

# A fully valid variant (same shape as the main fixture) must still pass,
# proving the validator isn't just rejecting everything.
write_broker_env_variant "$variant_env" \
  'RABBITMQ_DEFAULT_VHOST=/ieum' 'RABBITMQ_DEFAULT_USER=ieum_broker_admin' \
  'RABBITMQ_DEFAULT_PASS=fixture-admin-password' 'RABBITMQ_IMAGE_DIGEST=rabbitmq@sha256:fixture'
: >"$DOCKER_LOG"
assert_success run_provision

BROKER_ENV_FILE="$original_broker_env_file"

printf 'passed=%d failed=%d\n' "$pass" "$fail_count"
test "$fail_count" -eq 0
