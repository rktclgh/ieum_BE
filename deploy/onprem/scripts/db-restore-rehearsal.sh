#!/usr/bin/env bash
set -euo pipefail
umask 077

REHEARSAL_DB=ieum_rehearsal
APP_ROLE=ieum
ADMIN_SERVICE=ieum_target_admin
SOURCE_PG_RESTORE_BIN=/usr/lib/postgresql/18/bin/pg_restore
if [[ "$EUID" -eq 0 ]]; then
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
  export PATH
  LOCK_PATH=/var/lib/ieum/locks/deploy.lock
else
  LOCK_PATH="${IEUM_DEPLOY_LOCK_PATH:-/var/lib/ieum/locks/deploy.lock}"
fi

die() { echo "db restore rehearsal: $*" >&2; exit 2; }
usage() {
  echo "usage: $0 {restore|cleanup|assert-absent} --admin-service ieum_target_admin [options]" >&2
  exit 2
}
is_absolute() { [[ "$1" = /* ]]; }
regular_file() { [[ -f "$1" && ! -L "$1" ]]; }
mode_of() { stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1"; }
owner_of() { stat -c '%u' "$1" 2>/dev/null || stat -f '%u' "$1"; }
same_owner() { [[ "$(owner_of "$1")" == "$EUID" ]]; }
private_mode() { [[ "$(mode_of "$1")" == 600 ]]; }
private_dir_mode() { [[ "$(mode_of "$1")" == 700 ]]; }

require_private_regular_file() {
  local file=$1 label=$2
  regular_file "$file" || die "$label must be a regular non-symlink file"
  same_owner "$file" || die "$label must be owned by the invoking user"
  private_mode "$file" || die "$label must have mode 0600"
}

require_libpq_files() {
  [[ -n "${PGSERVICEFILE:-}" ]] || die "PGSERVICEFILE is required"
  [[ -n "${PGPASSFILE:-}" ]] || die "PGPASSFILE is required"
  require_private_regular_file "$PGSERVICEFILE" PGSERVICEFILE
  require_private_regular_file "$PGPASSFILE" PGPASSFILE
}

require_evidence_dir() {
  is_absolute "$1" || die "--evidence-dir must be absolute"
  [[ -d "$1" && ! -L "$1" ]] || die "--evidence-dir must be a regular non-symlink directory"
  same_owner "$1" || die "--evidence-dir must be owned by the invoking user"
  private_dir_mode "$1" || die "--evidence-dir must have mode 0700"
}
require_dump() {
  is_absolute "$1" || die "--dump must be absolute"
  require_private_regular_file "$1" --dump
}
configure_source_pg_restore() {
  if [[ -n "${IEUM_SOURCE_PG_RESTORE_BIN:-}" ]]; then
    [[ "${IEUM_TEST_MODE:-0}" == 1 ]] || die "IEUM_SOURCE_PG_RESTORE_BIN override is test-only"
    SOURCE_PG_RESTORE_BIN="$IEUM_SOURCE_PG_RESTORE_BIN"
  fi
  [[ -f "$SOURCE_PG_RESTORE_BIN" && ! -L "$SOURCE_PG_RESTORE_BIN" && -x "$SOURCE_PG_RESTORE_BIN" ]] || die "PostgreSQL 18 pg_restore client is required"
  if [[ "$EUID" -eq 0 ]]; then
    [[ "$(owner_of "$SOURCE_PG_RESTORE_BIN")" == 0 ]] || die "PostgreSQL 18 pg_restore client must be root-owned"
    local client_mode client_dir
    client_mode="$(mode_of "$SOURCE_PG_RESTORE_BIN")"
    [[ "$client_mode" =~ ^[0-7]+$ ]] && (( (8#$client_mode & 8#022) == 0 )) || die "PostgreSQL 18 pg_restore client is writable by group or other"
    client_dir="${SOURCE_PG_RESTORE_BIN%/*}"
    while [[ "$client_dir" != / ]]; do
      [[ -d "$client_dir" && ! -L "$client_dir" && "$(owner_of "$client_dir")" == 0 ]] || die "PostgreSQL 18 pg_restore parent directory is unsafe"
      client_mode="$(mode_of "$client_dir")"
      [[ "$client_mode" =~ ^[0-7]+$ ]] && (( (8#$client_mode & 8#022) == 0 )) || die "PostgreSQL 18 pg_restore parent directory is writable by group or other"
      client_dir="${client_dir%/*}"; [[ -n "$client_dir" ]] || client_dir=/
    done
  fi
  local version
  version="$("$SOURCE_PG_RESTORE_BIN" --version 2>/dev/null)" || die "unable to verify PostgreSQL 18 pg_restore client"
  [[ "$version" =~ PostgreSQL\)[[:space:]]+18([.[:space:]]|$) ]] || die "source archive requires PostgreSQL 18 pg_restore client"
}

create_pg_restore_list() {
  local label=$1 runtime_dir list_file runtime_mode
  [[ "$label" =~ ^[a-z-]+$ ]] || die "invalid pg_restore list label"
  if [[ "$EUID" -eq 0 ]]; then
    runtime_dir=/run
    if [[ -n "${IEUM_PG_RESTORE_RUNTIME_DIR:-}" ]]; then
      [[ "${IEUM_TEST_MODE:-0}" == 1 ]] || die "IEUM_PG_RESTORE_RUNTIME_DIR override is test-only"
      runtime_dir="$IEUM_PG_RESTORE_RUNTIME_DIR"
    fi
    is_absolute "$runtime_dir" || die "pg_restore runtime directory must be absolute"
    [[ -d "$runtime_dir" && ! -L "$runtime_dir" && "$(owner_of "$runtime_dir")" == 0 ]] || die "pg_restore runtime directory is unsafe"
    runtime_mode="$(mode_of "$runtime_dir")"
    [[ "$runtime_mode" =~ ^[0-7]+$ ]] && (( (8#$runtime_mode & 8#022) == 0 )) || die "pg_restore runtime directory is writable by group or other"
    list_file="$(mktemp "$runtime_dir/ieum-db-restore-$label-list.XXXXXX")" || die "unable to create postgres-accessible restore list"
    regular_file "$list_file" || die "postgres-accessible restore list is unsafe"
    chown postgres:postgres "$list_file" || die "unable to assign restore list to postgres"
    chmod 600 "$list_file" || die "unable to secure postgres restore list"
  else
    list_file="$(mktemp "$evidence_dir/.db-restore-$label.filtered-toc.XXXXXX")" || die "unable to create filtered restore TOC"
    chmod 600 "$list_file" || die "unable to secure filtered restore TOC"
  fi
  printf '%s\n' "$list_file"
}

acquire_lock() {
  local lock_dir
  is_absolute "$LOCK_PATH" || die "deployment lock path must be absolute"
  lock_dir="${LOCK_PATH%/*}"
  [[ -d "$lock_dir" && ! -L "$lock_dir" ]] || die "deployment lock directory is unsafe"
  same_owner "$lock_dir" || die "deployment lock directory has an unexpected owner"
  private_dir_mode "$lock_dir" || die "deployment lock directory must have mode 0700"
  if [[ -e "$LOCK_PATH" || -L "$LOCK_PATH" ]]; then
    require_private_regular_file "$LOCK_PATH" "deployment lock"
  else
    : >>"$LOCK_PATH" || die "unable to create deployment lock"
    chmod 600 "$LOCK_PATH" || die "unable to secure deployment lock"
    require_private_regular_file "$LOCK_PATH" "deployment lock"
  fi
  exec 9>>"$LOCK_PATH" || die "unable to open deployment lock"
  command -v flock >/dev/null 2>&1 || die "flock is required"
  flock -n 9 || die "deployment lock is unavailable"
}

prepare_evidence_file() {
  EVIDENCE_FILE="$evidence_dir/db-restore-rehearsal.log"
  if [[ -e "$EVIDENCE_FILE" || -L "$EVIDENCE_FILE" ]]; then
    require_private_regular_file "$EVIDENCE_FILE" "evidence log"
  else
    : >>"$EVIDENCE_FILE" || die "unable to create evidence log"
    chmod 600 "$EVIDENCE_FILE" || die "unable to secure evidence log"
    require_private_regular_file "$EVIDENCE_FILE" "evidence log"
  fi
}

psql_admin() {
  local db_args=(--no-password --no-psqlrc --set=ON_ERROR_STOP=1 --dbname=postgres)
  run_local_pg psql "${db_args[@]}" "$@"
}
psql_rehearsal() {
  run_local_pg psql --no-password --no-psqlrc --set=ON_ERROR_STOP=1 --dbname="$REHEARSAL_DB" "$@"
}
createdb_admin() {
  run_local_pg createdb --no-password --maintenance-db=postgres "$@"
}
dropdb_admin() {
  run_local_pg dropdb --no-password --maintenance-db=postgres "$@"
}
pg_restore_local() {
  run_local_pg pg_restore "$@"
}
pg_restore_source() {
  run_local_pg "$SOURCE_PG_RESTORE_BIN" "$@"
}
run_local_pg() {
  if [[ "$EUID" -eq 0 ]]; then
    runuser -u postgres -- env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin PGHOST=/var/run/postgresql PGPORT=5432 "$@"
  else
    PGSERVICE="$ADMIN_SERVICE" PGSERVICEFILE="$PGSERVICEFILE" PGPASSFILE="$PGPASSFILE" "$@"
  fi
}

terminate_and_drop() {
  psql_admin --tuples-only --no-align -c \
    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$REHEARSAL_DB' AND pid <> pg_backend_pid();" \
    >>"$EVIDENCE_FILE" 2>&1 || return 1
  dropdb_admin --if-exists "$REHEARSAL_DB" \
    >>"$EVIDENCE_FILE" 2>&1 || return 1
  absent="$(psql_admin --tuples-only --no-align -c "SELECT 1 FROM pg_database WHERE datname = '$REHEARSAL_DB';" 2>>"$EVIDENCE_FILE")" || return 1
  [[ -z "${absent//[[:space:]]/}" ]] || return 1
}

parse_common() {
  [[ $# -ge 2 ]] || usage
  command_name="$1"; shift
  [[ "$command_name" == restore || "$command_name" == cleanup || "$command_name" == assert-absent ]] || usage
  admin_seen=false
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --admin-service)
        [[ $# -ge 2 ]] || die "--admin-service requires a value"
        [[ "$2" == "$ADMIN_SERVICE" ]] || die "--admin-service must be ieum_target_admin"
        admin_seen=true; shift 2 ;;
      --admin-service=*)
        [[ "${1#*=}" == "$ADMIN_SERVICE" ]] || die "--admin-service must be ieum_target_admin"
        admin_seen=true; shift ;;
      --dump|--sha256|--evidence-dir)
        [[ "$command_name" == restore || "$1" == --evidence-dir ]] || die "$1 is not valid for $command_name"
        [[ $# -ge 2 ]] || die "$1 requires a value"
        case "$1" in --dump) dump="$2";; --sha256) sha256="$2";; --evidence-dir) evidence_dir="$2";; esac
        shift 2 ;;
      *) die "unsupported argument: $1" ;;
    esac
  done
  [[ "$admin_seen" == true ]] || die "--admin-service ieum_target_admin is required"
  require_libpq_files
}

restore() {
  : "${dump:?}"; : "${sha256:?}"; : "${evidence_dir:?}"
  require_dump "$dump"; require_evidence_dir "$evidence_dir"
  [[ "$sha256" =~ ^[0-9a-f]{64}$ ]] || die "--sha256 must be exactly 64 lowercase hexadecimal characters"
  configure_source_pg_restore
  prepare_evidence_file
  dump_snapshot="$(mktemp "$evidence_dir/.db-restore-rehearsal.dump.XXXXXX")" || die "unable to create dump snapshot"
  cp "$dump" "$dump_snapshot" || die "unable to snapshot dump"
  actual="$(sha256sum "$dump_snapshot" | awk '{print $1}')"
  [[ "$actual" == "$sha256" ]] || die "dump checksum does not match"
  acquire_lock
  existing="$(psql_admin --tuples-only --no-align -c "SELECT 1 FROM pg_database WHERE datname = '$REHEARSAL_DB';" 2>>"$EVIDENCE_FILE")" || die "unable to query rehearsal database presence"
  [[ -z "${existing//[[:space:]]/}" ]] || die "rehearsal database already exists"
  printf '%s\n' "restore begin" >>"$EVIDENCE_FILE"
  db_created=false
  trap 'rc=$?; rm -f "${dump_snapshot:-}" "${toc_raw:-}" "${toc_filtered:-}"; if [[ "$rc" -ne 0 && "${db_created:-false}" == true ]]; then if ! terminate_and_drop; then printf "%s\n" "CRITICAL: rehearsal cleanup failed" >&2; fi; fi; exit "$rc"' EXIT
  createdb_admin --template=template0 --owner="$APP_ROLE" "$REHEARSAL_DB" >>"$EVIDENCE_FILE" 2>&1
  db_created=true
  psql_rehearsal -c 'CREATE EXTENSION IF NOT EXISTS pgcrypto; CREATE EXTENSION IF NOT EXISTS postgis; CREATE EXTENSION IF NOT EXISTS vector;' >>"$EVIDENCE_FILE" 2>&1
  toc_raw="$(mktemp "$evidence_dir/.db-restore-rehearsal.toc.XXXXXX")" || die "unable to create restore TOC"
  toc_filtered="$(create_pg_restore_list rehearsal)"
  pg_restore_source --list <"$dump_snapshot" >"$toc_raw" 2>>"$EVIDENCE_FILE"
  awk '!($0 ~ /EXTENSION[[:space:]]+-[[:space:]]+(pgcrypto|postgis|vector)[[:space:]]*$/ || $0 ~ /COMMENT[[:space:]]+-[[:space:]]+EXTENSION[[:space:]]+(pgcrypto|postgis|vector)[[:space:]]*$/ || $0 ~ /TABLE DATA[[:space:]]+public[[:space:]]+spatial_ref_sys([[:space:]]|$)/)' "$toc_raw" >"$toc_filtered"
  pg_restore_source --no-password --single-transaction --exit-on-error --no-owner --no-acl --role="$APP_ROLE" --dbname="$REHEARSAL_DB" --use-list="$toc_filtered" <"$dump_snapshot" \
    >>"$EVIDENCE_FILE" 2>&1
  rm -f "$dump_snapshot" "$toc_raw" "$toc_filtered"
  printf '%s\n' "restore complete" >>"$EVIDENCE_FILE"
  db_created=false
  trap - EXIT
}

cleanup_cmd() {
  : "${evidence_dir:?}"; require_evidence_dir "$evidence_dir"
  prepare_evidence_file; acquire_lock
  terminate_and_drop || die "rehearsal database remains present"
  printf '%s\n' "cleanup complete" >>"$EVIDENCE_FILE"
}

assert_absent() {
  acquire_lock
  absent="$(psql_admin --tuples-only --no-align -c "SELECT 1 FROM pg_database WHERE datname = '$REHEARSAL_DB';")" || die "unable to query rehearsal database presence"
  [[ -z "${absent//[[:space:]]/}" ]] || die "rehearsal database is present"
}

command_name=''; dump=''; sha256=''; evidence_dir=''; admin_seen=false
parse_common "$@"
case "$command_name" in restore) restore;; cleanup) cleanup_cmd;; assert-absent) assert_absent;; esac
