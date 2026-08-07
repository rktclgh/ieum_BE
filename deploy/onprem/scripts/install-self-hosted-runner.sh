#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
die() { printf 'ieum self-hosted runner: %s\n' "$1" >&2; exit 64; }
is_test=false
if [[ "$EUID" -eq 0 ]]; then
  PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'; export PATH
  RUNNER_ROOT=${IEUM_RUNNER_ROOT:-/var/lib/ieum-runner}; ARCHIVE=${IEUM_RUNNER_ARCHIVE:-$RUNNER_ROOT/actions-runner.tar.gz}; SERVICE_ROOT=${IEUM_RUNNER_SERVICE_ROOT:-/etc/systemd/system}
  GETENT_BIN=/usr/bin/getent; TAR_BIN=/usr/bin/tar; CHOWN_BIN=/usr/bin/chown; RUNUSER_BIN=/usr/sbin/runuser; ID_BIN=/usr/bin/id; SYSTEMCTL_BIN=/usr/bin/systemctl
else
  [[ "${IEUM_RUNNER_TEST_MODE:-}" == 1 ]] || die 'must run as root'; is_test=true
  RUNNER_ROOT=${IEUM_RUNNER_ROOT:-}; ARCHIVE=${IEUM_RUNNER_ARCHIVE:-}; SERVICE_ROOT=${IEUM_RUNNER_SERVICE_ROOT:-}
  GETENT_BIN=${IEUM_RUNNER_GETENT_BIN:-}; TAR_BIN=${IEUM_RUNNER_TAR_BIN:-}; CHOWN_BIN=${IEUM_RUNNER_CHOWN_BIN:-}; RUNUSER_BIN=${IEUM_RUNNER_RUNUSER_BIN:-}; ID_BIN=${IEUM_RUNNER_ID_BIN:-}; SYSTEMCTL_BIN=${IEUM_RUNNER_SYSTEMCTL_BIN:-}
  [[ "$RUNNER_ROOT" = /* && "$ARCHIVE" = /* && "$SERVICE_ROOT" = /* ]] || die 'test paths must be absolute'
fi
readonly REPOSITORY_URL='https://github.com/rktclgh/ieum_BE' RUNNER_USER='ieum-runner' RUNNER_NAME='song-server-ieum-prod-01' RUNNER_LABEL='ieum-prod-deploy' SERVICE_NAME='actions.runner.rktclgh-ieum_BE.song-server-ieum-prod-01.service'
token_file=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --token-file) [[ $# -ge 2 ]] || die '--token-file requires a value'; token_file=$2; shift 2 ;;
    *) die "unsupported argument: $1" ;;
  esac
done
[[ -n "$token_file" && "$token_file" = /* ]] || die 'a private absolute token file is required'
[[ "$RUNNER_NAME" =~ ^song-server-ieum-prod-[0-9]{2}$ && "$RUNNER_LABEL" =~ ^[a-z0-9][a-z0-9-]{0,62}$ ]] || die 'invalid runner identity'
[[ "$REPOSITORY_URL" == https://github.com/rktclgh/ieum_BE ]] || die 'invalid repository URL'
for command_path in "$GETENT_BIN" "$TAR_BIN" "$CHOWN_BIN" "$RUNUSER_BIN" "$ID_BIN" "$SYSTEMCTL_BIN"; do [[ -x "$command_path" ]] || die "required binary is missing or not executable: $command_path"; done
owner_of() { stat -c '%U' -- "$1" 2>/dev/null || stat -f '%Su' -- "$1"; }
group_of() { stat -c '%G' -- "$1" 2>/dev/null || stat -f '%Sg' -- "$1"; }
mode_of() { stat -c '%a' -- "$1" 2>/dev/null || stat -f '%Lp' -- "$1"; }
validate_parent_chain() {
  local path=$1 parent mode
  parent=$(dirname -- "$path")
  while [[ "$parent" != / ]]; do
    [[ -d "$parent" && ( ! -L "$parent" || "$is_test" == true ) ]] || die 'path parent must be a real directory'
    parent_owner=$(owner_of "$parent")
    if [[ "$parent_owner" != "$expected_owner" && ! ( "$is_test" == true && "$parent_owner" == root ) ]]; then die 'path parent has unexpected owner'; fi
    mode=$((8#$(mode_of "$parent"))); (( (mode & 022) == 0 )) || die 'path parent must not be group- or other-writable'
    parent=$(dirname -- "$parent")
  done
}
expected_owner=${IEUM_RUNNER_EXPECTED_OWNER:-root}
validate_parent_chain "$token_file"
validate_parent_chain "$ARCHIVE"
[[ -f "$token_file" && ! -L "$token_file" ]] || die 'token file must be a regular non-symlink file'
[[ "$(owner_of "$token_file")" == "$expected_owner" ]] || die 'token file must be root-owned'
token_mode_decimal=$((8#$(mode_of "$token_file"))); (( (token_mode_decimal & 077) == 0 )) || die 'token file must not be accessible by group or other users'
[[ -s "$token_file" && "$(wc -l <"$token_file")" -eq 1 ]] || die 'token file must contain exactly one token line'
[[ -f "$ARCHIVE" && ! -L "$ARCHIVE" ]] || die 'runner archive must be a regular non-symlink file'
[[ "$(owner_of "$ARCHIVE")" == "$expected_owner" ]] || die 'runner archive must be root-owned'
archive_mode_decimal=$((8#$(mode_of "$ARCHIVE"))); (( (archive_mode_decimal & 022) == 0 )) || die 'runner archive must not be writable by group or other users'
[[ -d "$RUNNER_ROOT" && ! -L "$RUNNER_ROOT" ]] || die 'runner root must be a root-owned directory'
runner_root_owner_before=$(owner_of "$RUNNER_ROOT")
runner_root_group_before=$(group_of "$RUNNER_ROOT")
runner_root_mode_before=$(mode_of "$RUNNER_ROOT")
[[ "$runner_root_owner_before" == "$expected_owner" ]] || die 'runner root must be a root-owned directory'
root_mode_decimal=$((8#$runner_root_mode_before)); (( (root_mode_decimal & 022) == 0 )) || die 'runner root must not be writable by group or other users'
runner_root_metadata_changed=false
validate_parent_chain "$SERVICE_ROOT/$SERVICE_NAME"
[[ -d "$SERVICE_ROOT" && ! -L "$SERVICE_ROOT" && "$(owner_of "$SERVICE_ROOT")" == "$expected_owner" ]] || die 'service root must be root-owned'
service_root_mode_decimal=$((8#$(mode_of "$SERVICE_ROOT"))); (( (service_root_mode_decimal & 022) == 0 )) || die 'service root must not be writable by group or other users'
unit="$SERVICE_ROOT/$SERVICE_NAME"
[[ ! -e "$unit" && ! -L "$unit" ]] || die 'runner unit already exists; refuse to replace an existing runner'
account=$($GETENT_BIN passwd "$RUNNER_USER" 2>/dev/null) || die 'runner user must already exist'
IFS=: read -r account_name _ account_uid account_gid _ account_home account_shell <<<"$account"
[[ "$account_name" == "$RUNNER_USER" && "$account_uid" =~ ^[1-9][0-9]*$ && "$account_gid" =~ ^[1-9][0-9]*$ ]] || die 'runner account has unsafe UID or GID'
[[ "$account_home" = /* && -d "$account_home" && ! -L "$account_home" ]] || die 'runner account home is unsafe'
[[ "$account_shell" == /bin/bash ]] || die 'runner account shell is not /bin/bash'
home_owner=$(owner_of "$account_home")
[[ "$home_owner" == "$RUNNER_USER" || ( "$is_test" == true && "$home_owner" == "$expected_owner" ) ]] || die 'runner account home has unexpected owner'
runner_groups=$($ID_BIN -nG "$RUNNER_USER" 2>/dev/null) || die 'unable to inspect runner groups'
for privileged_group in sudo wheel lxd docker adm disk root; do
  for group in $runner_groups; do [[ "$group" == "$privileged_group" ]] && die "runner user has privileged group: $group"; done
done
runner_group_entry=$($GETENT_BIN group "$RUNNER_USER" 2>/dev/null) || die 'runner private primary group does not exist'
IFS=: read -r runner_group_name _ runner_group_gid runner_group_members <<<"$runner_group_entry"
[[ "$runner_group_name" == "$RUNNER_USER" && "$runner_group_gid" == "$account_gid" && -z "$runner_group_members" ]] || die 'runner must have an empty private primary group'
all_passwd_entries=$($GETENT_BIN passwd 2>/dev/null) || die 'unable to inspect runner primary-group sharing'
shared_primary_member=$(printf '%s\n' "$all_passwd_entries" | awk -F: -v gid="$account_gid" '$4 == gid && $1 != "ieum-runner" { print $1; exit }')
[[ -z "$shared_primary_member" ]] || die 'runner private primary group is shared by another account'
# The service account must be able to enumerate its own versioned runner
# directory when `config.sh` resolves the working path. Give only its primary
# group read/traverse access; no non-root principal may write the root.
runner_root_metadata_changed=true
"$CHOWN_BIN" root:"$account_gid" "$RUNNER_ROOT" || die 'unable to assign runner-root group'
chmod 750 "$RUNNER_ROOT"
[[ "$(owner_of "$RUNNER_ROOT")" == "$expected_owner" ]] || die 'runner root owner changed unexpectedly'
runner_root_mode=$(mode_of "$RUNNER_ROOT"); [[ "$runner_root_mode" == 750 ]] || die 'runner root must be mode 0750'
while IFS= read -r member; do
  [[ "$member" != /* && "$member" != ../* && "$member" != */../* && "$member" != *'/..' ]] || die 'runner archive contains path traversal'
done < <("$TAR_BIN" -tzf "$ARCHIVE")
install_dir=$(mktemp -d "$RUNNER_ROOT/runner.XXXXXX"); chmod 750 "$install_dir"
unit_tmp=''
unit_written=false
runner_registered=false
cleanup_on_exit() {
  local cleanup_failed=false
  if [[ -n "${unit_tmp:-}" && -e "$unit_tmp" ]] && ! rm -f -- "$unit_tmp"; then cleanup_failed=true; fi
  if [[ "${install_succeeded:-false}" != true && "${unit_written:-false}" == true ]]; then
    "$SYSTEMCTL_BIN" disable "$SERVICE_NAME" >/dev/null 2>&1 || cleanup_failed=true
  fi
  if [[ "${install_succeeded:-false}" != true && "${runner_registered:-false}" != true ]]; then
    if [[ "${unit_written:-false}" == true ]] && ! rm -f -- "$unit"; then cleanup_failed=true; fi
    if [[ "${unit_written:-false}" == true ]] && ! "$SYSTEMCTL_BIN" daemon-reload >/dev/null 2>&1; then cleanup_failed=true; fi
    if [[ -n "${install_dir:-}" && -d "$install_dir" ]] && ! rm -rf -- "$install_dir"; then cleanup_failed=true; fi
  elif [[ "${install_succeeded:-false}" != true ]]; then
    printf 'ieum self-hosted runner: registration completed but activation failed; retained %s and %s for root-operator recovery\n' "$install_dir" "$unit" >&2
  fi
  if [[ "${install_succeeded:-false}" != true && "${runner_registered:-false}" != true && "${runner_root_metadata_changed:-false}" == true ]]; then
    "$CHOWN_BIN" "$runner_root_owner_before:$runner_root_group_before" "$RUNNER_ROOT" >/dev/null 2>&1 || cleanup_failed=true
    chmod "$runner_root_mode_before" "$RUNNER_ROOT" || cleanup_failed=true
  fi
  if [[ "$cleanup_failed" == true ]]; then
    printf 'ieum self-hosted runner: cleanup incomplete; manual root intervention is required\n' >&2
  fi
  return 0
}
trap cleanup_on_exit EXIT
"$TAR_BIN" -xzf "$ARCHIVE" -C "$install_dir" || die 'unable to extract runner archive'
config="$install_dir/config.sh"; runsvc_source="$install_dir/bin/runsvc.sh"; runsvc="$install_dir/runsvc.sh"
[[ -x "$config" && ! -L "$config" && -x "$runsvc_source" && ! -L "$runsvc_source" ]] || die 'runner archive must contain config.sh and bin/runsvc.sh'
cp -p -- "$runsvc_source" "$runsvc"; chmod 755 "$runsvc"
"$CHOWN_BIN" -R "$RUNNER_USER":"$RUNNER_USER" "$install_dir" || die 'unable to assign runner ownership'
unit_tmp=$(mktemp "$SERVICE_ROOT/.${SERVICE_NAME}.tmp.XXXXXX")
cat >"$unit_tmp" <<EOF
[Unit]
Description=GitHub Actions Runner (rktclgh/ieum_BE)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$RUNNER_USER
WorkingDirectory=$install_dir
ExecStart=$runsvc
KillMode=process
TimeoutStopSec=5min
Restart=always
RestartSec=5s

[Install]
WantedBy=multi-user.target
EOF
chmod 644 "$unit_tmp"; mv -f -- "$unit_tmp" "$unit"; unit_tmp=''; unit_written=true
"$SYSTEMCTL_BIN" daemon-reload || die 'systemd daemon-reload failed'
"$SYSTEMCTL_BIN" enable "$SERVICE_NAME" || die 'systemd enable failed'
# The upstream runner's unattended interface has no token-file/stdin option:
# it necessarily receives the short-lived registration token through `--token`.
# Do not log it, and run this setup only in a controlled maintenance window.
runner_token=$(<"$token_file")
(
  cd -- "$install_dir"
  "$RUNUSER_BIN" -u "$RUNNER_USER" -- ./config.sh \
    --unattended \
    --url "$REPOSITORY_URL" \
    --name "$RUNNER_NAME" \
    --labels "$RUNNER_LABEL" \
    --replace \
    --token "$runner_token"
) || die 'runner registration failed'
runner_registered=true
unset runner_token
rm -f -- "$token_file"; [[ ! -e "$token_file" ]] || die 'token file was not removed after registration'
"$SYSTEMCTL_BIN" start "$SERVICE_NAME" || die 'systemd start failed'
install_succeeded=true
printf 'installed %s in %s\n' "$RUNNER_NAME" "$install_dir"
