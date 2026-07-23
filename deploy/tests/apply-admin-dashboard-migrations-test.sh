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

v37_migration="$root/db/migrations/v37_notification_i18n.sql"
test -s "$v37_migration" || fail "v37 notification i18n migration is missing"
grep -Fq 'ALTER TABLE IF EXISTS public.notifications' "$v37_migration" \
  || fail "v37 must tolerate deployments where notifications is not present"
grep -Fq 'ADD COLUMN IF NOT EXISTS message_key' "$v37_migration" \
  || fail "v37 must add notification message_key idempotently"
grep -Fq 'ADD COLUMN IF NOT EXISTS message_params' "$v37_migration" \
  || fail "v37 must add notification message_params idempotently"
grep -Fq 'notifications.message_key schema verification failed' "$v37_migration" \
  || fail "v37 must reject an incompatible notification message_key column"
grep -Fq 'notifications.message_params schema verification failed' "$v37_migration" \
  || fail "v37 must reject an incompatible notification message_params column"

v38_migration="$root/db/migrations/v38_chat_notices.sql"
test -s "$v38_migration" || fail "v38 chat notices migration is missing"
grep -Fq 'CREATE TABLE IF NOT EXISTS public.chat_notices' "$v38_migration" \
  || fail "v38 must create chat_notices idempotently"
grep -Fq 'uidx_chat_notices_room_message' "$v38_migration" \
  || fail "v38 must enforce one notice per source message per room"
grep -Fq 'ADD COLUMN IF NOT EXISTS pinned_notice_id' "$v38_migration" \
  || fail "v38 must add nullable chat_rooms.pinned_notice_id idempotently"
grep -Fq "column_row.atttypid = 'bigint'::regtype" "$v38_migration" \
  || fail "v38 must verify pinned_notice_id with a bigint catalog predicate"
grep -Fq 'NOT column_row.attnotnull' "$v38_migration" \
  || fail "v38 must verify pinned_notice_id is nullable with a boolean catalog predicate"
if grep -Fq "'bigint:false'" "$v38_migration"; then
  fail "v38 must not compare PostgreSQL boolean catalog values as the text false"
fi
grep -Fq 'fk_chat_rooms_pinned_notice' "$v38_migration" \
  || fail "v38 must add the representative notice foreign key"
grep -Fq 'fk_chat_notices_room' "$v38_migration" \
  || fail "v38 must verify chat notice room foreign key"
grep -Fq 'fk_chat_notices_message' "$v38_migration" \
  || fail "v38 must verify chat notice message foreign key"
grep -Fq 'fk_chat_notices_created_by' "$v38_migration" \
  || fail "v38 must verify chat notice creator foreign key"

v39_migration="$root/db/migrations/v39_admin_audit_content_hard_delete.sql"
test -s "$v39_migration" || fail "v39 admin audit content hard-delete migration is missing"
grep -Fq 'QUESTION_HARD_DELETED' "$v39_migration" \
  || fail "v39 must allow question hard-delete audit actions"
grep -Fq 'MEETING_HARD_DELETED' "$v39_migration" \
  || fail "v39 must allow meeting hard-delete audit actions"
grep -Fq "'question'" "$v39_migration" \
  || fail "v39 must allow question audit targets"
grep -Fq "'meeting'" "$v39_migration" \
  || fail "v39 must allow meeting audit targets"
grep -Fq 'admin_audit_logs.action content hard-delete verification failed' "$v39_migration" \
  || fail "v39 must verify the expanded action constraint"
grep -Fq 'admin_audit_logs.target_type content hard-delete verification failed' "$v39_migration" \
  || fail "v39 must verify the expanded target type constraint"

v40_migration="$root/db/migrations/v40_admin_content_file_cleanup_tasks.sql"
test -s "$v40_migration" || fail "v40 admin content file cleanup task migration is missing"
grep -Fq 'CREATE TABLE IF NOT EXISTS public.file_cleanup_tasks' "$v40_migration" \
  || fail "v40 must create file_cleanup_tasks idempotently"
grep -Fq 'ck_file_cleanup_tasks_status' "$v40_migration" \
  || fail "v40 must constrain file cleanup task status values"
grep -Fq 'uq_file_cleanup_tasks_s3_key' "$v40_migration" \
  || fail "v40 must enforce one durable cleanup task per S3 key"
grep -Fq 'idx_file_cleanup_tasks_claim' "$v40_migration" \
  || fail "v40 must index due cleanup task claims"
grep -Fq 'idx_file_cleanup_tasks_expired_lease' "$v40_migration" \
  || fail "v40 must index expired lease recovery"

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
grep -Fq "KNOWLEDGE_RELATION_APPROVED" "$stdin_file" \
  || fail "audit action verification must accept knowledge relation approval actions"
grep -Fq "KNOWLEDGE_RELATION_REJECTED" "$stdin_file" \
  || fail "audit action verification must accept knowledge relation rejection actions"
