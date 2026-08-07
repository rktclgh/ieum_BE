#!/usr/bin/env bash
set -euo pipefail
umask 077

PRODUCTION_DB=ieum
ADMIN_SERVICE=ieum_target_admin
SOURCE_PG_RESTORE_BIN=/usr/lib/postgresql/18/bin/pg_restore
TARGET_PG_DUMP_BIN=/usr/lib/postgresql/17/bin/pg_dump
TARGET_PG_RESTORE_BIN=/usr/lib/postgresql/17/bin/pg_restore

if [[ "$EUID" -eq 0 ]]; then
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
  export PATH
  LOCK_PATH=/var/lib/ieum/locks/deploy.lock
  WRITE_FENCE_PATH=/var/lib/ieum/maintenance/write-fence
  PGSERVICEFILE=/etc/ieum/postgres.pg_service.conf
  PGPASSFILE=/etc/ieum/postgres.pgpass
  export PGSERVICEFILE PGPASSFILE
else
  LOCK_PATH="${IEUM_DEPLOY_LOCK_PATH:-/var/lib/ieum/locks/deploy.lock}"
  WRITE_FENCE_PATH="${IEUM_PRODUCTION_WRITE_FENCE_PATH:-/var/lib/ieum/maintenance/write-fence}"
fi

