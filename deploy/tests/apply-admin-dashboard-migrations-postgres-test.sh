#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
helper="$root/deploy/scripts/apply-admin-dashboard-migrations.sh"
work_dir="$(mktemp -d)"
container_name="ieum-admin-migration-test-$$-$RANDOM"
postgres_image="${POSTGRES_TEST_IMAGE:-postgres:16-alpine}"

cleanup() {
  docker rm -f "$container_name" >/dev/null 2>&1 || true
  rm -rf "$work_dir"
}
trap cleanup EXIT

fail() {
  echo "admin dashboard PostgreSQL migration test failed: $*" >&2
  exit 1
}

command -v docker >/dev/null 2>&1 || fail "docker is required"
test -x "$helper" || fail "migration helper is missing or not executable"

docker run --detach --rm \
  --name "$container_name" \
  --env POSTGRES_PASSWORD=test-password \
  --env POSTGRES_DB=ieum \
  --mount "type=bind,src=$root,dst=$root,readonly" \
  --workdir "$root" \
  "$postgres_image" >/dev/null

ready=false
for _ in $(seq 1 60); do
  if docker exec "$container_name" pg_isready -h 127.0.0.1 -U postgres -d ieum >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
[[ "$ready" == true ]] || fail "PostgreSQL did not become ready"

sql() {
  docker exec "$container_name" \
    psql --no-psqlrc --set=ON_ERROR_STOP=1 --tuples-only --no-align \
      --username postgres --dbname ieum --command "$1"
}

fake_bin="$work_dir/bin"
mkdir -p "$fake_bin"
cat > "$fake_bin/psql" <<'PSQL_WRAPPER'
#!/usr/bin/env bash
set -euo pipefail
exec docker exec --interactive --workdir "$MIGRATION_ROOT" "$MIGRATION_CONTAINER" \
  psql --username postgres --dbname ieum "$@"
PSQL_WRAPPER
chmod +x "$fake_bin/psql"

sql "
  CREATE SCHEMA trap;
  CREATE TABLE public.users (user_id bigint PRIMARY KEY);
  CREATE TABLE trap.users (user_id bigint PRIMARY KEY);
  CREATE FUNCTION trap.hashtextextended(text, bigint) RETURNS bigint
    LANGUAGE sql AS 'SELECT 0::bigint';
  CREATE FUNCTION trap.pg_advisory_lock(bigint) RETURNS void
    LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''unsafe search_path''; END';
  ALTER ROLE postgres IN DATABASE ieum SET search_path = trap, pg_catalog, public;
" >/dev/null

run_helper() {
  PATH="$fake_bin:$PATH" \
    MIGRATION_ROOT="$root" \
    MIGRATION_CONTAINER="$container_name" \
    PGHOST=private-rds.invalid \
    PGPORT=5432 \
    PGDATABASE=ieum \
    PGUSER=postgres \
    PGPASSWORD=test-password \
    "$helper"
}

run_helper >/dev/null

schema_state="$(sql "
  SELECT concat_ws(':',
    to_regclass('public.admin_audit_logs') IS NOT NULL,
    to_regclass('trap.admin_audit_logs') IS NULL,
    EXISTS (
      SELECT 1 FROM pg_attribute
      WHERE attrelid = 'public.users'::regclass
        AND attname = 'auth_version'
        AND attnum > 0
        AND NOT attisdropped
    ),
    NOT EXISTS (
      SELECT 1 FROM pg_attribute
      WHERE attrelid = 'trap.users'::regclass
        AND attname = 'auth_version'
        AND attnum > 0
        AND NOT attisdropped
    )
  );
")"
[[ "$schema_state" == "t:t:t:t" ]] \
  || fail "DDL escaped public under a hostile search_path: $schema_state"

constraint_oid_before="$(sql "
  SELECT oid
  FROM pg_constraint
  WHERE conrelid = 'public.users'::regclass
    AND conname = 'ck_users_auth_version_nonnegative';
")"
run_helper >/dev/null
constraint_oid_after="$(sql "
  SELECT oid
  FROM pg_constraint
  WHERE conrelid = 'public.users'::regclass
    AND conname = 'ck_users_auth_version_nonnegative';
")"
[[ -n "$constraint_oid_before" && "$constraint_oid_before" == "$constraint_oid_after" ]] \
  || fail "an exact v25 rerun replaced the users constraint"

sql "ALTER SEQUENCE public.admin_audit_logs_audit_id_seq MAXVALUE 100 CYCLE CACHE 2;" >/dev/null
if run_helper >/dev/null 2>&1; then
  fail "helper accepted incompatible audit sequence properties"
fi

sequence_state="$(sql "
  SELECT concat_ws(':', seqmax, seqcache, seqcycle)
  FROM pg_sequence
  WHERE seqrelid = 'public.admin_audit_logs_audit_id_seq'::regclass;
")"
[[ "$sequence_state" == "100:2:t" ]] \
  || fail "failed preflight unexpectedly rewrote the incompatible sequence: $sequence_state"

echo "Admin dashboard PostgreSQL migration tests passed."
