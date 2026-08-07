#!/usr/bin/env bash
set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
HELPER="$SCRIPT_DIR/../scripts/bootstrap-control-plane.sh"
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ieum-bootstrap.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT

SOURCE_ROOT="$TMP_DIR/source"
INSTALL_ROOT="$TMP_DIR/usr-local-sbin"
SRV_ROOT="$TMP_DIR/srv-ieum"
STATE_ROOT="$TMP_DIR/var-lib-ieum"
ETC_ROOT="$TMP_DIR/etc-ieum"
BIN_ROOT="$TMP_DIR/bin"
SUDOERS_ROOT="$TMP_DIR/sudoers"
RUNNER_HOME="$TMP_DIR/runner-home"
VISUDO_LOG="$TMP_DIR/visudo.log"
mkdir -p "$SOURCE_ROOT/deploy/onprem/scripts" "$INSTALL_ROOT" "$SRV_ROOT" \
  "$STATE_ROOT" "$ETC_ROOT" "$BIN_ROOT" "$SUDOERS_ROOT" "$RUNNER_HOME"

for name in deploy-release.sh db-preflight.sh install-staging-nginx.sh install-production-nginx.sh object-store-mirror.sh ieum-release-dispatch.sh db-restore-rehearsal.sh db-restore-production.sh db-verify.sh provision-existing-postgres.sh provision-runtime-env.sh validate-runtime-env.sh install-self-hosted-runner.sh; do
  printf '#!/usr/bin/env bash\nexit 0\n' >"$SOURCE_ROOT/deploy/onprem/scripts/$name"
  chmod 700 "$SOURCE_ROOT/deploy/onprem/scripts/$name"
done

for name in docker curl openssl python3 systemctl nginx psql pg_restore sudo visudo; do
  path="$BIN_ROOT/$name"
  cat >"$path" <<'EOF'
#!/usr/bin/env bash
if [[ "${1-}" == --check-only ]]; then exit 0; fi
if [[ "${1-}" == -c* ]]; then
  printf '%s\n' "$*" >>"${VISUDO_LOG:?}"
fi
exit 0
EOF
  chmod 700 "$path"
done
cat >"$BIN_ROOT/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1-}" == compose && "${2-}" == version && "${FAKE_DOCKER_COMPOSE:-present}" == missing ]]; then exit 1; fi
printf '%s\n' "$*" >>"${FAKE_DOCKER_LOG:?}"
if [[ "${1-}" == network && "${2-}" == inspect ]]; then
  network=${3-}
  if [[ "${network}" == collision-network && "${FAKE_DOCKER_NETWORK_INSPECT_FAIL:-}" == 1 ]]; then
    exit 1
  fi
  if [[ "$network" == ieum-minio && "${FAKE_DOCKER_MINIO:-present}" == missing ]]; then exit 1; fi
  if [[ "$network" == ieum-minio && "${4-}" == --format ]]; then
    printf '/minio-container\n'
  fi
  if [[ "$network" == ieum && "${FAKE_DOCKER_IEUM:-present}" == missing ]]; then exit 1; fi
  if [[ "$network" == ieum && "${4-}" == --format ]]; then
    [[ "${FAKE_DOCKER_IEUM:-present}" == invalid ]] && printf 'bridge|172.31.0.0/24\n' || printf 'bridge|172.30.0.0/24\n'
  fi
  exit 0
fi
if [[ "${1-}" == network && "${2-}" == ls ]]; then
  [[ "${FAKE_DOCKER_NETWORK_ENUM:-}" == collision ]] && printf 'collision-network\n'
  exit 0
fi
if [[ "${1-}" == network && "${2-}" == create ]]; then printf '%s\n' "$*" >>"${FAKE_DOCKER_LOG:?}"; exit 0; fi
if [[ "${1-}" == inspect ]]; then
  if [[ "$*" == *NetworkSettings* ]]; then
    if [[ "${FAKE_DOCKER_MINIO:-present}" == missing-alias ]]; then printf 'other\n'; else printf 'minio\n'; fi
  elif [[ "${FAKE_DOCKER_MINIO:-present}" == unhealthy ]]; then printf 'false\n'; else printf 'true\n'; fi
  exit 0
