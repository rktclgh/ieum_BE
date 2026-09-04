#!/usr/bin/env bash
set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ONPREM_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
REPO_ROOT=$(CDPATH= cd -- "$ONPREM_DIR/../.." && pwd)
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

# Argument validator standing in for the real rabbitmqctl's own CLI parser,
# for exactly the subcommands this script issues. The real tool rejects a
# malformed invocation before touching the broker, so a fake that swallows
# every argv (the previous fixture) let `set_disk_free_limit absolute 5GB`
# — which the real rabbitmqctl refuses with "Error: too many arguments" —
# pass the suite while failing in production. Every fake `docker` below
# routes its `exec <cid> rabbitmqctl ...` through this.
RABBITMQCTL_VALIDATE="$BIN_DIR/rabbitmqctl-validate"
cat >"$RABBITMQCTL_VALIDATE" <<'EOF'
#!/usr/bin/env bash
set -u
usage_die() { printf 'Error: too many arguments.\nUsage: %s\n' "$1" >&2; exit 64; }
cmd=${1-}
shift 2>/dev/null || true
case "$cmd" in
  set_disk_free_limit)
    # Real usage: set_disk_free_limit <disk_limit>
    #          |  set_disk_free_limit mem_relative <fraction>
    # There is NO `absolute` keyword here — only set_vm_memory_high_watermark
    # takes one.
    if [[ "${1-}" == mem_relative ]]; then
      [[ $# -eq 2 ]] || usage_die 'set_disk_free_limit <disk_limit> | set_disk_free_limit mem_relative <fraction>'
    else
      [[ $# -eq 1 ]] || usage_die 'set_disk_free_limit <disk_limit> | set_disk_free_limit mem_relative <fraction>'
    fi
    ;;
  set_vm_memory_high_watermark)
    # Real usage: set_vm_memory_high_watermark <fraction>
    #          |  set_vm_memory_high_watermark absolute <memory_limit>
    if [[ "${1-}" == absolute ]]; then
      [[ $# -eq 2 ]] || usage_die 'set_vm_memory_high_watermark <fraction> | set_vm_memory_high_watermark absolute <memory_limit>'
    else
      [[ $# -eq 1 ]] || usage_die 'set_vm_memory_high_watermark <fraction> | set_vm_memory_high_watermark absolute <memory_limit>'
    fi
    ;;
  set_permissions)
    [[ "${1-}" == -p && $# -eq 6 ]] \
      || usage_die 'set_permissions [-p <vhost>] <username> <conf> <write> <read>'
    ;;
esac
exit 0
EOF
chmod 700 "$RABBITMQCTL_VALIDATE"

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
    if [[ "${3-}" == rabbitmqctl ]]; then "${FAKE_RABBITMQCTL_VALIDATE:?}" "${@:4}" || exit $?; fi
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
  FAKE_RABBITMQCTL_VALIDATE="$RABBITMQCTL_VALIDATE" \
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

# permissions must be scoped per spec.md §10.1. Both app-main
# (AiJobRabbitConfig + AiResultRabbitConfig) and app-ai
# (AiJobRabbitConfiguration) declare the FULL topology, and a `queue.declare`
# — passive or active — is checked against READ on the queue, exactly like
# `queue.bind` is (observed on the live broker 2026-09-04: ieum_ai's
# queue.declare of ieum.ai.question-answer.dispatch.retry was refused 403
# "read access to queue ... refused"). So configure/write/read must ALL cover
# the whole namespace for both accounts.
# Every exchange and queue either app declares must match all three regexes
# actually handed to set_permissions — not just the two strings above. The
# names come from AiJobTopology.java (the shared SSOT); the hard-coded list
# is the fallback and is asserted to have the expected size either way.
TOPOLOGY_JAVA="$REPO_ROOT/common/src/main/java/shinhan/fibri/ieum/common/ai/job/AiJobTopology.java"
topology_names=()
if [[ -f "$TOPOLOGY_JAVA" ]]; then
  while IFS= read -r topology_name; do
    topology_names+=("$topology_name")
  done < <(grep -oE '"ieum\.[A-Za-z0-9.-]+"' "$TOPOLOGY_JAVA" | tr -d '"' | sort -u)
fi
if [[ ${#topology_names[@]} -eq 0 ]]; then
  # Fallback — keep in sync with
  # common/src/main/java/shinhan/fibri/ieum/common/ai/job/AiJobTopology.java
  topology_names=(
    'ieum.ai.jobs' 'ieum.ai.results' 'ieum.ai.retry' 'ieum.ai.dlx'
    'ieum.ai.question-answer.dispatch' 'ieum.ai.question-answer.dispatch.retry' 'ieum.ai.question-answer.dispatch.dlq'
    'ieum.ai.accepted-answer.ingest' 'ieum.ai.accepted-answer.ingest.retry' 'ieum.ai.accepted-answer.ingest.dlq'
    'ieum.main.question-answer.completed' 'ieum.main.question-answer.completed.retry' 'ieum.main.question-answer.completed.dlq'
  )
fi
[[ ${#topology_names[@]} -eq 13 ]] \
  || fail "expected 13 topology names (4 exchanges + 9 queues), found ${#topology_names[@]}"

assert_permissions_cover_topology() {
  user=$1
  perm_line=$(grep -E "rabbitmqctl set_permissions -p /ieum ${user} " "$DOCKER_LOG" | tail -n 1)
  [[ -n "$perm_line" ]] || fail "no set_permissions call logged for $user"
  # `exec <cid> rabbitmqctl set_permissions -p <vhost> <user> <conf> <write> <read>`
  set -- $perm_line
  [[ $# -eq 10 ]] || fail "unexpected set_permissions argv for $user: $perm_line"
  conf_re=$8
  write_re=$9
  read_re=${10}
  for topology_name in "${topology_names[@]}"; do
    [[ "$topology_name" =~ $conf_re ]] \
      || fail "$user configure regex ($conf_re) does not cover $topology_name"
    [[ "$topology_name" =~ $write_re ]] \
      || fail "$user write regex ($write_re) does not cover $topology_name"
    [[ "$topology_name" =~ $read_re ]] \
      || fail "$user read regex ($read_re) does not cover $topology_name"
  done
}
assert_permissions_cover_topology ieum_main
assert_permissions_cover_topology ieum_ai

grep -Fq 'set_permissions -p /ieum ieum_main ^ieum\.(ai|main)\..*$ ^ieum\.(ai|main)\..*$ ^ieum\.(ai|main)\..*$' "$DOCKER_LOG" \
  || fail "ieum_main permissions do not match spec.md §10.1"
grep -Fq 'set_permissions -p /ieum ieum_ai ^ieum\.(ai|main)\..*$ ^ieum\.(ai|main)\..*$ ^ieum\.(ai|main)\..*$' "$DOCKER_LOG" \
  || fail "ieum_ai permissions do not match spec.md §10.1"

# memory/disk limits from spec.md §11.3. `set_vm_memory_high_watermark` takes
# the `absolute` keyword; `set_disk_free_limit` does not — passing it there is
# rejected by rabbitmqctl as "too many arguments".
grep -Fq 'set_vm_memory_high_watermark absolute 512MB' "$DOCKER_LOG" || fail "memory watermark was not set"
grep -Eq 'set_disk_free_limit 5GB$' "$DOCKER_LOG" || fail "disk free limit was not set"
grep -Fq 'set_disk_free_limit absolute' "$DOCKER_LOG" \
  && fail "set_disk_free_limit was called with the bogus 'absolute' keyword (rabbitmqctl rejects it)"

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

# --- Idempotency: re-running against a broker that already has both
# accounts must not rotate their passwords. Probing existence via
# `list_users` and skipping add_user/change_password entirely for an
# account that is already there is the only safe behavior — calling
# change_password with a freshly generated password (the previous bug)
# silently changes the broker-side credential to a value that is never
# recorded anywhere, locking the app out on its next connection attempt
# (finding C2). ---
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
    if [[ "${3-}" == rabbitmqctl ]]; then "${FAKE_RABBITMQCTL_VALIDATE:?}" "${@:4}" || exit $?; fi
    if [[ "${*}" == *'list_users'* ]]; then
      printf 'ieum_main\t[]\n'
      printf 'ieum_ai\t[]\n'
      exit 0
    fi
    if [[ "${*}" == *'add_user'* || "${*}" == *'change_password'* ]]; then
      # An already-provisioned account must never reach either call.
      exit 1
    fi
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
grep -Fq 'add_user' "$DOCKER_LOG" && fail "add_user was called for an already-provisioned account"
grep -Fq 'change_password' "$DOCKER_LOG" && fail "change_password was called for an already-provisioned account (rotates the broker password without recording it — finding C2)"
grep -Fq 'list_users' "$DOCKER_LOG" || fail "existing accounts were not probed via list_users before reconciling"

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

# --- Finding 7: a broker op that fails AFTER add_user for the second
# account must not lose the first account's already-generated password.
# The old code batched every credential write into one call at the very
# end of main() — if set_permissions for ieum_ai (the second account
# ensure_user'd) died the whole script (set -Eeuo pipefail), that batched
# writer never ran and ieum_main's freshly generated password — already
# live on the broker via add_user — was recorded nowhere. A retry then
# sees ieum_main via list_users and skips add_user/change_password for it
# forever (finding C2's own fix), permanently losing that credential.
# Persisting immediately after each add_user call fixes this. ---
rm -f "$generated"
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
    if [[ "${3-}" == rabbitmqctl ]]; then "${FAKE_RABBITMQCTL_VALIDATE:?}" "${@:4}" || exit $?; fi
    if [[ "${*}" == *'list_users'* ]]; then
      # Fresh broker: neither account exists yet.
      exit 0
    fi
    if [[ "${*}" == *'set_permissions'* && "${*}" == *' ieum_ai '* ]]; then
      # The failure point: ieum_ai's add_user has already succeeded (and
      # its password already persisted) by the time this runs.
      exit 1
    fi
    exit 0
    ;;
  *) exit 0 ;;
esac
EOF
chmod 700 "$BIN_DIR/docker"
: >"$DOCKER_LOG"
assert_failure run_provision
grep -Fq 'add_user ieum_main' "$DOCKER_LOG" || fail "ieum_main add_user was not attempted before the failure"
grep -Fq 'add_user ieum_ai' "$DOCKER_LOG" || fail "ieum_ai add_user was not attempted before the failure"
[[ -f "$generated" ]] || fail "ieum_main's credential was lost when a later broker op failed (finding 7)"
grep -Fq 'IEUM_MAIN_RABBITMQ_PASSWORD=deadbeefcafefixturehexpassword00' "$generated" \
  || fail "ieum_main password missing from generated credentials after a later failure (finding 7)"
[[ "$(stat -c '%a' "$generated" 2>/dev/null || stat -f '%Lp' "$generated")" == 600 ]] \
  || fail "credentials file written mid-failure is not mode 0600"

printf 'passed=%d failed=%d\n' "$pass" "$fail_count"
test "$fail_count" -eq 0