grep -Fq "QUESTION_HARD_DELETED" "$stdin_file" \
  || fail "audit action verification must accept question hard-delete actions"
grep -Fq "MEETING_HARD_DELETED" "$stdin_file" \
  || fail "audit action verification must accept meeting hard-delete actions"
grep -Fq "knowledge_relation_candidate" "$stdin_file" \
  || fail "audit target verification must accept knowledge relation candidates"
grep -Fq "''question''::text" "$stdin_file" \
  || fail "audit target verification must accept question targets"
grep -Fq "''meeting''::text" "$stdin_file" \
  || fail "audit target verification must accept meeting targets"
grep -Fq "message_type_contract_state" "$stdin_file" \
  || fail "message type preflight/final verification is missing"
grep -Fq "message_reply_contract_state" "$stdin_file" \
  || fail "message reply preflight/final verification is missing"
grep -Fq "chat_notice_contract_state" "$stdin_file" \
  || fail "chat notice preflight/final verification is missing"
grep -Fq "partial or incompatible users.auth_version schema" "$stdin_file" \
  || fail "partial auth schema must fail explicitly"
grep -Fq "partial or incompatible admin_audit_logs schema" "$stdin_file" \
  || fail "partial audit schema must fail explicitly"
grep -Fq "partial or incompatible messages.message_type schema" "$stdin_file" \
  || fail "partial message type schema must fail explicitly"
grep -Fq "partial or incompatible messages.reply_to_message_id schema" "$stdin_file" \
  || fail "partial message reply schema must fail explicitly"
grep -Fq "partial or incompatible chat_notices schema" "$stdin_file" \
  || fail "partial chat notice schema must fail explicitly"
grep -Fq "apply_admin_audit_migration" "$stdin_file" \
  || fail "an exact existing audit schema must skip the non-idempotent v26 file"
grep -Fq "apply_admin_audit_content_hard_delete_migration" "$stdin_file" \
  || fail "a base audit schema must apply the v39 content hard-delete constraint expansion"
grep -Fq "apply_auth_version_migration" "$stdin_file" \
  || fail "an exact existing auth schema must skip the locking v25 file"
grep -Fq "apply_message_type_migration" "$stdin_file" \
  || fail "an exact existing message type schema must skip the non-idempotent v28 file"
grep -Fq "apply_message_reply_migration" "$stdin_file" \
  || fail "an exact existing message reply schema must skip the non-idempotent v32 file"
grep -Fq "apply_chat_notice_migration" "$stdin_file" \
  || fail "an exact existing chat notice schema must skip the v38 file"
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
required_chat_notice_catalog_tokens=(
  "CREATE OR REPLACE FUNCTION pg_temp.chat_notice_contract_state()"
  "table_class.relname = 'chat_notices'"
  "'public.chat_rooms'::regclass"
  "'public.messages'::regclass"
  "'public.users'::regclass"
  "format_type(attribute.atttypid, attribute.atttypmod) = expected.type_name"
  "chat_notices_notice_id_seq"
  "fk_chat_notices_room"
  "fk_chat_notices_message"
  "fk_chat_notices_created_by"
  "fk_chat_rooms_pinned_notice"
  "constraint_row.confdeltype = 'c'"
  "constraint_row.confdeltype = 'n'"
  "uidx_chat_notices_room_message"
  "idx_chat_notices_room_created"
  "index_row.indkey::text = '2 3'"
  "index_row.indoption::text = '0 0'"
  "index_row.indkey::text = '2 5 1'"
  "index_row.indoption::text = '0 3 3'"
  "RAISE EXCEPTION 'chat_notices schema verification failed'"
)
for token in "${required_chat_notice_catalog_tokens[@]}"; do
  grep -Fq "$token" "$stdin_file" \
    || fail "chat notice exact catalog verification is missing: $token"
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
v33_line="$(grep -n -m1 -F '\i db/migrations/v33_question_ai_ungrounded_answer.sql' "$stdin_file" | cut -d: -f1 || true)"
v34_line="$(grep -n -m1 -F '\i db/migrations/v34_question_ai_ungrounded_answer_validate.sql' "$stdin_file" | cut -d: -f1 || true)"
v35_line="$(grep -n -m1 -F '\i db/migrations/v35_knowledge_relation_candidates.sql' "$stdin_file" | cut -d: -f1 || true)"
v36_line="$(grep -n -m1 -F '\i db/migrations/v36_meeting_schedule_date_time.sql' "$stdin_file" | cut -d: -f1 || true)"
v37_line="$(grep -n -m1 -F '\i db/migrations/v37_notification_i18n.sql' "$stdin_file" | cut -d: -f1 || true)"
v38_line="$(grep -n -m1 -F '\i db/migrations/v38_chat_notices.sql' "$stdin_file" | cut -d: -f1 || true)"
v39_line="$(grep -n -m1 -F '\i db/migrations/v39_admin_audit_content_hard_delete.sql' "$stdin_file" | cut -d: -f1 || true)"
v40_line="$(grep -n -m1 -F '\i db/migrations/v40_admin_content_file_cleanup_tasks.sql' "$stdin_file" | cut -d: -f1 || true)"
test -n "$v24_line" && test -n "$v25_line" && test -n "$v26_line" && test -n "$v27_line" && test -n "$v28_line" \
  && test -n "$v29_line" && test -n "$v30_line" && test -n "$v31_line" && test -n "$v32_line" \
  && test -n "$v33_line" && test -n "$v34_line" && test -n "$v35_line" && test -n "$v36_line" && test -n "$v37_line" \
  && test -n "$v38_line" && test -n "$v39_line" && test -n "$v40_line" \
	|| fail "all v24-v40 migrations must be applied"