fi
if [[ "${1-}" == exec ]]; then
  [[ "${FAKE_DOCKER_MINIO:-present}" == unreachable ]] && exit 1
  exit 0
fi
exit 0
EOF
chmod 700 "$BIN_ROOT/docker"
cat >"$BIN_ROOT/id" <<'EOF'
#!/usr/bin/env bash
if [[ "${1-}" == -nG ]]; then
  [[ "${FAKE_ID_GROUPS:-}" == fail ]] && exit 1
  [[ "${FAKE_ID_GROUPS:-}" == docker ]] && printf 'users docker\n' || printf 'users\n'
  [[ "${FAKE_ID_GROUPS:-}" == sudo ]] && printf 'users sudo\n'
  exit 0
fi
exit 1
EOF
chmod 700 "$BIN_ROOT/id"
cat >"$BIN_ROOT/getent" <<'EOF'
#!/usr/bin/env bash
if [[ "${1-}" == passwd && "${2-}" == ieum-runner && -f "${FAKE_RUNNER_CREATED:?}" ]]; then
  printf 'ieum-runner:x:1003:1003::%s:/bin/bash\n' "${FAKE_RUNNER_HOME:?}"
  exit 0
fi
if [[ "${1-}" == group && "${2-}" == ieum-runner && -f "${FAKE_RUNNER_CREATED:?}" ]]; then
  case "${FAKE_RUNNER_GROUP:-private}" in
    private) printf 'ieum-runner:x:1003:\n' ;;
    shared) printf 'users:x:1003:other-account\n' ;;
    wrong-gid) printf 'ieum-runner:x:1004:\n' ;;
    missing) exit 2 ;;
    *) exit 2 ;;
  esac
  exit 0
fi
exit 2
EOF
chmod 700 "$BIN_ROOT/getent"
for name in useradd passwd; do
  cat >"$BIN_ROOT/$name" <<'EOF'
