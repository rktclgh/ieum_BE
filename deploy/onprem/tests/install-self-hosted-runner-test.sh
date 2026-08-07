#!/usr/bin/env bash
set -u
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
HELPER="$SCRIPT_DIR/../scripts/install-self-hosted-runner.sh"
# The helper intentionally rejects a world-writable parent chain. GitHub's
# hosted Linux runner leaves TMPDIR unset, so /tmp is not a valid fixture root.
TMP_DIR=$(mktemp -d "$SCRIPT_DIR/.ieum-runner-test.XXXXXX"); trap 'rm -rf "$TMP_DIR"' EXIT
RUNNER_ROOT="$TMP_DIR/runners"; SERVICE_ROOT="$TMP_DIR/systemd"; BIN_ROOT="$TMP_DIR/bin"; mkdir -p "$RUNNER_ROOT" "$SERVICE_ROOT" "$BIN_ROOT"
RUNNER_HOME="$TMP_DIR/ieum-runner-home"; mkdir -p "$RUNNER_HOME"
TOKEN_FILE="$TMP_DIR/registration.token"; printf 'test-registration-token\n' >"$TOKEN_FILE"; chmod 600 "$TOKEN_FILE"
ARCHIVE_DIR="$TMP_DIR/archive"; mkdir -p "$ARCHIVE_DIR/bin"
printf '%s\n' '#!/usr/bin/env bash' 'printf "%s\\n" "$*" >>"${FAKE_CONFIG_LOG:?}"' '[[ "${FAKE_CONFIG_FAIL:-}" == 1 ]] && exit 1' 'exit 0' >"$ARCHIVE_DIR/config.sh"
printf '%s\n' '#!/usr/bin/env bash' 'printf "%s\\n" "$*" >>"${FAKE_SVC_LOG:?}"' 'exit 0' >"$ARCHIVE_DIR/svc.sh"
printf '%s\n' '#!/usr/bin/env bash' 'exit 0' >"$ARCHIVE_DIR/bin/runsvc.sh"
chmod 700 "$ARCHIVE_DIR/config.sh" "$ARCHIVE_DIR/svc.sh" "$ARCHIVE_DIR/bin/runsvc.sh"; tar -czf "$RUNNER_ROOT/actions-runner.tar.gz" -C "$ARCHIVE_DIR" config.sh bin/runsvc.sh; chmod 600 "$RUNNER_ROOT/actions-runner.tar.gz"
for name in getent systemctl runuser chown; do
  if [[ "$name" == getent ]]; then
    printf '%s\n' '#!/usr/bin/env bash' 'if [[ "${1-}" == passwd ]]; then' '  printf "ieum-runner:x:%s:%s::%s:/bin/bash\\n" "${FAKE_UID:-1001}" "${FAKE_GID:-1001}" "${FAKE_RUNNER_HOME:?}"' '  [[ -z "${2-}" && "${FAKE_SHARED_GID_USER:-}" == 1 ]] && printf "other:x:1002:%s::/home/other:/bin/bash\\n" "${FAKE_GID:-1001}"' '  exit 0' 'fi' 'if [[ "${1-}" == group && "${2-}" == ieum-runner ]]; then' '  case "${FAKE_RUNNER_GROUP:-private}" in' '    private) printf "ieum-runner:x:%s:\\n" "${FAKE_GID:-1001}" ;;' '    shared) printf "users:x:%s:other-account\\n" "${FAKE_GID:-1001}" ;;' '    wrong-gid) printf "ieum-runner:x:1002:\\n" ;;' '    missing) exit 2 ;;' '  esac' '  exit 0' 'fi' 'exit 2' >"$BIN_ROOT/$name"
  elif [[ "$name" == systemctl ]]; then
    printf '%s\n' '#!/usr/bin/env bash' 'printf "%s\\n" "$*" >>"${FAKE_USER_LOG:?}"' '[[ "${FAKE_SYSTEMCTL_FAIL_START:-}" == 1 && "${1-}" == start ]] && exit 1' '[[ "${FAKE_SYSTEMCTL_FAIL_DISABLE:-}" == 1 && "${1-}" == disable ]] && exit 1' 'exit 0' >"$BIN_ROOT/$name"
  else
    printf '%s\n' '#!/usr/bin/env bash' 'printf "%s\\n" "$*" >>"${FAKE_USER_LOG:?}"' >"$BIN_ROOT/$name"
  fi
  chmod 700 "$BIN_ROOT/$name"
