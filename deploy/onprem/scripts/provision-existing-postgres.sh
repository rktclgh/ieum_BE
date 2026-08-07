#!/usr/bin/env bash
set -euo pipefail
umask 077

ETC_DIR="${IEUM_ETC_DIR:-/etc/ieum}"
CREDENTIALS_FILE="${IEUM_POSTGRES_CREDENTIALS_FILE:-$ETC_DIR/postgres.app.env}"
PG_OS_USER="${IEUM_POSTGRES_OS_USER:-postgres}"
SERVICE_FILE="$ETC_DIR/postgres.pg_service.conf"
PASS_FILE="$ETC_DIR/postgres.pgpass"

die() { printf '%s\n' "provision existing postgres: $*" >&2; exit 2; }
mode_of() { stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1"; }
owner_of() { stat -c '%u' "$1" 2>/dev/null || stat -f '%u' "$1"; }
require_private_root_file() {
  local path="$1" label="$2"
  [[ -f "$path" && ! -L "$path" ]] || die "$label must be a regular non-symlink file"
  [[ "$(owner_of "$path")" == 0 ]] || die "$label must be owned by root"
  [[ "$(mode_of "$path")" == 600 ]] || die "$label must have mode 0600"
}
read_credentials() {
  require_private_root_file "$CREDENTIALS_FILE" credentials
  local first second extra
  exec 3<"$CREDENTIALS_FILE" || die "unable to read credentials"
  IFS= read -r first <&3 || die "unable to read credentials"
  IFS= read -r second <&3 || die "credentials must contain exactly two lines"
  if IFS= read -r extra <&3; then exec 3<&-; die "credentials must contain exactly two lines"; fi
  exec 3<&-
  [[ "$first" == 'SPRING_DATASOURCE_USERNAME=ieum' ]] || die "credentials username must be ieum"
  [[ "$second" =~ ^SPRING_DATASOURCE_PASSWORD=[0-9a-fA-F]+$ ]] || die "credentials password must be non-empty hexadecimal"
  APP_PASSWORD="${second#SPRING_DATASOURCE_PASSWORD=}"
}
as_postgres() {
  command -v runuser >/dev/null 2>&1 || die "runuser is required"
  runuser -u "$PG_OS_USER" -- env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin PGHOST=/var/run/postgresql PGPORT=5432 "$@"
}
psql_admin() {
  as_postgres psql --no-password --no-psqlrc --quiet --set=ON_ERROR_STOP=1 --tuples-only --no-align --dbname=postgres "$@"
}
expect_exact() {
  local file="$1" expected="$2" actual
  actual="$(sed '/^[[:space:]]*$/d' "$file")"
  [[ "$actual" == "$expected" ]] || die "postgres preflight check failed"
}
preflight() {
  local dir="$1" version available role db
  version="$dir/version"; available="$dir/available"; role="$dir/role"; db="$dir/db"
  psql_admin -c "SELECT current_setting('server_version_num');" >"$version"
  local actual_version
  actual_version="$(sed '/^[[:space:]]*$/d' "$version")"
  [[ "$actual_version" =~ ^17[0-9]{4}$ ]] || die "host PostgreSQL must be version 17"
  psql_admin -c "SELECT name FROM pg_available_extensions WHERE name IN ('pgcrypto','postgis','vector') ORDER BY name;" >"$available"
  expect_exact "$available" $'pgcrypto\npostgis\nvector'
  psql_admin -c "SELECT rolname || '|' || CASE WHEN rolcanlogin THEN 't' ELSE 'f' END || '|' || CASE WHEN rolsuper THEN 't' ELSE 'f' END || '|' || CASE WHEN rolcreatedb THEN 't' ELSE 'f' END || '|' || CASE WHEN rolcreaterole THEN 't' ELSE 'f' END || '|' || CASE WHEN rolreplication THEN 't' ELSE 'f' END || '|' || CASE WHEN rolbypassrls THEN 't' ELSE 'f' END FROM pg_roles WHERE rolname = 'ieum';" >"$role"
  local role_state
  role_state="$(sed '/^[[:space:]]*$/d' "$role")"
  if [[ -n "$role_state" && "$role_state" != 'ieum|t|f|f|f|f|f' ]]; then die "existing ieum role has unexpected privileges"; fi
  psql_admin -c "SELECT datname || '|' || pg_get_userbyid(datdba) FROM pg_database WHERE datname = 'ieum';" >"$db"
  local db_state
  db_state="$(sed '/^[[:space:]]*$/d' "$db")"
  [[ -z "$db_state" || "$db_state" == 'ieum|ieum' ]] || die "existing ieum database has an unexpected owner"
}
mutate() {
  local dir="$1" sql
  sql="$dir/role.sql"
  printf '%s\n' "DO \$provision\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ieum') THEN CREATE ROLE ieum LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '$APP_PASSWORD'; ELSE ALTER ROLE ieum LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '$APP_PASSWORD'; END IF; END \$provision\$;" >"$sql"
  chmod 600 "$sql"
  as_postgres psql --no-password --no-psqlrc --quiet --set=ON_ERROR_STOP=1 --dbname=postgres --file=- <"$sql" >/dev/null
  if [[ -z "$(psql_admin -c "SELECT 1 FROM pg_database WHERE datname = 'ieum';")" ]]; then
    as_postgres createdb --no-password --maintenance-db=postgres --template=template0 --owner=ieum ieum >/dev/null
  fi
  psql_admin -c "GRANT CONNECT ON DATABASE postgres TO ieum; GRANT CONNECT ON DATABASE ieum TO ieum;"
  as_postgres psql --no-password --no-psqlrc --quiet --set=ON_ERROR_STOP=1 --dbname=ieum -c 'CREATE EXTENSION IF NOT EXISTS pgcrypto;' -c 'CREATE EXTENSION IF NOT EXISTS postgis;' -c 'CREATE EXTENSION IF NOT EXISTS vector;' >/dev/null
}
write_libpq_files() {
  local dir="$1" stage
  stage="$dir/.provision.$$"
  mkdir -p "$ETC_DIR"
  [[ "$(owner_of "$ETC_DIR")" == 0 && "$(mode_of "$ETC_DIR")" == 700 ]] || die "$ETC_DIR must be root-owned mode 0700"
  mkdir "$stage"; chmod 700 "$stage"
  printf '%s\n' \
    '[ieum_target_admin]' 'host=127.0.0.1' 'port=5432' 'user=ieum' 'dbname=postgres' \
    '' '[ieum_target]' 'host=127.0.0.1' 'port=5432' 'user=ieum' 'dbname=ieum' \
    '' '[ieum_target_rehearsal]' 'host=127.0.0.1' 'port=5432' 'user=ieum' 'dbname=ieum_rehearsal' >"$stage/service"
  printf '%s\n' "127.0.0.1:5432:*:ieum:$APP_PASSWORD" >"$stage/pass"
  chmod 600 "$stage/service" "$stage/pass"
  chown root:root "$stage/service" "$stage/pass" || die "unable to secure libpq files"
  mv -f "$stage/service" "$SERVICE_FILE"; mv -f "$stage/pass" "$PASS_FILE"; rmdir "$stage"
  require_private_root_file "$SERVICE_FILE" postgres service file
  require_private_root_file "$PASS_FILE" postgres pass file
}
main() {
  [[ "$EUID" -eq 0 ]] || die "must run as root"
  [[ "$PG_OS_USER" =~ ^[a-z_][a-z0-9_-]*$ ]] || die "invalid postgres OS user"
  read_credentials
  local tmp
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/ieum-provision-postgres.XXXXXX")"; chmod 700 "$tmp"
  trap 'rm -rf "${tmp:-}"' EXIT
  preflight "$tmp"
  mutate "$tmp"
  write_libpq_files "$tmp"
  printf '%s\n' 'provision existing postgres: PASS'
}
main "$@"
