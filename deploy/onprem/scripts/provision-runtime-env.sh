#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# Render the two root-owned runtime files from checked-in env bases and
# root-only overlays.  Overlay files are parsed as data; they are never sourced.
#
# Usage:
#   provision-runtime-env.sh \
#     --app-main-base /path/app-main.env.deploy \
#     --app-ai-base /path/app-ai.env.deploy \
#     --app-main-overlay /path/app-main.secrets.env \
#     --app-ai-overlay /path/app-ai.secrets.env \
#     --db-overlay /path/database.env [--output-dir /etc/ieum] [--validator PATH]

die() {
  printf 'runtime environment provisioning failed: %s\n' "$1" >&2
  exit 64
}

usage() {
  printf 'usage: %s --app-main-base FILE --app-ai-base FILE --app-main-overlay FILE --app-ai-overlay FILE --db-overlay FILE [--output-dir DIR] [--validator FILE]\n' "$0" >&2
  exit 64
}

is_test=${IEUM_PROVISION_RUNTIME_ENV_TEST_MODE:-0}
if [[ "$is_test" != 1 && "$EUID" -ne 0 ]]; then
  die 'must run as root'
fi

expected_owner=${IEUM_PROVISION_RUNTIME_ENV_EXPECTED_OWNER:-root}
output_dir=${IEUM_PROVISION_RUNTIME_ENV_OUTPUT_DIR:-/etc/ieum}
validator=${IEUM_RUNTIME_ENV_VALIDATOR:-}
main_base=''
ai_base=''
main_overlay=''
ai_overlay=''
db_overlay=''