done
printf '%s\n' '#!/usr/bin/env bash' 'if [[ "$1" == -u ]]; then shift 2; fi' 'if [[ "$1" == -- ]]; then shift; fi' 'exec "$@"' >"$BIN_ROOT/runuser"; chmod 700 "$BIN_ROOT/runuser"
printf '%s\n' '#!/usr/bin/env bash' 'printf "chown %s\\n" "$*" >>"${FAKE_USER_LOG:?}"' 'exit 0' >"$BIN_ROOT/chown"; chmod 700 "$BIN_ROOT/chown"
printf '%s\n' '#!/usr/bin/env bash' 'if [[ "${FAKE_ID_GROUPS:-}" == privileged ]]; then printf "users sudo\\n"; else printf "users\\n"; fi' >"$BIN_ROOT/id"; chmod 700 "$BIN_ROOT/id"
ln -s "$(command -v tar)" "$BIN_ROOT/tar"
run() {
  IEUM_RUNNER_TEST_MODE=1 IEUM_RUNNER_ROOT="$RUNNER_ROOT" IEUM_RUNNER_ARCHIVE="$RUNNER_ROOT/actions-runner.tar.gz" IEUM_RUNNER_SERVICE_ROOT="$SERVICE_ROOT" IEUM_RUNNER_EXPECTED_OWNER="$(id -un)" IEUM_RUNNER_GETENT_BIN="$BIN_ROOT/getent" IEUM_RUNNER_TAR_BIN="$BIN_ROOT/tar" IEUM_RUNNER_RUNUSER_BIN="$BIN_ROOT/runuser" IEUM_RUNNER_CHOWN_BIN="$BIN_ROOT/chown" IEUM_RUNNER_ID_BIN="$BIN_ROOT/id" IEUM_RUNNER_SYSTEMCTL_BIN="$BIN_ROOT/systemctl" FAKE_CONFIG_FAIL="${FAKE_CONFIG_FAIL:-}" FAKE_SYSTEMCTL_FAIL_START="${FAKE_SYSTEMCTL_FAIL_START:-}" FAKE_SYSTEMCTL_FAIL_DISABLE="${FAKE_SYSTEMCTL_FAIL_DISABLE:-}" FAKE_UID="${FAKE_UID:-}" FAKE_GID="${FAKE_GID:-}" FAKE_ID_GROUPS="${FAKE_ID_GROUPS:-}" FAKE_RUNNER_GROUP="${FAKE_RUNNER_GROUP:-private}" FAKE_SHARED_GID_USER="${FAKE_SHARED_GID_USER:-}" FAKE_RUNNER_HOME="$RUNNER_HOME" FAKE_CONFIG_LOG="$TMP_DIR/config.log" FAKE_SVC_LOG="$TMP_DIR/svc.log" FAKE_USER_LOG="$TMP_DIR/user.log" "$HELPER" "$@"
}
pass=0; fail=0
if run --token-file "$TOKEN_FILE" >/dev/null 2>"$TMP_DIR/err"; then pass=$((pass+1)); else cat "$TMP_DIR/err" >&2; fail=$((fail+1)); fi
[[ ! -e "$TOKEN_FILE" ]] && pass=$((pass+1)) || fail=$((fail+1))
grep -Fq -- '--url https://github.com/rktclgh/ieum_BE --name song-server-ieum-prod-01 --labels ieum-prod-deploy' "$TMP_DIR/config.log" && pass=$((pass+1)) || fail=$((fail+1))
unit="$SERVICE_ROOT/actions.runner.rktclgh-ieum_BE.song-server-ieum-prod-01.service"
[[ -f "$unit" ]] && grep -Fq 'User=ieum-runner' "$unit" && grep -Fq 'WorkingDirectory=' "$unit" && grep -Fq 'ExecStart=' "$unit" && [[ "$(stat -c '%a' "$unit" 2>/dev/null || stat -f '%Lp' "$unit")" == 644 ]] && pass=$((pass+1)) || fail=$((fail+1))
[[ "$(stat -c '%a' "$RUNNER_ROOT" 2>/dev/null || stat -f '%Lp' "$RUNNER_ROOT")" == 750 ]] && pass=$((pass+1)) || fail=$((fail+1))
grep -Fqx "chown root:1001 $RUNNER_ROOT" "$TMP_DIR/user.log" && pass=$((pass+1)) || fail=$((fail+1))
grep -Fqx 'daemon-reload' "$TMP_DIR/user.log" && grep -Fqx 'enable actions.runner.rktclgh-ieum_BE.song-server-ieum-prod-01.service' "$TMP_DIR/user.log" && grep -Fqx 'start actions.runner.rktclgh-ieum_BE.song-server-ieum-prod-01.service' "$TMP_DIR/user.log" && pass=$((pass+1)) || fail=$((fail+1))
rm -f "$unit"; chmod 755 "$RUNNER_ROOT"
TOKEN_FILE="$TMP_DIR/failing.token"; printf 'keep-me\n' >"$TOKEN_FILE"; chmod 600 "$TOKEN_FILE"; FAKE_CONFIG_FAIL=1
if run --token-file "$TOKEN_FILE" >/dev/null 2>&1; then fail=$((fail+1)); else pass=$((pass+1)); fi
[[ -e "$TOKEN_FILE" && "$(stat -c '%a' "$RUNNER_ROOT" 2>/dev/null || stat -f '%Lp' "$RUNNER_ROOT")" == 755 ]] && pass=$((pass+1)) || fail=$((fail+1)); unset FAKE_CONFIG_FAIL
TOKEN_FILE="$TMP_DIR/cleanup-warning.token"; printf 'keep-me\n' >"$TOKEN_FILE"; chmod 600 "$TOKEN_FILE"; FAKE_CONFIG_FAIL=1; FAKE_SYSTEMCTL_FAIL_DISABLE=1
if run --token-file "$TOKEN_FILE" >"$TMP_DIR/stdout" 2>"$TMP_DIR/err"; then fail=$((fail+1)); else pass=$((pass+1)); fi
grep -Fq 'cleanup incomplete; manual root intervention is required' "$TMP_DIR/err" && pass=$((pass+1)) || fail=$((fail+1))
[[ -e "$TOKEN_FILE" && "$(stat -c '%a' "$RUNNER_ROOT" 2>/dev/null || stat -f '%Lp' "$RUNNER_ROOT")" == 755 ]] && pass=$((pass+1)) || fail=$((fail+1)); unset FAKE_CONFIG_FAIL FAKE_SYSTEMCTL_FAIL_DISABLE
if run --token forbidden >/dev/null 2>&1; then fail=$((fail+1)); else pass=$((pass+1)); fi
TOKEN_FILE="$TMP_DIR/root-uid.token"; printf 'keep-me\n' >"$TOKEN_FILE"; chmod 600 "$TOKEN_FILE"; FAKE_UID=0
if run --token-file "$TOKEN_FILE" >/dev/null 2>&1; then fail=$((fail+1)); else pass=$((pass+1)); fi
[[ -e "$TOKEN_FILE" ]] && pass=$((pass+1)) || fail=$((fail+1)); unset FAKE_UID
TOKEN_FILE="$TMP_DIR/privileged-group.token"; printf 'keep-me\n' >"$TOKEN_FILE"; chmod 600 "$TOKEN_FILE"; FAKE_ID_GROUPS=privileged
if run --token-file "$TOKEN_FILE" >/dev/null 2>&1; then fail=$((fail+1)); else pass=$((pass+1)); fi
[[ -e "$TOKEN_FILE" ]] && pass=$((pass+1)) || fail=$((fail+1)); unset FAKE_ID_GROUPS
TOKEN_FILE="$TMP_DIR/shared-primary-group.token"; printf 'keep-me\n' >"$TOKEN_FILE"; chmod 600 "$TOKEN_FILE"; FAKE_RUNNER_GROUP=shared
if run --token-file "$TOKEN_FILE" >/dev/null 2>&1; then fail=$((fail+1)); else pass=$((pass+1)); fi
[[ -e "$TOKEN_FILE" ]] && pass=$((pass+1)) || fail=$((fail+1)); unset FAKE_RUNNER_GROUP
TOKEN_FILE="$TMP_DIR/shared-gid.token"; printf 'keep-me\n' >"$TOKEN_FILE"; chmod 600 "$TOKEN_FILE"; FAKE_SHARED_GID_USER=1
if run --token-file "$TOKEN_FILE" >/dev/null 2>&1; then fail=$((fail+1)); else pass=$((pass+1)); fi
[[ -e "$TOKEN_FILE" ]] && pass=$((pass+1)) || fail=$((fail+1)); unset FAKE_SHARED_GID_USER
rm -f "$unit"
TOKEN_FILE="$TMP_DIR/start-failure.token"; printf 'keep-me\n' >"$TOKEN_FILE"; chmod 600 "$TOKEN_FILE"; FAKE_SYSTEMCTL_FAIL_START=1
if run --token-file "$TOKEN_FILE" >/dev/null 2>&1; then fail=$((fail+1)); else pass=$((pass+1)); fi
retained_runner_dir=''
[[ ! -e "$TOKEN_FILE" && -f "$unit" ]] && retained_runner_dir="$(sed -n 's/^WorkingDirectory=//p' "$unit")" && [[ -d "$retained_runner_dir" ]] && pass=$((pass+1)) || fail=$((fail+1)); unset FAKE_SYSTEMCTL_FAIL_START
rm -f "$unit"; [[ -n "$retained_runner_dir" ]] && rm -rf "$retained_runner_dir"
TOKEN_FILE="$TMP_DIR/existing-unit.token"; printf 'keep-me\n' >"$TOKEN_FILE"; chmod 600 "$TOKEN_FILE"; : >"$unit"; config_lines_before="$(wc -l <"$TMP_DIR/config.log")"
if run --token-file "$TOKEN_FILE" >/dev/null 2>&1; then fail=$((fail+1)); else pass=$((pass+1)); fi
[[ -e "$TOKEN_FILE" ]] && [[ "$(wc -l <"$TMP_DIR/config.log")" == "$config_lines_before" ]] && pass=$((pass+1)) || fail=$((fail+1))
printf 'pass=%d fail=%d\n' "$pass" "$fail"; (( fail == 0 ))
