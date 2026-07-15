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

v25_line="$(grep -n -m1 -F '\i db/migrations/v25_user_auth_version.sql' "$stdin_file" | cut -d: -f1)"
v26_line="$(grep -n -m1 -F '\i db/migrations/v26_admin_audit_logs.sql' "$stdin_file" | cut -d: -f1)"
test -n "$v25_line" && test -n "$v26_line" \
  || fail "both migrations must be applied"
(( v25_line < v26_line )) || fail "v25 must run before v26"

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
