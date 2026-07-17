#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
helper="$root/deploy/scripts/apply-admin-dashboard-migrations.sh"
workflow="$root/.github/workflows/deploy-app-main.yml"
ai_workflow="$root/.github/workflows/deploy-app-ai.yml"
env_example="$root/deploy/env/app-main.env.example"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

fail() {
  echo "admin dashboard migration helper test failed: $*" >&2
  exit 1
}

test -x "$helper" || fail "migration helper is missing or not executable"
test -s "$workflow" || fail "app-main deployment workflow is missing"
test -s "$ai_workflow" || fail "app-ai deployment workflow is missing"
test -s "$env_example" || fail "app-main environment example is missing"

v32_migration="$root/db/migrations/v32_chat_message_reply.sql"
test -s "$v32_migration" || fail "v32 reply migration is missing"
v32_begin_line="$(grep -n -m1 -Fx 'BEGIN;' "$v32_migration" | cut -d: -f1 || true)"
v32_add_column_line="$(grep -n -m1 -F 'ADD COLUMN reply_to_message_id' "$v32_migration" | cut -d: -f1 || true)"
v32_fk_line="$(grep -n -m1 -F 'ADD CONSTRAINT fk_messages_reply_to_message' "$v32_migration" | cut -d: -f1 || true)"
v32_commit_line="$(grep -n -m1 -Fx 'COMMIT;' "$v32_migration" | cut -d: -f1 || true)"
test -n "$v32_begin_line" && test -n "$v32_add_column_line" && test -n "$v32_fk_line" && test -n "$v32_commit_line" \
  && (( v32_begin_line < v32_add_column_line )) && (( v32_add_column_line < v32_fk_line )) && (( v32_fk_line < v32_commit_line )) \
  || fail "v32 reply migration must atomically wrap column and foreign-key DDL"

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

runtime_env="$work_dir/.env.runtime"
cat > "$runtime_env" <<'RUNTIME_ENV'
SPRING_DATASOURCE_URL=jdbc:postgresql://private-rds.invalid:5432/ieum?sslmode=require
SPRING_DATASOURCE_USERNAME=migration_user
SPRING_DATASOURCE_PASSWORD='migration-secret'
RUNTIME_ENV
chmod 600 "$runtime_env"

env -u PGHOST -u PGPORT -u PGDATABASE -u PGUSER -u PGPASSWORD \
  PATH="$fake_bin:$PATH" \
  CAPTURE_DIR="$capture_dir" \
  MIGRATION_RUNTIME_ENV="$runtime_env" \
  "$helper" >/dev/null

expected_runtime_connection=$'private-rds.invalid\n5432\nieum\nmigration_user'
test "$(cat "$capture_dir/connection")" = "$expected_runtime_connection" \
  || fail "runtime datasource was not converted to libpq connection variables"
test "$(cat "$capture_dir/password-transport")" = 'environment' \
  || fail "runtime datasource password was not inherited through the environment"

stdin_file="$capture_dir/stdin"
grep -Fq "pg_advisory_lock" "$stdin_file" \
  || fail "session advisory lock is missing"
grep -Fq "hashtextextended('ieum:admin-dashboard:v25-v26', 0)" "$stdin_file" \
  || fail "migration helper must retain the deployed advisory-lock namespace"
if grep -Fq "hashtextextended('ieum:admin-dashboard:v25-v32', 0)" "$stdin_file"; then
  fail "migration helper must not split migration serialization onto a new advisory-lock namespace"
fi
grep -Fq "auth_version_contract_state" "$stdin_file" \
  || fail "auth_version preflight/final verification is missing"
grep -Fq "admin_audit_contract_state" "$stdin_file" \
  || fail "audit schema preflight/final verification is missing"
grep -Fq "message_type_contract_state" "$stdin_file" \
  || fail "message type preflight/final verification is missing"
grep -Fq "message_reply_contract_state" "$stdin_file" \
  || fail "message reply preflight/final verification is missing"
grep -Fq "partial or incompatible users.auth_version schema" "$stdin_file" \
  || fail "partial auth schema must fail explicitly"
