#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
helper="$root/deploy/scripts/apply-admin-dashboard-migrations.sh"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

fail() {
  echo "admin dashboard migration helper test failed: $*" >&2
  exit 1
}

test -x "$helper" || fail "migration helper is missing or not executable"

fake_bin="$work_dir/bin"
capture_dir="$work_dir/capture"
mkdir -p "$fake_bin" "$capture_dir"

cat > "$fake_bin/psql" <<'FAKE_PSQL'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" > "$CAPTURE_DIR/args"
printf '%s\n' "${PGHOST:-}" "${PGPORT:-}" "${PGDATABASE:-}" "${PGUSER:-}" \
  > "$CAPTURE_DIR/connection"
if [[ -n "${PGPASSWORD:-}" ]]; then
  printf 'environment\n' > "$CAPTURE_DIR/password-transport"
else
  printf 'absent\n' > "$CAPTURE_DIR/password-transport"
fi
cat > "$CAPTURE_DIR/stdin"
exit "${FAKE_PSQL_EXIT:-0}"
FAKE_PSQL
chmod +x "$fake_bin/psql"

if env -u PGHOST -u PGPORT -u PGDATABASE -u PGUSER \
  PATH="$fake_bin:$PATH" \
  CAPTURE_DIR="$capture_dir" \
  "$helper" >/dev/null 2>&1; then
  fail "helper accepted missing libpq connection variables"
fi

PATH="$fake_bin:$PATH" \
  CAPTURE_DIR="$capture_dir" \
  PGHOST=example.invalid \
  PGPORT=5432 \
  PGDATABASE=ieum \
  PGUSER=admin \
  PGPASSWORD=secret \
  "$helper" >/dev/null

expected_connection=$'example.invalid\n5432\nieum\nadmin'
test "$(cat "$capture_dir/connection")" = "$expected_connection" \
  || fail "libpq connection variables were not inherited"
if grep -Fq 'secret' "$capture_dir/args"; then
  fail "database password leaked into the psql process arguments"
fi
test "$(cat "$capture_dir/password-transport")" = 'environment' \
  || fail "database password was not inherited through the process environment"
grep -Fxq -- '--no-psqlrc' "$capture_dir/args" \
  || fail "psql must ignore user startup files"
grep -Fxq -- '--set=ON_ERROR_STOP=1' "$capture_dir/args" \
  || fail "psql must fail fast"

stdin_file="$capture_dir/stdin"
grep -Fq "pg_advisory_lock" "$stdin_file" \
  || fail "session advisory lock is missing"
grep -Fq "auth_version_contract_state" "$stdin_file" \
  || fail "auth_version preflight/final verification is missing"
grep -Fq "admin_audit_contract_state" "$stdin_file" \
  || fail "audit schema preflight/final verification is missing"
grep -Fq "partial or incompatible users.auth_version schema" "$stdin_file" \
  || fail "partial auth schema must fail explicitly"
grep -Fq "partial or incompatible admin_audit_logs schema" "$stdin_file" \
  || fail "partial audit schema must fail explicitly"
grep -Fq "apply_admin_audit_migration" "$stdin_file" \
  || fail "an exact existing audit schema must skip the non-idempotent v26 file"
grep -Fq "apply_auth_version_migration" "$stdin_file" \
  || fail "an exact existing auth schema must skip the locking v25 file"
grep -Fq "SET search_path = pg_catalog, public" "$stdin_file" \
  || fail "migration session must pin trusted catalog resolution before running qualified DDL"
search_path_line="$(grep -n -m1 -F 'SET search_path = pg_catalog, public' "$stdin_file" | cut -d: -f1)"
advisory_lock_line="$(grep -n -m1 -F 'SELECT pg_advisory_lock' "$stdin_file" | cut -d: -f1)"
test -n "$search_path_line" && test -n "$advisory_lock_line" \
  && (( search_path_line < advisory_lock_line )) \
  || fail "trusted search_path must be pinned before the advisory-lock function call"

required_exact_catalog_tokens=(
  "relpersistence = 'p'"
  "attribute.attnum"
  "attribute.attgenerated"
  "attribute.attidentity"
  "pg_get_serial_sequence"
  "pg_sequence"
  "sequence_row.seqtypid = 'bigint'::regtype"
  "sequence_row.seqstart = 1"
  "sequence_row.seqincrement = 1"
  "sequence_row.seqmax = 9223372036854775807"
  "sequence_row.seqmin = 1"
  "sequence_row.seqcache = 1"
  "NOT sequence_row.seqcycle"
  "dependency.deptype = 'a'"
  "constraint_row.conkey"
  "constraint_row.confkey"
  "constraint_row.convalidated"
  "constraint_row.confmatchtype"
  "constraint_row.confupdtype"
  "constraint_row.confdeltype"
  "constraint_row.condeferrable"
  "constraint_row.condeferred"
  "index_row.indisvalid"
  "index_row.indisready"
  "index_row.indisunique"
  "index_row.indpred IS NULL"
  "index_row.indkey::text"
  "index_row.indoption::text"
)
for token in "${required_exact_catalog_tokens[@]}"; do
  grep -Fq "$token" "$stdin_file" \
    || fail "exact catalog verification is missing: $token"
done
if grep -Fq "indexdef LIKE" "$stdin_file"; then
  fail "index verification must not rely on permissive text matching"
fi
if grep -Fq "pg_get_expr(conbin, conrelid) LIKE" "$stdin_file"; then
  fail "check verification must compare normalized expressions exactly"
fi

v24_line="$(grep -n -m1 -F '\i db/migrations/v24_seed_report_policy_rules.sql' "$stdin_file" | cut -d: -f1 || true)"
v25_line="$(grep -n -m1 -F '\i db/migrations/v25_user_auth_version.sql' "$stdin_file" | cut -d: -f1 || true)"
v26_line="$(grep -n -m1 -F '\i db/migrations/v26_admin_audit_logs.sql' "$stdin_file" | cut -d: -f1 || true)"
v27_line="$(grep -n -m1 -F '\i db/migrations/v27_report_policy_sanction_durations.sql' "$stdin_file" | cut -d: -f1 || true)"
test -n "$v24_line" && test -n "$v25_line" && test -n "$v26_line" && test -n "$v27_line" \
	|| fail "all v24-v27 migrations must be applied"
(( v24_line < v27_line )) || fail "v24 must run before v27"
(( v25_line < v26_line )) || fail "v25 must run before v26"
report_policy_guard_line="$(grep -n -m1 -F '\if :apply_report_policy_migrations' "$stdin_file" | cut -d: -f1 || true)"
test -n "$report_policy_guard_line" && (( report_policy_guard_line < v24_line )) \
	|| fail "report policy migrations must be guarded by table presence"
auth_guard_line="$(grep -n -m1 -F '\if :apply_auth_version_migration' "$stdin_file" | cut -d: -f1 || true)"
test -n "$auth_guard_line" && (( auth_guard_line < v25_line )) \
  || fail "v25 must be guarded by the auth absent-state flag"

if PATH="$fake_bin:$PATH" \
  CAPTURE_DIR="$capture_dir" \
  FAKE_PSQL_EXIT=23 \
  PGHOST=example.invalid \
  PGPORT=5432 \
  PGDATABASE=ieum \
  PGUSER=admin \
  PGPASSWORD=secret \
  "$helper" >/dev/null 2>&1; then
  fail "helper hid a psql failure"
fi

echo "Admin dashboard migration helper tests passed."
