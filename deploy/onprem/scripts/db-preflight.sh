#!/usr/bin/env bash
set -euo pipefail
umask 077

ADMIN_SERVICE=ieum_target_admin
die() { printf '%s\n' "db preflight: $*" >&2; exit 2; }
usage() { printf '%s\n' "usage: $0 --kind cluster|rehearsal|production --admin-service ieum_target_admin" >&2; exit 2; }

mode_of() { stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1"; }
owner_of() { stat -c '%u' "$1" 2>/dev/null || stat -f '%u' "$1"; }
require_private_file() {
  local path="$1" label="$2"
  [[ -f "$path" && ! -L "$path" ]] || die "$label must be a regular non-symlink file"
  [[ "$(owner_of "$path")" == "$EUID" ]] || die "$label has an unexpected owner"
  [[ "$(mode_of "$path")" == 600 ]] || die "$label must have mode 0600"
}
require_libpq_files() {
  [[ -n "${PGSERVICEFILE:-}" ]] || die "PGSERVICEFILE is required"
  [[ -n "${PGPASSFILE:-}" ]] || die "PGPASSFILE is required"
  require_private_file "$PGSERVICEFILE" PGSERVICEFILE
  require_private_file "$PGPASSFILE" PGPASSFILE
}

run_query() {
  local db="$1" query="$2" out="$3"
  PGSERVICE="$ADMIN_SERVICE" PGSERVICEFILE="$PGSERVICEFILE" PGPASSFILE="$PGPASSFILE" \
    psql --no-password --no-psqlrc --quiet --set=ON_ERROR_STOP=1 --tuples-only --no-align \
      --dbname="$db" -c "$query" >"$out" 2>/dev/null || die "database preflight query failed"
}
expect_lines() {
  local file="$1" expected="$2" actual
  actual="$(sed '/^[[:space:]]*$/d' "$file")"
  [[ "$actual" == "$expected" ]] || die "database preflight check failed"
}
expect_pg17() {
  local file="$1" actual
  actual="$(sed '/^[[:space:]]*$/d' "$file")"
  [[ "$actual" =~ ^17[0-9]{4}$ ]] || die "database preflight check failed"
}
expect_vector_probe() {
  local file="$1" actual
  actual="$(awk '
    /^[[:space:]]*(BEGIN|CREATE TABLE|INSERT 0 1|ROLLBACK)[[:space:]]*$/ { next }
    NF { print }
  ' "$file")"
  [[ "$actual" == 768 ]] || die "database preflight check failed"
}

kind=''; admin_seen=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --kind)
      [[ $# -ge 2 ]] || die "--kind requires a value"; kind="$2"; shift 2 ;;
    --kind=*) kind="${1#*=}"; shift ;;
    --admin-service)
      [[ $# -ge 2 ]] || die "--admin-service requires a value"
      [[ "$2" == "$ADMIN_SERVICE" ]] || die "--admin-service must be ieum_target_admin"
      admin_seen=true; shift 2 ;;
    --admin-service=*)
      [[ "${1#*=}" == "$ADMIN_SERVICE" ]] || die "--admin-service must be ieum_target_admin"
      admin_seen=true; shift ;;
    *) die "unsupported argument: $1" ;;
  esac
done
[[ "$admin_seen" == true ]] || usage
case "$kind" in cluster|rehearsal|production) ;; *) die "--kind must be cluster, rehearsal, or production" ;; esac
require_libpq_files

tmp="$(mktemp -d "${TMPDIR:-/tmp}/ieum-db-preflight.XXXXXX")" || die "unable to create temporary workspace"
trap 'rm -rf "$tmp"' EXIT
version="$tmp/version"; role="$tmp/role"; extensions="$tmp/extensions"
run_query postgres "SELECT current_setting('server_version_num');" "$version"
expect_pg17 "$version"
run_query postgres "SELECT rolname || '|' || CASE WHEN rolcanlogin THEN 't' ELSE 'f' END || '|' || CASE WHEN rolsuper THEN 't' ELSE 'f' END || '|' || CASE WHEN rolcreatedb THEN 't' ELSE 'f' END || '|' || CASE WHEN rolcreaterole THEN 't' ELSE 'f' END || '|' || CASE WHEN rolreplication THEN 't' ELSE 'f' END || '|' || CASE WHEN rolbypassrls THEN 't' ELSE 'f' END FROM pg_roles WHERE rolname = 'ieum';" "$role"
expect_lines "$role" 'ieum|t|f|f|f|f|f'

if [[ "$kind" == cluster ]]; then
  run_query postgres "SELECT name FROM pg_available_extensions WHERE name IN ('pgcrypto','postgis','vector') ORDER BY name;" "$extensions"
  expect_lines "$extensions" $'pgcrypto\npostgis\nvector'
else
  target=ieum_rehearsal
  [[ "$kind" == production ]] && target=ieum
  run_query "$target" "SELECT extname FROM pg_extension WHERE extname IN ('pgcrypto','postgis','vector') ORDER BY extname;" "$extensions"
  expect_lines "$extensions" $'pgcrypto\npostgis\nvector'
  vector="$tmp/vector"
  run_query "$target" "BEGIN; CREATE TEMP TABLE ieum_vector_probe (embedding vector(768)); INSERT INTO ieum_vector_probe SELECT array_fill(0::real, ARRAY[768])::vector; SELECT vector_dims(embedding) FROM ieum_vector_probe; ROLLBACK;" "$vector"
  expect_vector_probe "$vector"
fi

printf '%s\n' "db preflight: $kind PASS"