grep -Fq "partial or incompatible admin_audit_logs schema" "$stdin_file" \
  || fail "partial audit schema must fail explicitly"
grep -Fq "partial or incompatible messages.message_type schema" "$stdin_file" \
  || fail "partial message type schema must fail explicitly"
grep -Fq "partial or incompatible messages.reply_to_message_id schema" "$stdin_file" \
  || fail "partial message reply schema must fail explicitly"
grep -Fq "apply_admin_audit_migration" "$stdin_file" \
  || fail "an exact existing audit schema must skip the non-idempotent v26 file"
grep -Fq "apply_auth_version_migration" "$stdin_file" \
  || fail "an exact existing auth schema must skip the locking v25 file"
grep -Fq "apply_message_type_migration" "$stdin_file" \
  || fail "an exact existing message type schema must skip the non-idempotent v28 file"
grep -Fq "apply_message_reply_migration" "$stdin_file" \
  || fail "an exact existing message reply schema must skip the non-idempotent v32 file"
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
required_message_type_catalog_tokens=(
  "'public.messages'::regclass"
  "attribute.atttypid = 'character varying'::regtype"
  "attribute.atttypmod = 20"
  "ck_messages_message_type"
  "ck_messages_system_text_only"
  "constraint_row.conkey"
  "constraint_row.convalidated"
)
for token in "${required_message_type_catalog_tokens[@]}"; do
  grep -Fq "$token" "$stdin_file" \
    || fail "message type exact catalog verification is missing: $token"
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
v28_line="$(grep -n -m1 -F '\i db/migrations/v28_chat_system_messages.sql' "$stdin_file" | cut -d: -f1 || true)"
v29_line="$(grep -n -m1 -F '\i db/migrations/v29_meeting_schedule_details.sql' "$stdin_file" | cut -d: -f1 || true)"
v30_line="$(grep -n -m1 -F '\i db/migrations/v30_report_schedule_target_enum.sql' "$stdin_file" | cut -d: -f1 || true)"
v31_line="$(grep -n -m1 -F '\i db/migrations/v31_report_schedule_target.sql' "$stdin_file" | cut -d: -f1 || true)"
v32_line="$(grep -n -m1 -F '\i db/migrations/v32_chat_message_reply.sql' "$stdin_file" | cut -d: -f1 || true)"
test -n "$v24_line" && test -n "$v25_line" && test -n "$v26_line" && test -n "$v27_line" && test -n "$v28_line" \
  && test -n "$v29_line" && test -n "$v30_line" && test -n "$v31_line" && test -n "$v32_line" \
	|| fail "all v24-v32 migrations must be applied"
(( v24_line < v27_line )) || fail "v24 must run before v27"
(( v25_line < v26_line )) || fail "v25 must run before v26"
(( v26_line < v28_line )) || fail "v26 must run before v28"
(( v28_line < v29_line )) || fail "v28 must run before v29"
(( v29_line < v30_line )) || fail "v29 must run before v30"
(( v30_line < v31_line )) || fail "v30 must run before v31"
(( v31_line < v32_line )) || fail "v31 must run before v32"
report_policy_guard_line="$(grep -n -m1 -F '\if :apply_report_policy_migrations' "$stdin_file" | cut -d: -f1 || true)"
test -n "$report_policy_guard_line" && (( report_policy_guard_line < v24_line )) \
	|| fail "report policy migrations must be guarded by table presence"
auth_guard_line="$(grep -n -m1 -F '\if :apply_auth_version_migration' "$stdin_file" | cut -d: -f1 || true)"
test -n "$auth_guard_line" && (( auth_guard_line < v25_line )) \
  || fail "v25 must be guarded by the auth absent-state flag"
message_type_guard_line="$(grep -n -m1 -F '\if :apply_message_type_migration' "$stdin_file" | cut -d: -f1 || true)"
test -n "$message_type_guard_line" && (( message_type_guard_line < v28_line )) \
  || fail "v28 must be guarded by the message type absent-state flag"