#!/usr/bin/env bash
printf '%s %s\n' "$(basename "$0")" "$*" >>"${FAKE_USER_LOG:?}"
if [[ "$(basename "$0")" == useradd ]]; then
  account=${!#}
  if [[ "$account" == ieum-runner ]]; then : >"${FAKE_RUNNER_CREATED:?}"; mkdir -p "${FAKE_RUNNER_HOME:?}"; fi
fi
exit 0
EOF
  chmod 700 "$BIN_ROOT/$name"
done

( cd "$SOURCE_ROOT" && sha256sum deploy/onprem/scripts/*.sh > .ieum-source.sha256 )
chmod 600 "$SOURCE_ROOT/.ieum-source.sha256"

run_bootstrap() {
  IEUM_BOOTSTRAP_TEST_MODE=1 \
  IEUM_BOOTSTRAP_SOURCE_ROOT="$SOURCE_ROOT" \
  IEUM_BOOTSTRAP_SOURCE_CHECKSUM="$SOURCE_ROOT/.ieum-source.sha256" \
  IEUM_BOOTSTRAP_INSTALL_ROOT="$INSTALL_ROOT" \
  IEUM_BOOTSTRAP_SRV_ROOT="$SRV_ROOT" \
  IEUM_BOOTSTRAP_STATE_ROOT="$STATE_ROOT" \
  IEUM_BOOTSTRAP_ETC_ROOT="$ETC_ROOT" \
  IEUM_BOOTSTRAP_BIN_ROOT="$BIN_ROOT" \
  IEUM_BOOTSTRAP_ID_BIN="$BIN_ROOT/id" \
  IEUM_BOOTSTRAP_GETENT_BIN="$BIN_ROOT/getent" \
  IEUM_BOOTSTRAP_EXPECTED_OWNER="${IEUM_BOOTSTRAP_EXPECTED_OWNER:-$(id -un)}" \
  IEUM_BOOTSTRAP_AUTH_OWNER="$(id -un)" \
  IEUM_BOOTSTRAP_SUDOERS_ROOT="$SUDOERS_ROOT" \
  IEUM_BOOTSTRAP_VISUDO_LOG="$VISUDO_LOG" \
  VISUDO_LOG="$VISUDO_LOG" \
  FAKE_DOCKER_LOG="$TMP_DIR/docker.log" \
  FAKE_DOCKER_MINIO="${FAKE_DOCKER_MINIO:-present}" \
  FAKE_DOCKER_IEUM="${FAKE_DOCKER_IEUM:-present}" \
  FAKE_DOCKER_COMPOSE="${FAKE_DOCKER_COMPOSE:-present}" \
  FAKE_DOCKER_NETWORK_ENUM="${FAKE_DOCKER_NETWORK_ENUM:-}" \
  FAKE_DOCKER_NETWORK_INSPECT_FAIL="${FAKE_DOCKER_NETWORK_INSPECT_FAIL:-}" \
  FAKE_ID_GROUPS="${FAKE_ID_GROUPS:-}" \
  FAKE_RUNNER_GROUP="${FAKE_RUNNER_GROUP:-private}" \
  FAKE_USER_LOG="$TMP_DIR/user.log" \
  FAKE_RUNNER_CREATED="$TMP_DIR/runner-created" \
  FAKE_RUNNER_HOME="$RUNNER_HOME" \
  "$HELPER" "$@"
}

pass=0
fail=0
assert_success() {
  if "$@" >"$TMP_DIR/stdout" 2>"$TMP_DIR/stderr"; then pass=$((pass + 1)); else
    printf 'FAIL (expected success): %s\n' "$*" >&2; cat "$TMP_DIR/stderr" >&2; fail=$((fail + 1));
  fi
}
assert_failure() {
  if "$@" >"$TMP_DIR/stdout" 2>"$TMP_DIR/stderr"; then
    printf 'FAIL (expected failure): %s\n' "$*" >&2; fail=$((fail + 1));
  else pass=$((pass + 1)); fi
}

assert_success run_bootstrap
printf '#!/usr/bin/env bash\nexit 99\n' >>"$SOURCE_ROOT/deploy/onprem/scripts/deploy-release.sh"
assert_failure run_bootstrap
awk -v checksum="$(sha256sum "$SOURCE_ROOT/deploy/onprem/scripts/deploy-release.sh" | awk '{print $1}')" '{ if ($2 == "deploy/onprem/scripts/deploy-release.sh") print checksum "  " $2; else print }' "$SOURCE_ROOT/.ieum-source.sha256" >"$TMP_DIR/source-checksum" && mv "$TMP_DIR/source-checksum" "$SOURCE_ROOT/.ieum-source.sha256"
assert_success run_bootstrap
chmod 775 "$SOURCE_ROOT"
assert_failure run_bootstrap
chmod 700 "$SOURCE_ROOT"
chmod 666 "$SOURCE_ROOT/.ieum-source.sha256"
assert_failure run_bootstrap
chmod 600 "$SOURCE_ROOT/.ieum-source.sha256"
grep -v 'install-production-nginx.sh' "$SOURCE_ROOT/.ieum-source.sha256" >"$TMP_DIR/source-checksum" && mv "$TMP_DIR/source-checksum" "$SOURCE_ROOT/.ieum-source.sha256"
assert_failure run_bootstrap
( cd "$SOURCE_ROOT" && sha256sum deploy/onprem/scripts/*.sh > .ieum-source.sha256 )
chmod 600 "$SOURCE_ROOT/.ieum-source.sha256"
mv "$SOURCE_ROOT" "$TMP_DIR/source-real"
ln -s source-real "$SOURCE_ROOT"
assert_failure run_bootstrap
rm "$SOURCE_ROOT"
mv "$TMP_DIR/source-real" "$SOURCE_ROOT"
for name in ieum-deploy-release ieum-db-preflight ieum-install-staging-nginx ieum-install-production-nginx ieum-object-store-mirror ieum-release-dispatch ieum-db-restore-rehearsal ieum-db-restore-production ieum-db-verify ieum-provision-existing-postgres ieum-provision-runtime-env ieum-validate-runtime-env ieum-install-self-hosted-runner; do
  [[ -f "$INSTALL_ROOT/$name" && ! -L "$INSTALL_ROOT/$name" ]] || { printf 'FAIL missing helper %s\n' "$name" >&2; fail=$((fail + 1)); }
  [[ "$(stat -c '%a' "$INSTALL_ROOT/$name" 2>/dev/null || stat -f '%Lp' "$INSTALL_ROOT/$name")" == 755 ]] || { printf 'FAIL helper mode %s\n' "$name" >&2; fail=$((fail + 1)); }
done
for source_name in deploy-release.sh db-preflight.sh install-staging-nginx.sh install-production-nginx.sh object-store-mirror.sh ieum-release-dispatch.sh db-restore-rehearsal.sh db-restore-production.sh db-verify.sh provision-existing-postgres.sh provision-runtime-env.sh validate-runtime-env.sh install-self-hosted-runner.sh; do
  grep -Fq "deploy/onprem/scripts/$source_name" "$SOURCE_ROOT/.ieum-source.sha256" || { printf 'FAIL checksum manifest missing %s\n' "$source_name" >&2; fail=$((fail + 1)); }
done
for dir in "$SRV_ROOT/staging" "$SRV_ROOT/releases" "$STATE_ROOT/state" "$STATE_ROOT/locks" "$STATE_ROOT/deployments" "$STATE_ROOT/maintenance" "$STATE_ROOT/nginx-staging" "$STATE_ROOT/nginx-production" "$ETC_ROOT"; do
  [[ -d "$dir" && ! -L "$dir" ]] || { printf 'FAIL missing state dir %s\n' "$dir" >&2; fail=$((fail + 1)); }
  [[ "$(stat -c '%a' "$dir" 2>/dev/null || stat -f '%Lp' "$dir")" == 700 ]] || { printf 'FAIL state mode %s\n' "$dir" >&2; fail=$((fail + 1)); }
done
[[ -d "$STATE_ROOT/backups" && ! -L "$STATE_ROOT/backups" ]] || { printf 'FAIL root-only backups dir missing\n' >&2; fail=$((fail + 1)); }
[[ ! -e "$ETC_ROOT/app-main.env" && ! -e "$ETC_ROOT/app-ai.env" ]] || { printf 'FAIL secret env file created\n' >&2; fail=$((fail + 1)); }

: >"$TMP_DIR/docker.log"
FAKE_DOCKER_IEUM=missing
assert_failure run_bootstrap
if grep -Fqx 'network create --driver bridge --subnet 172.30.0.0/24 ieum' "$TMP_DIR/docker.log"; then
  printf 'FAIL bootstrap created the required external ieum network\n' >&2
  fail=$((fail + 1))
fi
unset FAKE_DOCKER_IEUM

FAKE_DOCKER_IEUM=invalid
assert_failure run_bootstrap
unset FAKE_DOCKER_IEUM

FAKE_DOCKER_IEUM=missing
FAKE_DOCKER_NETWORK_ENUM=collision
FAKE_DOCKER_NETWORK_INSPECT_FAIL=1
assert_failure run_bootstrap
unset FAKE_DOCKER_IEUM FAKE_DOCKER_NETWORK_ENUM FAKE_DOCKER_NETWORK_INSPECT_FAIL

FAKE_DOCKER_MINIO=missing
assert_failure run_bootstrap
unset FAKE_DOCKER_MINIO

FAKE_DOCKER_MINIO=missing-alias
assert_failure run_bootstrap
unset FAKE_DOCKER_MINIO

FAKE_DOCKER_MINIO=unhealthy
assert_failure run_bootstrap
unset FAKE_DOCKER_MINIO

FAKE_DOCKER_MINIO=unreachable
assert_failure run_bootstrap
unset FAKE_DOCKER_MINIO

FAKE_DOCKER_COMPOSE=missing
assert_failure run_bootstrap
unset FAKE_DOCKER_COMPOSE

IEUM_BOOTSTRAP_EXPECTED_OWNER='unexpected-owner'
assert_failure run_bootstrap
unset IEUM_BOOTSTRAP_EXPECTED_OWNER

rm -f "$BIN_ROOT/pg_restore"
assert_failure run_bootstrap
printf '%s\n' 'fake' >"$BIN_ROOT/pg_restore"
chmod 700 "$BIN_ROOT/pg_restore"

assert_success run_bootstrap --install-runner-user
assert_success run_bootstrap --install-runner-user
FAKE_ID_GROUPS=docker
assert_failure run_bootstrap --install-runner-user
unset FAKE_ID_GROUPS
FAKE_ID_GROUPS=sudo
assert_failure run_bootstrap --install-runner-user
unset FAKE_ID_GROUPS
FAKE_RUNNER_GROUP=shared
assert_failure run_bootstrap --install-runner-user
unset FAKE_RUNNER_GROUP
FAKE_RUNNER_GROUP=wrong-gid
assert_failure run_bootstrap --install-runner-user
unset FAKE_RUNNER_GROUP
runner_sudoers="$SUDOERS_ROOT/ieum-runner-release-dispatch"
[[ -f "$runner_sudoers" && ! -L "$runner_sudoers" ]] || { printf 'FAIL runner sudoers missing\n' >&2; fail=$((fail + 1)); }
[[ "$(stat -c '%a' "$runner_sudoers" 2>/dev/null || stat -f '%Lp' "$runner_sudoers")" == 440 ]] || { printf 'FAIL runner sudoers mode\n' >&2; fail=$((fail + 1)); }
grep -Fqx "ieum-runner ALL=(root) NOPASSWD: $INSTALL_ROOT/ieum-release-dispatch --local *" "$runner_sudoers" || { printf 'FAIL runner sudoers rule is not narrowly scoped\n' >&2; fail=$((fail + 1)); }
if grep -Eq 'ieum-deploy-release|NOPASSWD: ALL|/bin/sh|/bin/bash' "$runner_sudoers"; then printf 'FAIL runner sudoers grants a root shell or release helper\n' >&2; fail=$((fail + 1)); fi
grep -Fq 'useradd --system' "$TMP_DIR/user.log" || { printf 'FAIL runner account was not created\n' >&2; fail=$((fail + 1)); }
grep -Fq 'useradd --system --create-home --user-group --home-dir /home/ieum-runner --shell /bin/bash ieum-runner' "$TMP_DIR/user.log" || { printf 'FAIL runner account home/shell/group provisioning\n' >&2; fail=$((fail + 1)); }
grep -Fq 'passwd --lock ieum-runner' "$TMP_DIR/user.log" || { printf 'FAIL runner account password was not locked\n' >&2; fail=$((fail + 1)); }
[[ -d "$RUNNER_HOME" && ! -L "$RUNNER_HOME" ]] || { printf 'FAIL runner home missing\n' >&2; fail=$((fail + 1)); }
[[ "$(stat -c '%a' "$RUNNER_HOME" 2>/dev/null || stat -f '%Lp' "$RUNNER_HOME")" == 700 ]] || { printf 'FAIL runner home mode\n' >&2; fail=$((fail + 1)); }

printf 'passed=%d failed=%d\n' "$pass" "$fail"
test "$fail" -eq 0