(( v24_line < v27_line )) || fail "v24 must run before v27"
(( v25_line < v26_line )) || fail "v25 must run before v26"
(( v26_line < v28_line )) || fail "v26 must run before v28"
(( v28_line < v29_line )) || fail "v28 must run before v29"
(( v29_line < v30_line )) || fail "v29 must run before v30"
(( v30_line < v31_line )) || fail "v30 must run before v31"
(( v31_line < v32_line )) || fail "v31 must run before v32"
(( v32_line < v33_line )) || fail "v32 must run before v33"
(( v33_line < v34_line )) || fail "v33 must run before v34"
(( v34_line < v35_line )) || fail "v34 must run before v35"
(( v35_line < v36_line )) || fail "v35 must run before v36"
(( v36_line < v37_line )) || fail "v36 must run before v37"
(( v37_line < v38_line )) || fail "v37 must run before v38"
(( v38_line < v39_line )) || fail "v38 must run before v39"
(( v39_line < v40_line )) || fail "v39 must run before v40"
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
question_ai_ungrounded_guard_line="$(grep -n -m1 -F '\if :apply_question_ai_ungrounded_migration' "$stdin_file" | cut -d: -f1 || true)"
test -n "$question_ai_ungrounded_guard_line" && (( question_ai_ungrounded_guard_line < v33_line )) \
  || fail "v33 must be guarded after its named grounding-status constraint exists"
meeting_schedule_date_time_guard_line="$(grep -n -m1 -F '\if :apply_meeting_schedule_date_time_migration' "$stdin_file" | cut -d: -f1 || true)"
test -n "$meeting_schedule_date_time_guard_line" && (( meeting_schedule_date_time_guard_line < v36_line )) \
  || fail "v36 must be guarded by a missing starts_on column"
chat_notice_guard_line="$(grep -n -m1 -F '\if :apply_chat_notice_migration' "$stdin_file" | cut -d: -f1 || true)"
test -n "$chat_notice_guard_line" && (( chat_notice_guard_line < v38_line )) \
  || fail "v38 must be guarded by missing chat notice schema"
admin_audit_content_hard_delete_guard_line="$(grep -n -m1 -F '\if :apply_admin_audit_content_hard_delete_migration' "$stdin_file" | cut -d: -f1 || true)"
test -n "$admin_audit_content_hard_delete_guard_line" && (( admin_audit_content_hard_delete_guard_line < v39_line )) \
  || fail "v39 must be guarded by the base audit schema state"
admin_audit_content_hard_delete_recompute_after_v35="$(
  grep -n -F "SELECT pg_temp.admin_audit_contract_state() = 'base' AS apply_admin_audit_content_hard_delete_migration" "$stdin_file" \
    | cut -d: -f1 \
    | awk -v after="$v35_line" -v before="$v39_line" '$1 > after && $1 < before { print; exit }'
)"
test -n "$admin_audit_content_hard_delete_recompute_after_v35" \
  || fail "v39 guard must be recomputed after v35 because v35 can rewrite audit constraints"
grep -Fq "conname = 'fk_chat_rooms_pinned_notice'" "$stdin_file" \
  || fail "v38 guard must catch a missing pinned notice foreign key"
grep -Fq "index_class.relname = 'uidx_chat_notices_room_message'" "$stdin_file" \
  || fail "v38 guard must catch a missing chat notice unique index"
if grep -Fq "to_regclass('public.chat_notices') IS NULL" "$stdin_file"; then
  fail "v38 guard must use the exact chat notice contract state instead of a permissive table-presence check"
fi

for workflow in "$root/.github/workflows/deploy-app-main.yml" "$root/.github/workflows/deploy-app-ai.yml"; do
  for migration in v28_chat_system_messages v29_meeting_schedule_details v30_report_schedule_target_enum v31_report_schedule_target v32_chat_message_reply v33_question_ai_ungrounded_answer v34_question_ai_ungrounded_answer_validate v35_knowledge_relation_candidates v36_meeting_schedule_date_time v37_notification_i18n v38_chat_notices v39_admin_audit_content_hard_delete v40_admin_content_file_cleanup_tasks; do
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