schedule_details_guard_line="$(grep -n -m1 -F '\if :apply_meeting_schedule_details_migration' "$stdin_file" | cut -d: -f1 || true)"
test -n "$schedule_details_guard_line" && (( schedule_details_guard_line < v29_line )) \
  || fail "v29 must be guarded by missing meeting schedule detail columns"
schedule_target_enum_guard_line="$(grep -n -m1 -F '\if :apply_schedule_report_target_enum_migration' "$stdin_file" | cut -d: -f1 || true)"
test -n "$schedule_target_enum_guard_line" && (( schedule_target_enum_guard_line < v30_line )) \
  || fail "v30 must be guarded by a missing schedule report target enum"
schedule_target_guard_line="$(grep -n -m1 -F '\if :apply_schedule_report_target_migration' "$stdin_file" | cut -d: -f1 || true)"
test -n "$schedule_target_guard_line" && (( schedule_target_guard_line < v31_line )) \
  || fail "v31 must be guarded by a missing reports.schedule_id column"
message_reply_guard_line="$(grep -n -m1 -F '\if :apply_message_reply_migration' "$stdin_file" | cut -d: -f1 || true)"
test -n "$message_reply_guard_line" && (( message_reply_guard_line < v32_line )) \
  || fail "v32 must be guarded by the reply column absent-state flag"

for workflow in "$root/.github/workflows/deploy-app-main.yml" "$root/.github/workflows/deploy-app-ai.yml"; do
  for migration in v28_chat_system_messages v29_meeting_schedule_details v30_report_schedule_target_enum v31_report_schedule_target v32_chat_message_reply; do
    scp_line="$(grep -n -F "db/migrations/${migration}.sql" "$workflow" | grep -F 'scp ' | cut -d: -f1 || true)"
    chmod_line="$(grep -n -F "${migration}.sql" "$workflow" | grep -F 'chmod 600' | cut -d: -f1 || true)"
    test -n "$scp_line" || fail "workflow must copy ${migration}: $workflow"
    test -n "$chmod_line" || fail "workflow must chmod ${migration}: $workflow"
  done
done

for migration in \
  db/migrations/v25_web_push_subscriptions.sql \
  db/migrations/v26_web_push_session_cardinality.sql; do
  grep -Fq "\\i $migration" "$stdin_file" \
    || fail "Web Push migration is missing from the guarded migration helper: $migration"
  grep -Fq "$migration" "$workflow" \
    || fail "Web Push migration is missing from the app-main deployment copy path: $migration"
  grep -Fq "$migration" "$ai_workflow" \
    || fail "Web Push migration is missing from the app-ai deployment copy path: $migration"
done
web_push_base_line="$(grep -n -m1 -F '\i db/migrations/v25_web_push_subscriptions.sql' "$stdin_file" | cut -d: -f1 || true)"
web_push_cardinality_line="$(grep -n -m1 -F '\i db/migrations/v26_web_push_session_cardinality.sql' "$stdin_file" | cut -d: -f1 || true)"
web_push_base_guard_line="$(grep -n -m1 -F '\if :apply_web_push_subscription_base_migration' "$stdin_file" | cut -d: -f1 || true)"
web_push_cardinality_guard_line="$(grep -n -m1 -F '\if :apply_web_push_session_cardinality_migration' "$stdin_file" | cut -d: -f1 || true)"
test -n "$web_push_base_guard_line" && (( web_push_base_guard_line < web_push_base_line )) \
  || fail "Web Push base migration must be guarded by table absence"
test -n "$web_push_cardinality_guard_line" && (( web_push_cardinality_guard_line < web_push_cardinality_line )) \
  || fail "Web Push session-cardinality migration must be guarded by its unique index"
grep -Fxq 'WEB_PUSH_ALLOWED_ENDPOINT_HOSTS=fcm.googleapis.com,push.services.mozilla.com,push.apple.com,notify.windows.com' "$env_example" \
  || fail "Web Push endpoint-host example must permit the Apple Push domain boundary"

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