die() { echo "db restore production: $*" >&2; exit 2; }
usage() {
  echo "usage: $0 restore --admin-service ieum_target_admin --dump <absolute-file> --sha256 <64-lowercase-hex> --evidence-dir <absolute-dir>" >&2
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

require_dump() {
  is_absolute "$dump" || die "--dump must be absolute"
  require_private_regular_file "$dump" --dump
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

require_evidence_dir() {
  is_absolute "$evidence_dir" || die "--evidence-dir must be absolute"
  [[ -d "$evidence_dir" && ! -L "$evidence_dir" ]] || die "--evidence-dir must be a regular non-symlink directory"
  same_owner "$evidence_dir" || die "--evidence-dir must be owned by the invoking user"
  private_dir_mode "$evidence_dir" || die "--evidence-dir must have mode 0700"
}

require_write_fence() {
  [[ -f "$WRITE_FENCE_PATH" && ! -L "$WRITE_FENCE_PATH" ]] || die "production write fence is required"
  require_private_regular_file "$WRITE_FENCE_PATH" "production write fence"
  [[ "$(wc -l <"$WRITE_FENCE_PATH")" -eq 1 && "$(cat "$WRITE_FENCE_PATH")" == 'IEUM_PRODUCTION_WRITE_FENCE=enabled' ]] || die "production write fence is invalid"
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

psql_admin() {
  run_local_pg psql --no-password --no-psqlrc --set=ON_ERROR_STOP=1 --dbname=postgres "$@"
}
run_local_pg() {
  if [[ "$EUID" -eq 0 ]]; then
    runuser -u postgres -- env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin PGHOST=/var/run/postgresql PGPORT=5432 "$@"
  else
    PGSERVICE="$ADMIN_SERVICE" PGSERVICEFILE="$PGSERVICEFILE" PGPASSFILE="$PGPASSFILE" "$@"
  fi
}

require_target_database() {
  local present
  present="$(psql_admin --tuples-only --no-align -c "SELECT 1 FROM pg_database WHERE datname = '$PRODUCTION_DB';")" || die "unable to query production target database presence"
  [[ -n "${present//[[:space:]]/}" ]] || die "production target database is not present"
}

require_rehearsal_database_absent() {
  local present
  present="$(psql_admin --tuples-only --no-align -c "SELECT 1 FROM pg_database WHERE datname = 'ieum_rehearsal';")" || die "unable to query rehearsal database presence"
  [[ -z "${present//[[:space:]]/}" ]] || die "rehearsal database must be absent before production restore"
}

configure_target_clients() {
  if [[ -n "${IEUM_TARGET_PG_DUMP_BIN:-}" || -n "${IEUM_TARGET_PG_RESTORE_BIN:-}" ]]; then
    [[ "${IEUM_TEST_MODE:-0}" == 1 ]] || die "target PostgreSQL client override is test-only"
    TARGET_PG_DUMP_BIN="${IEUM_TARGET_PG_DUMP_BIN:-$TARGET_PG_DUMP_BIN}"
    TARGET_PG_RESTORE_BIN="${IEUM_TARGET_PG_RESTORE_BIN:-$TARGET_PG_RESTORE_BIN}"
  fi
  [[ -f "$TARGET_PG_DUMP_BIN" && -x "$TARGET_PG_DUMP_BIN" && ! -L "$TARGET_PG_DUMP_BIN" ]] || die "PostgreSQL 17 pg_dump client is required"
  [[ -f "$TARGET_PG_RESTORE_BIN" && -x "$TARGET_PG_RESTORE_BIN" && ! -L "$TARGET_PG_RESTORE_BIN" ]] || die "PostgreSQL 17 pg_restore client is required"
  local client version client_dir client_mode
  for client in "$TARGET_PG_DUMP_BIN" "$TARGET_PG_RESTORE_BIN"; do
    version="$($client --version 2>/dev/null)" || die "unable to verify PostgreSQL 17 target client"
    [[ "$version" =~ PostgreSQL\)[[:space:]]+17([.[:space:]]|$) ]] || die "target database client must be PostgreSQL 17"
    if [[ "$EUID" -eq 0 ]]; then
      [[ "$(owner_of "$client")" == 0 ]] || die "PostgreSQL 17 target client must be root-owned"
      client_dir="${client%/*}"
      while [[ "$client_dir" != / ]]; do
        [[ -d "$client_dir" && ! -L "$client_dir" && "$(owner_of "$client_dir")" == 0 ]] || die "PostgreSQL 17 target client parent directory is unsafe"
        client_mode="$(mode_of "$client_dir")"
        [[ "$client_mode" =~ ^[0-7]+$ ]] && (( (8#$client_mode & 8#022) == 0 )) || die "PostgreSQL 17 target client parent directory is writable by group or other"
        client_dir="${client_dir%/*}"; [[ -n "$client_dir" ]] || client_dir=/
      done
    fi
  done
}

prepare_evidence_file() {
  EVIDENCE_FILE="$evidence_dir/db-restore-production.log"
  if [[ -e "$EVIDENCE_FILE" || -L "$EVIDENCE_FILE" ]]; then
    require_private_regular_file "$EVIDENCE_FILE" "evidence log"
  else
    : >>"$EVIDENCE_FILE" || die "unable to create evidence log"
    chmod 600 "$EVIDENCE_FILE" || die "unable to secure evidence log"
    require_private_regular_file "$EVIDENCE_FILE" "evidence log"
  fi
}

snapshot_and_verify_source_dump() {
  source_dump_snapshot="$(mktemp "$evidence_dir/.db-restore-production.input.XXXXXX")" || die "unable to create source dump snapshot"
  cp "$dump" "$source_dump_snapshot" || die "unable to snapshot source dump"
  actual_sha256="$(sha256sum "$source_dump_snapshot" | awk '{print $1}')"
  [[ "$actual_sha256" == "$sha256" ]] || die "dump checksum does not match"
}

backup_current_target() {
  target_backup="$(mktemp "$evidence_dir/target-before-restore.XXXXXX")" || die "unable to create target backup path"
  run_local_pg "$TARGET_PG_DUMP_BIN" --no-password --format=custom --no-owner --no-acl --dbname="$PRODUCTION_DB" \
    >"$target_backup" 2>>"$EVIDENCE_FILE" || die "unable to back up current production target database"
  require_private_regular_file "$target_backup" "target backup"
  target_backup_sha256="$(sha256sum "$target_backup" | awk '{print $1}')"
  [[ "$target_backup_sha256" =~ ^[0-9a-f]{64}$ ]] || die "unable to checksum target backup"
  target_backup_toc_raw="$(mktemp "$evidence_dir/.db-restore-production.target-toc.XXXXXX")" || die "unable to create target backup TOC"
  run_local_pg "$TARGET_PG_RESTORE_BIN" --list <"$target_backup" >"$target_backup_toc_raw" 2>>"$EVIDENCE_FILE" || die "unable to inspect target backup TOC"
  [[ -s "$target_backup_toc_raw" ]] || die "target backup TOC is empty"
  printf '%s  %s\n' "$target_backup_sha256" "$(basename "$target_backup")" >"$evidence_dir/target-before-restore.sha256" || die "unable to write target backup checksum"
  chmod 600 "$evidence_dir/target-before-restore.sha256" || die "unable to secure target backup checksum"
  require_private_regular_file "$evidence_dir/target-before-restore.sha256" "target backup checksum"
}

validate_source_dump() {
  local source_toc
  source_toc="$(mktemp "$evidence_dir/.db-restore-production.source-toc.XXXXXX")" || die "unable to create source dump TOC"
  pg_restore_source --list <"$source_dump_snapshot" >"$source_toc" 2>>"$EVIDENCE_FILE" || die "unable to inspect source dump TOC"
  [[ -s "$source_toc" ]] || die "source dump TOC is empty"
  rm -f "$source_toc"
}

terminate_current_target() {
  psql_admin --tuples-only --no-align -c \
    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$PRODUCTION_DB' AND pid <> pg_backend_pid();" \
    >>"$EVIDENCE_FILE" 2>&1
  local remaining
  remaining="$(psql_admin --tuples-only --no-align -c \
    "SELECT count(*) FROM pg_stat_activity WHERE datname = '$PRODUCTION_DB' AND pid <> pg_backend_pid();" \
    2>>"$EVIDENCE_FILE")" || return 1
  [[ -z "${remaining//[[:space:]]/}" || "${remaining//[[:space:]]/}" == 0 ]] || return 1
}

createdb_admin() {
  run_local_pg createdb --no-password --maintenance-db=postgres "$@"
}

dropdb_admin() {
  run_local_pg dropdb --no-password --maintenance-db=postgres "$@"
}

psql_production() {
  run_local_pg psql --no-password --no-psqlrc --set=ON_ERROR_STOP=1 --dbname="$PRODUCTION_DB" "$@"
}
pg_restore_source() {
  run_local_pg "$SOURCE_PG_RESTORE_BIN" "$@"
}

filter_extension_entries() {
  awk '!($0 ~ /EXTENSION[[:space:]]+-[[:space:]]+(pgcrypto|postgis|vector)[[:space:]]*$/ || $0 ~ /COMMENT[[:space:]]+-[[:space:]]+EXTENSION[[:space:]]+(pgcrypto|postgis|vector)[[:space:]]*$/ || $0 ~ /TABLE DATA[[:space:]]+public[[:space:]]+spatial_ref_sys([[:space:]]|$)/)' "$1" >"$2"
}

restore_captured_target() {
  local actual_backup_sha256
  actual_backup_sha256="$(sha256sum "$target_backup" | awk '{print $1}')" || return 1
  [[ "$actual_backup_sha256" == "$target_backup_sha256" ]] || return 1
  printf '%s\n' "rollback begin" >>"$EVIDENCE_FILE"
  terminate_current_target || return 1
  dropdb_admin --if-exists "$PRODUCTION_DB" >>"$EVIDENCE_FILE" 2>&1 || return 1
  createdb_admin --template=template0 --owner=ieum "$PRODUCTION_DB" >>"$EVIDENCE_FILE" 2>&1 || return 1
  psql_production -c 'CREATE EXTENSION IF NOT EXISTS pgcrypto; CREATE EXTENSION IF NOT EXISTS postgis; CREATE EXTENSION IF NOT EXISTS vector;' >>"$EVIDENCE_FILE" 2>&1 || return 1
  rollback_toc_raw="$(mktemp "$evidence_dir/.db-restore-production.rollback-toc.XXXXXX")" || return 1
  rollback_toc_filtered="$(create_pg_restore_list production-rollback)" || return 1
  run_local_pg "$TARGET_PG_RESTORE_BIN" --list <"$target_backup" >"$rollback_toc_raw" 2>>"$EVIDENCE_FILE" || return 1
  filter_extension_entries "$rollback_toc_raw" "$rollback_toc_filtered" || return 1
  run_local_pg "$TARGET_PG_RESTORE_BIN" --no-password --single-transaction --exit-on-error --no-owner --no-acl --role=ieum --dbname="$PRODUCTION_DB" --use-list="$rollback_toc_filtered" <"$target_backup" \
    >>"$EVIDENCE_FILE" 2>&1 || return 1
  printf '%s\n' "rollback complete" >>"$EVIDENCE_FILE"
}

on_exit() {
  local rc=$1
  trap - EXIT
  set +e
  if [[ "$rc" -ne 0 && "${target_restore_required:-false}" == true ]]; then
    if ! restore_captured_target; then
      printf '%s\n' "CRITICAL: production target rollback failed" >&2
    fi
  fi
  rm -f "${source_dump_snapshot:-}" "${target_backup_toc_raw:-}" "${toc_raw:-}" "${toc_filtered:-}" "${rollback_toc_raw:-}" "${rollback_toc_filtered:-}"
  exit "$rc"
}

replace_target_with_source_dump() {
  printf '%s\n' "restore begin" >>"$EVIDENCE_FILE"
  terminate_current_target || die "production target database still has non-admin connections"
  require_write_fence
  # Once destructive replacement is attempted, the write fence makes the
  # captured backup authoritative even if dropdb loses its connection after
  # committing the DROP.
  target_restore_required=true
  dropdb_admin --if-exists "$PRODUCTION_DB" >>"$EVIDENCE_FILE" 2>&1 || die "unable to drop production target database"
  createdb_admin --template=template0 --owner=ieum "$PRODUCTION_DB" >>"$EVIDENCE_FILE" 2>&1 || die "unable to recreate production target database"
  psql_production -c 'CREATE EXTENSION IF NOT EXISTS pgcrypto; CREATE EXTENSION IF NOT EXISTS postgis; CREATE EXTENSION IF NOT EXISTS vector;' >>"$EVIDENCE_FILE" 2>&1 || die "unable to create required production extensions"
  toc_raw="$(mktemp "$evidence_dir/.db-restore-production.toc.XXXXXX")" || die "unable to create restore TOC"
  toc_filtered="$(create_pg_restore_list production-source)"
  pg_restore_source --list <"$source_dump_snapshot" >"$toc_raw" 2>>"$EVIDENCE_FILE" || die "unable to inspect source dump TOC"
  filter_extension_entries "$toc_raw" "$toc_filtered" || die "unable to filter source dump TOC"
  pg_restore_source --no-password --single-transaction --exit-on-error --no-owner --no-acl --role=ieum --dbname="$PRODUCTION_DB" --use-list="$toc_filtered" <"$source_dump_snapshot" \
    >>"$EVIDENCE_FILE" 2>&1 || die "unable to restore production source dump"
  printf '%s\n' "restore complete" >>"$EVIDENCE_FILE"
  target_restore_required=false
}

command_name="${1:-}"
[[ "$command_name" == restore ]] || usage
shift

admin_seen=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --admin-service)
      [[ $# -ge 2 ]] || die "--admin-service requires a value"
      [[ "$2" == "$ADMIN_SERVICE" ]] || die "--admin-service must be ieum_target_admin"
      admin_seen=true
      shift 2
      ;;
    --dump|--sha256|--evidence-dir)
      [[ $# -ge 2 ]] || die "$1 requires a value"
      case "$1" in
        --dump) dump="$2" ;;
        --sha256) sha256="$2" ;;
        --evidence-dir) evidence_dir="$2" ;;
      esac
      shift 2
      ;;
    *) die "unsupported argument: $1" ;;
  esac
done

[[ "$admin_seen" == true ]] || die "--admin-service ieum_target_admin is required"
: "${dump:?--dump is required}"
: "${sha256:?--sha256 is required}"
: "${evidence_dir:?--evidence-dir is required}"
require_dump
require_evidence_dir
[[ "$sha256" =~ ^[0-9a-f]{64}$ ]] || die "--sha256 must be exactly 64 lowercase hexadecimal characters"
require_libpq_files
acquire_lock
require_write_fence
require_target_database
require_rehearsal_database_absent
configure_target_clients
configure_source_pg_restore
prepare_evidence_file
source_dump_snapshot=''
target_backup_toc_raw=''
toc_raw=''
toc_filtered=''
rollback_toc_raw=''
rollback_toc_filtered=''
target_backup=''
target_backup_sha256=''
target_restore_required=false
trap 'on_exit "$?"' EXIT
snapshot_and_verify_source_dump
backup_current_target
validate_source_dump
replace_target_with_source_dump