while [[ $# -gt 0 ]]; do
  case "$1" in
    --app-main-base) [[ $# -ge 2 ]] || usage; main_base=$2; shift 2 ;;
    --app-ai-base) [[ $# -ge 2 ]] || usage; ai_base=$2; shift 2 ;;
    --app-main-overlay) [[ $# -ge 2 ]] || usage; main_overlay=$2; shift 2 ;;
    --app-ai-overlay) [[ $# -ge 2 ]] || usage; ai_overlay=$2; shift 2 ;;
    --db-overlay) [[ $# -ge 2 ]] || usage; db_overlay=$2; shift 2 ;;
    --output-dir) [[ $# -ge 2 ]] || usage; output_dir=$2; shift 2 ;;
    --validator) [[ $# -ge 2 ]] || usage; validator=$2; shift 2 ;;
    -h|--help) usage ;;
    *) die "unsupported argument: $1" ;;
  esac
done

[[ "$main_base" = /* && "$ai_base" = /* && "$main_overlay" = /* && "$ai_overlay" = /* && "$db_overlay" = /* ]] || usage
[[ "$output_dir" = /* ]] || die 'output directory must be absolute'

if [[ -z "$validator" ]]; then
  if [[ "$is_test" == 1 ]]; then
    validator=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/validate-runtime-env.sh
  else
    # bootstrap-control-plane installs this companion under a public helper
    # name; it is not adjacent to the installed provisioner filename.
    validator='/usr/local/sbin/ieum-validate-runtime-env'
  fi
fi
[[ "$validator" = /* && -f "$validator" && ! -L "$validator" && -x "$validator" ]] || die 'validator is not executable and safe'

owner_of() {
  stat -c '%U' -- "$1" 2>/dev/null || stat -f '%Su' -- "$1"
}

mode_of() {
  stat -c '%a' -- "$1" 2>/dev/null || stat -f '%Lp' -- "$1"
}

[[ "$(owner_of "$validator")" == "$expected_owner" ]] || die "validator must be owned by ${expected_owner}"
validator_mode=$(mode_of "$validator")
validator_mode_decimal=$((8#$validator_mode))
(( (validator_mode_decimal & 18) == 0 )) || die 'validator must not be group- or other-writable'

check_private_file() {
  local path=$1 label=$2 mode
  [[ "$path" = /* ]] || die "${label} path must be absolute"
  [[ -f "$path" && ! -L "$path" ]] || die "${label} must be a regular non-symlink file"
  [[ "$(owner_of "$path")" == "$expected_owner" ]] || die "${label} must be owned by ${expected_owner}"
  mode=$(mode_of "$path")
  [[ "$mode" == 600 ]] || die "${label} must have mode 0600"
  [[ -r "$path" ]] || die "${label} is unreadable"
}

check_private_file "$main_base" 'app-main base'
check_private_file "$ai_base" 'app-ai base'
check_private_file "$main_overlay" 'app-main overlay'
check_private_file "$ai_overlay" 'app-ai overlay'
check_private_file "$db_overlay" 'database overlay'

validate_base_file() {
  local path=$1 label=$2
  awk -v label="$label" -F= '
    /^[[:space:]]*(#|$)/ { next }
    /\r/ { exit 3 }
    !/^[A-Za-z_][A-Za-z0-9_]*=/ { exit 1 }
    { if (++seen[$1] > 1) exit 2 }
  ' "$path" || die "${label} is malformed, duplicated, or unsafe"
}

validate_base_file "$main_base" 'app-main base'
validate_base_file "$ai_base" 'app-ai base'

if [[ -L "$output_dir" || ( -e "$output_dir" && ! -d "$output_dir" ) ]]; then
  die 'output directory is not a safe directory'
fi
mkdir -p -- "$output_dir"
[[ "$(owner_of "$output_dir")" == "$expected_owner" ]] || die "output directory must be owned by ${expected_owner}"
chmod 700 "$output_dir"

parse_overlay() {
  local path=$1 label=$2 expected_count=$3 expected_keys=$4
  awk -v label="$label" -v expected_count="$expected_count" -v expected_keys="$expected_keys" -F= '
    function expected(key,   i, n, parts) {
      n = split(expected_keys, parts, ",")
      for (i = 1; i <= n; i++) if (parts[i] == key) return 1
      return 0
    }
    /^[[:space:]]*(#|$)/ { next }
    /\r/ { exit 3 }
    !/^[A-Za-z_][A-Za-z0-9_]*=/ { exit 1 }
    { if (!expected($1) || ++seen[$1] > 1 || $2 == "") exit 2; count++ }
    END {
      if (count != expected_count) exit 4
      n = split(expected_keys, parts, ",")
      for (i = 1; i <= n; i++) if (!seen[parts[i]]) exit 5
    }
  ' "$path" || die "${label} is malformed, duplicated, unsupported, or incomplete"
}
parse_overlay "$main_overlay" 'app-main overlay' 3 'AWS_ACCESS_KEY_ID,AWS_SECRET_ACCESS_KEY,REDIS_PASSWORD'
parse_overlay "$ai_overlay" 'app-ai overlay' 2 'AWS_ACCESS_KEY_ID,AWS_SECRET_ACCESS_KEY'
parse_overlay "$db_overlay" 'database overlay' 2 'SPRING_DATASOURCE_USERNAME,SPRING_DATASOURCE_PASSWORD'
[[ "$(awk -F= '$1 == "SPRING_DATASOURCE_USERNAME" { print substr($0, index($0, "=") + 1); exit }' "$db_overlay")" == ieum ]] || die 'database overlay must use the ieum username'

value_from_file() {
  awk -F= -v wanted="$2" '$1 == wanted { print substr($0, index($0, "=") + 1); exit }' "$1"
}

replacement_value() {
  local service=$1 key=$2
  case "$key" in
    SPRING_DATASOURCE_USERNAME|SPRING_DATASOURCE_PASSWORD) value_from_file "$db_overlay" "$key" ;;
    AWS_ACCESS_KEY_ID|AWS_SECRET_ACCESS_KEY)
      if [[ "$service" == main ]]; then value_from_file "$main_overlay" "$key"; else value_from_file "$ai_overlay" "$key"; fi ;;
    REDIS_PASSWORD) value_from_file "$main_overlay" "$key" ;;
    *) return 1 ;;
  esac
}

is_replacement_key() {
  case "$1" in
    AWS_ACCESS_KEY_ID|AWS_SECRET_ACCESS_KEY|REDIS_PASSWORD|SPRING_DATASOURCE_USERNAME|SPRING_DATASOURCE_PASSWORD) return 0 ;;
    *) return 1 ;;
  esac
}

render() {
  local source=$1 destination=$2 service=$3 tmp line key value
  tmp=$(mktemp "$output_dir/.env.XXXXXX") || die 'unable to create temporary runtime file'
  chmod 600 "$tmp"
  trap 'rm -f -- "$tmp"' RETURN
  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=.*$ ]]; then
      key=${BASH_REMATCH[1]}
      if is_replacement_key "$key" && { [[ "$service" == main ]] || [[ "$key" != REDIS_PASSWORD ]]; }; then
        value=$(replacement_value "$service" "$key") || die "unable to resolve replacement for ${key}"
        printf '%s=%s\n' "$key" "$value" >> "$tmp"
        continue
      fi
    fi
    printf '%s\n' "$line" >> "$tmp"
  done < "$source"
  if [[ "$service" == main ]]; then
    replacement_keys='AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY REDIS_PASSWORD SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD'
  else
    replacement_keys='AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD'
  fi
  for key in $replacement_keys; do
    if ! awk -F= -v wanted="$key" '$1 == wanted { found=1 } END { exit(found ? 0 : 1) }' "$source"; then
      value=$(replacement_value "$service" "$key") || die "unable to resolve replacement for ${key}"
      printf '%s=%s\n' "$key" "$value" >> "$tmp"
    fi
  done
  chmod 600 "$tmp"
  mv -f -- "$tmp" "$destination"
  trap - RETURN
}

render "$main_base" "$output_dir/.app-main.rendered.$$" main
render "$ai_base" "$output_dir/.app-ai.rendered.$$" ai
main_rendered="$output_dir/.app-main.rendered.$$"
ai_rendered="$output_dir/.app-ai.rendered.$$"
chmod 600 "$main_rendered" "$ai_rendered"

if ! "$validator" app-main "$main_rendered" "$ai_rendered" >/dev/null 2>&1; then
  rm -f -- "$main_rendered" "$ai_rendered"
  die 'app-main runtime environment failed validation'
fi
if ! "$validator" app-ai "$ai_rendered" "$main_rendered" >/dev/null 2>&1; then
  rm -f -- "$main_rendered" "$ai_rendered"
  die 'app-ai runtime environment failed validation'
fi

mv -f -- "$main_rendered" "$output_dir/app-main.env"
mv -f -- "$ai_rendered" "$output_dir/app-ai.env"
chmod 600 "$output_dir/app-main.env" "$output_dir/app-ai.env"

printf 'runtime environment files provisioned\n'
