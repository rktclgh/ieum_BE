#!/usr/bin/env bash
set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DISPATCHER="$SCRIPT_DIR/../scripts/ieum-release-dispatch.sh"
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ieum-dispatch-test.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT

FAKE_HELPER="$TMP_DIR/fake-helper"
LOG="$TMP_DIR/argv.log"
OUTPUT="$TMP_DIR/output"
cat >"$FAKE_HELPER" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'helper' >"${IEUM_DISPATCH_LOG:?}"
for arg in "$@"; do printf '\t%s' "$arg" >>"${IEUM_DISPATCH_LOG:?}"; done
printf '\n' >>"${IEUM_DISPATCH_LOG:?}"
case "${1-}" in
  current) printf '%s\n' '{"release_id":null}' ;;
  apply)
    printf 'apply-stdin:'
    while IFS= read -r -n 1 byte; do printf '%s' "$byte"; done
    printf '\n'
    ;;
  *) exit 91 ;;
esac
EOF
chmod 700 "$FAKE_HELPER"

pass=0
fail=0
assert_success() {
  if "$@" >"$OUTPUT" 2>"$TMP_DIR/stderr"; then pass=$((pass + 1)); else
    printf 'FAIL (expected success): %s\n' "$*" >&2
    cat "$TMP_DIR/stderr" >&2
    fail=$((fail + 1))
  fi
}
assert_failure() {
  if "$@" >"$OUTPUT" 2>"$TMP_DIR/stderr"; then
    printf 'FAIL (expected failure): %s\n' "$*" >&2
    fail=$((fail + 1))
  else pass=$((pass + 1)); fi
}
run_dispatch() {
  IEUM_RELEASE_DISPATCH_TEST_MODE=1 \
  IEUM_RELEASE_DISPATCH_TEST_ASSUME_ROOT=1 \
  IEUM_RELEASE_DISPATCH_ROOT_HELPER="$FAKE_HELPER" \
  IEUM_DISPATCH_LOG="$LOG" \
  "$DISPATCHER" "$@"
}
run_unprivileged_dispatch() {
  IEUM_RELEASE_DISPATCH_TEST_MODE=1 \
  IEUM_RELEASE_DISPATCH_ROOT_HELPER="$FAKE_HELPER" \
  IEUM_DISPATCH_LOG="$LOG" \
  "$DISPATCHER" "$@"
}

release_id='r-29428455417-1-0123456789abcdef0123456789abcdef01234567'
bundle_sha='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'

assert_success run_dispatch --local current --json
expected_current_log=$(printf 'helper\tcurrent\t--json')
grep -Fqx "$expected_current_log" "$LOG" || { printf 'FAIL (current argv mismatch)\n' >&2; fail=$((fail + 1)); }
grep -Fqx '{"release_id":null}' "$OUTPUT" || { printf 'FAIL (current output mismatch)\n' >&2; fail=$((fail + 1)); }

printf 'release-envelope-bytes' >"$TMP_DIR/apply-input"
assert_success run_dispatch --local apply --release-id "$release_id" --expected-current none --bundle-sha256 "$bundle_sha" <"$TMP_DIR/apply-input"
expected_apply_log=$(printf 'helper\tapply\t--release-id\t%s\t--expected-current\tnone\t--bundle-sha256\t%s' "$release_id" "$bundle_sha")
grep -Fqx "$expected_apply_log" "$LOG" || { printf 'FAIL (apply argv mismatch)\n' >&2; fail=$((fail + 1)); }
grep -Fqx 'apply-stdin:release-envelope-bytes' "$OUTPUT" || { printf 'FAIL (apply stdin mismatch)\n' >&2; fail=$((fail + 1)); }

assert_failure run_dispatch --local rollback --expected-current "$release_id"
grep -Fqx 'ieum release dispatch: local rollback is not supported' "$TMP_DIR/stderr" || {
  printf 'FAIL (local rollback was not explicitly rejected)\n' >&2
  fail=$((fail + 1))
}

for bad in \
  '--local current --json extra' \
  "--local apply --release-id bad --expected-current none --bundle-sha256 $bundle_sha" \
  "--local apply --release-id $release_id --expected-current ../other --bundle-sha256 $bundle_sha" \
  "--local apply --release-id $release_id --expected-current none --bundle-sha256 $bundle_sha extra" \
  "--local apply --release-id \$(touch $TMP_DIR/evaluated) --expected-current none --bundle-sha256 $bundle_sha" \
  '--local rollback --expected-current none'; do
  # shellcheck disable=SC2086
  assert_failure run_dispatch $bad
done
[[ ! -e "$TMP_DIR/evaluated" ]] || { printf 'FAIL (command substitution evaluated)\n' >&2; fail=$((fail + 1)); }

assert_failure run_unprivileged_dispatch --local current --json
grep -Fqx 'ieum release dispatch: local mode requires root' "$TMP_DIR/stderr" || {
  printf 'FAIL (unprivileged invocation was not rejected)\n' >&2
  fail=$((fail + 1))
}

assert_failure run_dispatch current --json
assert_failure run_dispatch --local

printf 'passed=%d failed=%d\n' "$pass" "$fail"
test "$fail" -eq 0
