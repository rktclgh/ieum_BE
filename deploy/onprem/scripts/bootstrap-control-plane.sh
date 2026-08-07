#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

die() { printf 'ieum control-plane bootstrap: %s\n' "$1" >&2; exit 64; }
is_test=false
if [[ "$EUID" -eq 0 ]]; then
  PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'; export PATH
  SOURCE_ROOT=${IEUM_BOOTSTRAP_SOURCE_SNAPSHOT:-}
  SOURCE_CHECKSUM=${IEUM_BOOTSTRAP_SOURCE_CHECKSUM:-}
  INSTALL_ROOT='/usr/local/sbin'
  SRV_ROOT='/srv/ieum'
  STATE_ROOT='/var/lib/ieum'
  ETC_ROOT='/etc/ieum'
  SUDOERS_ROOT='/etc/sudoers.d'
  BIN_ROOT=''
  ID_BIN='/usr/bin/id'
  GETENT_BIN='/usr/bin/getent'
  VISUDO_BIN='/usr/sbin/visudo'
  INSTALL_BIN='/usr/bin/install'
  USERADD_BIN='/usr/sbin/useradd'
  PASSWD_BIN='/usr/bin/passwd'
  ID_GROUPS_BIN='/usr/bin/id'
else
  [[ "${IEUM_BOOTSTRAP_TEST_MODE:-}" == 1 ]] || die 'must run as root'
  is_test=true
  SOURCE_ROOT=${IEUM_BOOTSTRAP_SOURCE_ROOT:-}
  SOURCE_CHECKSUM=${IEUM_BOOTSTRAP_SOURCE_CHECKSUM:-}
  INSTALL_ROOT=${IEUM_BOOTSTRAP_INSTALL_ROOT:-}
  SRV_ROOT=${IEUM_BOOTSTRAP_SRV_ROOT:-}
  STATE_ROOT=${IEUM_BOOTSTRAP_STATE_ROOT:-}
  ETC_ROOT=${IEUM_BOOTSTRAP_ETC_ROOT:-}
  SUDOERS_ROOT=${IEUM_BOOTSTRAP_SUDOERS_ROOT:-}
  BIN_ROOT=${IEUM_BOOTSTRAP_BIN_ROOT:-}
  ID_BIN=${IEUM_BOOTSTRAP_ID_BIN:-/usr/bin/id}
  GETENT_BIN=${IEUM_BOOTSTRAP_GETENT_BIN:-$BIN_ROOT/getent}
  VISUDO_BIN=${IEUM_BOOTSTRAP_VISUDO_BIN:-$BIN_ROOT/visudo}
  INSTALL_BIN=${IEUM_BOOTSTRAP_INSTALL_BIN:-/usr/bin/install}
  USERADD_BIN=${IEUM_BOOTSTRAP_USERADD_BIN:-$BIN_ROOT/useradd}
  PASSWD_BIN=${IEUM_BOOTSTRAP_PASSWD_BIN:-$BIN_ROOT/passwd}
  ID_GROUPS_BIN=${IEUM_BOOTSTRAP_ID_GROUPS_BIN:-$BIN_ROOT/id}
  [[ "$SOURCE_ROOT" = /* && "$INSTALL_ROOT" = /* && "$SRV_ROOT" = /* && "$STATE_ROOT" = /* && "$ETC_ROOT" = /* && "$SUDOERS_ROOT" = /* && "$BIN_ROOT" = /* ]] || die 'test paths must be absolute'
  [[ "$ID_BIN" = /* && "$GETENT_BIN" = /* && "$VISUDO_BIN" = /* && "$INSTALL_BIN" = /* && "$USERADD_BIN" = /* && "$PASSWD_BIN" = /* ]] || die 'test command paths must be absolute'
fi

if [[ -z "$VISUDO_BIN" ]]; then VISUDO_BIN='/usr/sbin/visudo'; fi
EXPECTED_OWNER=root
if [[ "$is_test" == true ]]; then EXPECTED_OWNER=${IEUM_BOOTSTRAP_EXPECTED_OWNER:-$(id -un)}; fi
readonly DISPATCH_HELPERS=(
  'deploy-release.sh:ieum-deploy-release'
  'db-preflight.sh:ieum-db-preflight'
  'install-staging-nginx.sh:ieum-install-staging-nginx'
  'install-production-nginx.sh:ieum-install-production-nginx'
  'object-store-mirror.sh:ieum-object-store-mirror'
  'ieum-release-dispatch.sh:ieum-release-dispatch'
  'db-restore-rehearsal.sh:ieum-db-restore-rehearsal'
  'db-restore-production.sh:ieum-db-restore-production'
  'db-verify.sh:ieum-db-verify'
  'provision-existing-postgres.sh:ieum-provision-existing-postgres'
  'provision-runtime-env.sh:ieum-provision-runtime-env'
  'validate-runtime-env.sh:ieum-validate-runtime-env'
  'minio-presign-smoke.py:ieum-minio-presign-smoke'
  'install-self-hosted-runner.sh:ieum-install-self-hosted-runner'
)

install_runner_user=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --install-runner-user) install_runner_user=true; shift ;;
    *) die "unsupported argument: $1" ;;
  esac
done

required_binaries=(docker curl openssl python3 systemctl psql pg_restore sudo)
if [[ "$is_test" == true ]]; then required_binaries+=(nginx visudo); else required_binaries+=(nginx); fi
if [[ "$install_runner_user" == true ]]; then required_binaries+=(useradd passwd); fi
for binary in "${required_binaries[@]}"; do
  if [[ "$is_test" == true ]]; then path="$BIN_ROOT/$binary"; else
    case "$binary" in
      nginx) path='/usr/sbin/nginx' ;;
      visudo) path='/usr/sbin/visudo' ;;
      useradd) path="$USERADD_BIN" ;;
      passwd) path="$PASSWD_BIN" ;;
      *) path="/usr/bin/$binary" ;;
    esac
  fi
  [[ -x "$path" ]] || die "required binary is missing or not executable: $path"
done
[[ -x "$ID_BIN" ]] || die "required binary is missing or not executable: $ID_BIN"
[[ -x "$GETENT_BIN" ]] || die "required binary is missing or not executable: $GETENT_BIN"
[[ -x "$VISUDO_BIN" ]] || die "required binary is missing or not executable: $VISUDO_BIN"
[[ -x "$INSTALL_BIN" ]] || die "required binary is missing or not executable: $INSTALL_BIN"
if [[ "$is_test" == true ]]; then DOCKER_BIN="$BIN_ROOT/docker"; else DOCKER_BIN='/usr/bin/docker'; fi
"$DOCKER_BIN" compose version >/dev/null 2>&1 || die 'Docker Compose plugin is missing or unusable'

prepare_docker_networks() {
  "$DOCKER_BIN" network inspect ieum-minio >/dev/null 2>&1 || die 'required ieum-minio Docker network is missing'

  # The network alone is insufficient: app containers resolve the storage
  # endpoint as `minio`, so fail closed unless a running MinIO container owns
  # that Docker DNS alias and answers the health endpoint from inside the
  # network. No container is attached, restarted, or otherwise modified here.
  local records candidate aliases minio_container minio_running alias_count=0
  records=$("$DOCKER_BIN" network inspect ieum-minio \
    --format '{{range .Containers}}{{.Name}}{{"\n"}}{{end}}' \
    2>/dev/null) || die 'unable to inspect ieum-minio containers'
  while IFS= read -r candidate; do
    [[ -n "$candidate" ]] || continue
    aliases=$("$DOCKER_BIN" inspect --format \
      '{{range $network, $config := .NetworkSettings.Networks}}{{if eq $network "ieum-minio"}}{{range $config.Aliases}}{{.}}{{"\n"}}{{end}}{{end}}{{end}}' \
      "$candidate" 2>/dev/null) || die 'unable to inspect an ieum-minio container'
    if printf '%s\n' "$aliases" | grep -Fqx minio; then
      minio_container=$candidate
      alias_count=$((alias_count + 1))
    fi
  done <<<"$records"
  [[ "$alias_count" -eq 1 ]] || die 'ieum-minio must expose exactly one container with the minio DNS alias'
  minio_running=$("$DOCKER_BIN" inspect --format '{{.State.Running}}' "$minio_container" 2>/dev/null) || \
    die 'unable to inspect the MinIO container state'
  [[ "$minio_running" == true ]] || die 'MinIO container is not running'
  "$DOCKER_BIN" exec "$minio_container" /bin/sh -c \
    'command -v curl >/dev/null 2>&1 && curl --fail --silent --show-error --connect-timeout 3 --max-time 5 http://minio:9000/minio/health/live >/dev/null' \
    >/dev/null 2>&1 || die 'MinIO health endpoint is not reachable through the minio Docker DNS alias'

  local desired_subnet='172.30.0.0/24' ieum_attributes
  "$DOCKER_BIN" network inspect ieum >/dev/null 2>&1 || die 'required ieum Docker network is missing'
  ieum_attributes=$("$DOCKER_BIN" network inspect ieum --format '{{.Driver}}|{{(index .IPAM.Config 0).Subnet}}' 2>/dev/null) || die 'unable to inspect ieum Docker network attributes'
  [[ "$ieum_attributes" == "bridge|$desired_subnet" ]] || die 'ieum Docker network has unexpected driver or subnet'
}
prepare_docker_networks

safe_dir() {
  local path=$1
  [[ -d "$path" && ! -L "$path" ]] || die "unsafe directory: $path"
  [[ "$(owner_of "$path")" == "$EXPECTED_OWNER" ]] || die "unsafe directory owner: $path"
  chmod 700 "$path"
}
owner_of() { stat -c '%U' -- "$1" 2>/dev/null || stat -f '%Su' -- "$1"; }
verify_source_snapshot() {
  [[ -n "$SOURCE_ROOT" && -d "$SOURCE_ROOT" && ! -L "$SOURCE_ROOT" ]] || die 'source snapshot is missing or unsafe'
  if [[ "$is_test" != true && "$SOURCE_ROOT" == /home/song/* ]]; then die 'user-writable checkout cannot be used as the production source snapshot'; fi
  [[ "$(owner_of "$SOURCE_ROOT")" == "$EXPECTED_OWNER" ]] || die 'source snapshot must be owned by root'
  snapshot_mode=$(stat -c '%a' -- "$SOURCE_ROOT" 2>/dev/null || stat -f '%Lp' -- "$SOURCE_ROOT")
  snapshot_mode_decimal=$((8#$snapshot_mode))
  (( (snapshot_mode_decimal & 18) == 0 )) || die 'source snapshot must not be group- or other-writable'
  [[ -n "$SOURCE_CHECKSUM" && -f "$SOURCE_CHECKSUM" && ! -L "$SOURCE_CHECKSUM" ]] || die 'source snapshot checksum manifest is missing or unsafe'
  [[ "$(owner_of "$SOURCE_CHECKSUM")" == "$EXPECTED_OWNER" ]] || die 'source snapshot checksum manifest has unexpected owner'
  checksum_mode=$(stat -c '%a' -- "$SOURCE_CHECKSUM" 2>/dev/null || stat -f '%Lp' -- "$SOURCE_CHECKSUM")
  checksum_mode_decimal=$((8#$checksum_mode))
  (( (checksum_mode_decimal & 18) == 0 )) || die 'source snapshot checksum manifest must not be group- or other-writable'
  [[ "$SOURCE_CHECKSUM" == "$SOURCE_ROOT"/* ]] || die 'source snapshot checksum manifest must be inside snapshot'
  awk 'NF == 2 && $1 ~ /^[0-9a-f]{64}$/ && $2 !~ /^\// && $2 !~ /(^|\/)\.\.($|\/)/ && $2 !~ /(^|\/)\.ieum-source\.sha256$/ { next } { exit 1 }' "$SOURCE_CHECKSUM" || die 'source snapshot checksum manifest is unsafe'
  while read -r _ source_member; do
    source_member_path="$SOURCE_ROOT/$source_member"
    [[ -f "$source_member_path" && ! -L "$source_member_path" ]] || die 'source snapshot contains an unsafe member'
    [[ "$(owner_of "$source_member_path")" == "$EXPECTED_OWNER" ]] || die 'source snapshot member has unexpected owner'
    member_mode=$(stat -c '%a' -- "$source_member_path" 2>/dev/null || stat -f '%Lp' -- "$source_member_path")
    member_mode_decimal=$((8#$member_mode))
    (( (member_mode_decimal & 18) == 0 )) || die 'source snapshot member must not be group- or other-writable'
  done < <(awk '{ print $1 " " $2 }' "$SOURCE_CHECKSUM")
  for item in "${DISPATCH_HELPERS[@]}"; do
    source_name=${item%%:*}
    awk -v expected="deploy/onprem/scripts/$source_name" '$2 == expected { found=1 } END { exit(found ? 0 : 1) }' "$SOURCE_CHECKSUM" || die "source snapshot checksum manifest omits $source_name"
  done
  ( cd "$SOURCE_ROOT" && sha256sum --check "$SOURCE_CHECKSUM" >/dev/null 2>&1 ) || die 'source snapshot checksum verification failed'
}
system_dir() {
  local path=$1 mode
  [[ -d "$path" && ! -L "$path" ]] || die "unsafe system directory: $path"
  [[ "$(owner_of "$path")" == "$EXPECTED_OWNER" ]] || die "unsafe system directory owner: $path"
  mode=$(stat -c '%a' -- "$path" 2>/dev/null || stat -f '%Lp' -- "$path")
  if [[ "$is_test" == true ]]; then chmod 755 "$path"; else
    [[ "$mode" == 755 || "$mode" == 750 ]] || die "unsafe system directory mode: $path"
  fi
}
make_private_dir() {
  local path=$1
  if [[ -L "$path" || ( -e "$path" && ! -d "$path" ) ]]; then die "unsafe directory: $path"; fi
  mkdir -p -- "$path"
  chmod 700 "$path"
  safe_dir "$path"
}
regular_source() {
  local path=$1
  [[ -f "$path" && ! -L "$path" ]] || die "helper source is not a regular file: $path"
}

verify_source_snapshot

make_private_dir "$SRV_ROOT"
make_private_dir "$SRV_ROOT/staging"
make_private_dir "$SRV_ROOT/releases"
make_private_dir "$STATE_ROOT"
make_private_dir "$STATE_ROOT/state"
make_private_dir "$STATE_ROOT/locks"
make_private_dir "$STATE_ROOT/deployments"
make_private_dir "$STATE_ROOT/maintenance"
make_private_dir "$STATE_ROOT/backups"
make_private_dir "$STATE_ROOT/nginx-staging"
make_private_dir "$STATE_ROOT/nginx-production"
make_private_dir "$ETC_ROOT"
mkdir -p -- "$INSTALL_ROOT" "$SUDOERS_ROOT"
system_dir "$INSTALL_ROOT"
system_dir "$SUDOERS_ROOT"

for item in "${DISPATCH_HELPERS[@]}"; do
  source_name=${item%%:*}; target_name=${item#*:}
  source_path="$SOURCE_ROOT/deploy/onprem/scripts/$source_name"
  target_path="$INSTALL_ROOT/$target_name"
  regular_source "$source_path"
  if [[ -L "$target_path" || ( -e "$target_path" && ! -f "$target_path" ) ]]; then die "unsafe helper destination: $target_path"; fi
  if [[ -f "$target_path" ]]; then
    [[ "$(owner_of "$target_path")" == "$EXPECTED_OWNER" ]] || die "helper destination has unexpected owner: $target_path"
  fi
  install_tmp="$INSTALL_ROOT/.${target_name}.tmp.$$"
  backup_path="$STATE_ROOT/backups/${target_name}.previous"
  if [[ -f "$target_path" ]]; then
    [[ ! -L "$backup_path" ]] || die "unsafe helper backup destination: $backup_path"
    cp -p -- "$target_path" "$backup_path"
    chmod 700 "$backup_path"
    [[ "$(owner_of "$backup_path")" == "$EXPECTED_OWNER" ]] || die "helper backup has unexpected owner: $backup_path"
  fi
  rm -f -- "$install_tmp"
  "$INSTALL_BIN" -m 755 -- "$source_path" "$install_tmp"
  chmod 755 "$install_tmp"
  mv -f -- "$install_tmp" "$target_path"
  chmod 755 "$target_path"
  [[ -f "$target_path" && ! -L "$target_path" ]] || die "helper installation failed: $target_path"
  [[ "$(owner_of "$target_path")" == "$EXPECTED_OWNER" ]] || die "helper destination has unexpected owner: $target_path"
done

if [[ "$install_runner_user" == true ]]; then
  runner_user='ieum-runner'
  runner_home='/home/ieum-runner'
  if ! "$GETENT_BIN" passwd "$runner_user" >/dev/null 2>&1; then
    "$USERADD_BIN" --system --create-home --user-group --home-dir "$runner_home" --shell /bin/bash "$runner_user" >/dev/null 2>&1 || die 'unable to create ieum-runner account'
  fi
  "$PASSWD_BIN" --lock "$runner_user" >/dev/null 2>&1 || die 'unable to lock ieum-runner password'
  runner_passwd_entry=$($GETENT_BIN passwd "$runner_user" 2>/dev/null) || die 'ieum-runner account does not exist'
  runner_id=$(printf '%s\n' "$runner_passwd_entry" | awk -F: 'NF >= 7 { print $3; exit }')
  runner_gid=$(printf '%s\n' "$runner_passwd_entry" | awk -F: 'NF >= 7 { print $4; exit }')
  runner_home=$(printf '%s\n' "$runner_passwd_entry" | awk -F: 'NF >= 7 { print $6; exit }')
  runner_shell=$(printf '%s\n' "$runner_passwd_entry" | awk -F: 'NF >= 7 { print $7; exit }')
  [[ "$runner_id" =~ ^[1-9][0-9]*$ && "$runner_gid" =~ ^[1-9][0-9]*$ ]] || die 'ieum-runner has invalid passwd entry'
  runner_group_entry=$($GETENT_BIN group "$runner_user" 2>/dev/null) || die 'ieum-runner private primary group does not exist'
  IFS=: read -r runner_group_name _ runner_group_gid runner_group_members <<<"$runner_group_entry"
  [[ "$runner_group_name" == "$runner_user" && "$runner_group_gid" == "$runner_gid" && -z "$runner_group_members" ]] || die 'ieum-runner must have an empty private primary group'
  [[ "$runner_shell" == /bin/bash ]] || die 'ieum-runner must use a functional shell for the self-hosted runner'
  [[ "$runner_home" = /* && "$runner_home" != / && "$runner_home" != *'/../'* && "$runner_home" != */.. ]] || die 'ieum-runner home is unsafe'
  [[ -d "$runner_home" && ! -L "$runner_home" ]] || die 'ieum-runner home does not exist'
  chmod 700 "$runner_home"
  runner_groups=$($ID_GROUPS_BIN -nG "$runner_user" 2>/dev/null) || die 'unable to inspect ieum-runner groups'
  for forbidden_group in sudo wheel lxd docker adm disk root; do
    ! printf '%s\n' "$runner_groups" | tr ' ' '\n' | grep -Fxq "$forbidden_group" || die "ieum-runner must not belong to the $forbidden_group group"
  done
  runner_auth_owner="$runner_user"
  if [[ "$is_test" == true ]]; then runner_auth_owner=${IEUM_BOOTSTRAP_AUTH_OWNER:-$EXPECTED_OWNER}; fi
  [[ "$(owner_of "$runner_home")" == "$runner_auth_owner" ]] || die 'ieum-runner home has unexpected owner'

  runner_sudoers_file="$SUDOERS_ROOT/ieum-runner-release-dispatch"
  runner_sudoers_tmp="$SUDOERS_ROOT/.ieum-runner-release-dispatch.tmp.$$"
  trap 'rm -f -- "$runner_sudoers_tmp"' EXIT
  printf '%s ALL=(root) NOPASSWD: %s --local *\n' \
    "$runner_user" "$INSTALL_ROOT/ieum-release-dispatch" >"$runner_sudoers_tmp"
  chmod 600 "$runner_sudoers_tmp"
  "$VISUDO_BIN" -cf "$runner_sudoers_tmp" >/dev/null 2>&1 || die 'runner sudoers syntax verification failed'
  "$INSTALL_BIN" -m 440 -- "$runner_sudoers_tmp" "$runner_sudoers_file"
  chmod 440 "$runner_sudoers_file"
  rm -f -- "$runner_sudoers_tmp"
  trap - EXIT
fi

printf 'ieum control-plane bootstrap: PASS\n'
