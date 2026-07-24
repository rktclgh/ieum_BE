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
  CREATE TABLE public.chat_rooms (room_id bigint PRIMARY KEY);
  CREATE TABLE trap.chat_rooms (room_id bigint PRIMARY KEY);
  CREATE TABLE public.messages (
    message_id bigint PRIMARY KEY,
    room_id bigint NOT NULL,
    sender_id bigint NOT NULL,
    content text,
    image_file_id uuid,
    CHECK (content IS NOT NULL OR image_file_id IS NOT NULL)
  );
  CREATE TABLE trap.messages (
    message_id bigint PRIMARY KEY,
    room_id bigint NOT NULL,
    sender_id bigint NOT NULL,
    content text,
    image_file_id uuid,
    CHECK (content IS NOT NULL OR image_file_id IS NOT NULL)
  );
  CREATE TABLE public.notifications (notification_id bigint PRIMARY KEY);
  CREATE TABLE trap.notifications (notification_id bigint PRIMARY KEY);
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
    ),
    to_regclass('public.web_push_subscriptions') IS NOT NULL,
    to_regclass('trap.web_push_subscriptions') IS NULL,
    EXISTS (
      SELECT 1
      FROM pg_index index_row
      JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
      WHERE index_row.indrelid = 'public.web_push_subscriptions'::regclass
        AND index_class.relname = 'uidx_web_push_subscriptions_session'
        AND index_row.indisunique
    ),
    EXISTS (
      SELECT 1 FROM pg_attribute
      WHERE attrelid = 'public.messages'::regclass
        AND attname = 'message_type'
        AND attnum > 0
        AND NOT attisdropped
    ),
    NOT EXISTS (
      SELECT 1 FROM pg_attribute
      WHERE attrelid = 'trap.messages'::regclass
        AND attname = 'message_type'
        AND attnum > 0
        AND NOT attisdropped
    ),
    EXISTS (
      SELECT 1 FROM pg_attribute
      WHERE attrelid = 'public.notifications'::regclass
        AND attname = 'message_key'
        AND atttypid = 'character varying'::regtype
        AND atttypmod = 104
        AND attnum > 0
        AND NOT attisdropped
    ),
    EXISTS (
      SELECT 1 FROM pg_attribute
      WHERE attrelid = 'public.notifications'::regclass
        AND attname = 'message_params'
        AND atttypid = 'jsonb'::regtype
        AND attnum > 0
        AND NOT attisdropped
    ),
    to_regclass('public.chat_notices') IS NOT NULL,
    to_regclass('trap.chat_notices') IS NULL,
    EXISTS (
      SELECT 1 FROM pg_attribute
      WHERE attrelid = 'public.chat_rooms'::regclass
        AND attname = 'pinned_notice_id'
        AND atttypid = 'bigint'::regtype
        AND NOT attnotnull
        AND attnum > 0
        AND NOT attisdropped
    ),
    NOT EXISTS (
      SELECT 1 FROM pg_attribute
      WHERE attrelid = 'trap.chat_rooms'::regclass
        AND attname = 'pinned_notice_id'
        AND attnum > 0
        AND NOT attisdropped
    ),
    EXISTS (
      SELECT 1
      FROM pg_index index_row
      JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
      WHERE index_row.indrelid = 'public.chat_notices'::regclass
        AND index_class.relname = 'uidx_chat_notices_room_message'
        AND index_row.indisunique
        AND index_row.indkey::text = '2 3'
    ),
    EXISTS (
      SELECT 1
      FROM pg_index index_row
      JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
      WHERE index_row.indrelid = 'public.chat_notices'::regclass
        AND index_class.relname = 'idx_chat_notices_room_created'
        AND NOT index_row.indisunique
        AND index_row.indkey::text = '2 5 1'
        AND index_row.indoption::text = '0 3 3'
    ),
    NOT EXISTS (
      SELECT 1 FROM pg_attribute
      WHERE attrelid = 'trap.notifications'::regclass
        AND attname IN ('message_key', 'message_params')
        AND attnum > 0
        AND NOT attisdropped
    )
  );
")"
[[ "$schema_state" == "t:t:t:t:t:t:t:t:t:t:t:t:t:t:t:t:t:t" ]] \
  || fail "DDL escaped public under a hostile search_path: $schema_state"

constraint_oid_before="$(sql "
  SELECT oid
  FROM pg_constraint
  WHERE conrelid = 'public.users'::regclass
    AND conname = 'ck_users_auth_version_nonnegative';
")"
notification_column_contract_before="$(sql "
  SELECT string_agg(concat(attname, ':', atttypid::regtype::text, ':', atttypmod), ',' ORDER BY attname)
  FROM pg_attribute
  WHERE attrelid = 'public.notifications'::regclass
    AND attname IN ('message_key', 'message_params')
    AND attnum > 0
    AND NOT attisdropped;
")"
run_helper >/dev/null
constraint_oid_after="$(sql "
  SELECT oid
  FROM pg_constraint
  WHERE conrelid = 'public.users'::regclass
    AND conname = 'ck_users_auth_version_nonnegative';
")"
notification_column_contract_after="$(sql "
  SELECT string_agg(concat(attname, ':', atttypid::regtype::text, ':', atttypmod), ',' ORDER BY attname)
  FROM pg_attribute
  WHERE attrelid = 'public.notifications'::regclass
    AND attname IN ('message_key', 'message_params')
    AND attnum > 0
    AND NOT attisdropped;
")"
[[ -n "$constraint_oid_before" && "$constraint_oid_before" == "$constraint_oid_after" ]] \
  || fail "an exact v25 rerun replaced the users constraint"
[[ "$notification_column_contract_before" == "message_key:character varying:104,message_params:jsonb:-1" \
  && "$notification_column_contract_before" == "$notification_column_contract_after" ]] \
  || fail "notification i18n migration was not idempotent"

# A current content-management audit row must not cause a later deployment
# migration rerun to downgrade the action constraint and fail.
sql "
  INSERT INTO public.admin_audit_logs (action, target_type, target_id, details)
  VALUES ('QUESTION_UPDATED', 'question', 1, '{}'::jsonb);
" >/dev/null
run_helper >/dev/null

sql "
  ALTER TABLE public.notifications DROP COLUMN message_key;
  ALTER TABLE public.notifications ADD COLUMN message_key TEXT;
" >/dev/null
if run_helper >/dev/null 2>&1; then
  fail "helper accepted an incompatible notification message_key column"
fi
sql "
  ALTER TABLE public.notifications DROP COLUMN message_key;
  ALTER TABLE public.notifications ADD COLUMN message_key VARCHAR(100);
" >/dev/null
sql "
  ALTER TABLE public.notifications DROP COLUMN message_params;
  ALTER TABLE public.notifications ADD COLUMN message_params TEXT;
" >/dev/null
if run_helper >/dev/null 2>&1; then
  fail "helper accepted an incompatible notification message_params column"
fi
sql "
  ALTER TABLE public.notifications DROP COLUMN message_params;
  ALTER TABLE public.notifications ADD COLUMN message_params JSONB;
" >/dev/null

chat_notice_constraint_oids_before="$(sql "
  SELECT string_agg(oid::text, ':' ORDER BY conname)
  FROM pg_constraint
  WHERE conname IN (
    'chat_notices_pkey',
    'fk_chat_notices_room',
    'fk_chat_notices_message',
    'fk_chat_notices_created_by',
    'fk_chat_rooms_pinned_notice'
  );
")"
run_helper >/dev/null
chat_notice_constraint_oids_after="$(sql "
  SELECT string_agg(oid::text, ':' ORDER BY conname)
  FROM pg_constraint
  WHERE conname IN (
    'chat_notices_pkey',
    'fk_chat_notices_room',
    'fk_chat_notices_message',
    'fk_chat_notices_created_by',
    'fk_chat_rooms_pinned_notice'
  );
")"
[[ "$chat_notice_constraint_oids_before" =~ ^[0-9]+:[0-9]+:[0-9]+:[0-9]+:[0-9]+$ \
  && "$chat_notice_constraint_oids_before" == "$chat_notice_constraint_oids_after" ]] \
  || fail "an exact v38 rerun replaced chat notice constraints"

sql "ALTER TABLE public.chat_notices ALTER COLUMN created_by SET NOT NULL;" >/dev/null
if run_helper >/dev/null 2>&1; then
  fail "helper accepted an incompatible chat_notices.created_by column"
fi
sql "ALTER TABLE public.chat_notices ALTER COLUMN created_by DROP NOT NULL;" >/dev/null

sql "
  ALTER TABLE public.chat_notices DROP CONSTRAINT fk_chat_notices_room;
  ALTER TABLE public.chat_notices
    ADD CONSTRAINT fk_chat_notices_room
    FOREIGN KEY (room_id) REFERENCES public.chat_rooms(room_id)
    ON DELETE SET NULL;
" >/dev/null
if run_helper >/dev/null 2>&1; then
  fail "helper accepted an incompatible chat_notices.room_id foreign key"
fi
sql "
  ALTER TABLE public.chat_notices DROP CONSTRAINT fk_chat_notices_room;
  ALTER TABLE public.chat_notices
    ADD CONSTRAINT fk_chat_notices_room
    FOREIGN KEY (room_id) REFERENCES public.chat_rooms(room_id)
    ON DELETE CASCADE;
" >/dev/null

sql "
  ALTER TABLE public.chat_rooms DROP CONSTRAINT fk_chat_rooms_pinned_notice;
  ALTER TABLE public.chat_rooms
    ADD CONSTRAINT fk_chat_rooms_pinned_notice
    FOREIGN KEY (pinned_notice_id) REFERENCES public.chat_notices(notice_id)
    ON DELETE CASCADE;
" >/dev/null
if run_helper >/dev/null 2>&1; then
  fail "helper accepted an incompatible chat_rooms.pinned_notice_id foreign key"
fi
sql "
  ALTER TABLE public.chat_rooms DROP CONSTRAINT fk_chat_rooms_pinned_notice;
  ALTER TABLE public.chat_rooms
    ADD CONSTRAINT fk_chat_rooms_pinned_notice
    FOREIGN KEY (pinned_notice_id) REFERENCES public.chat_notices(notice_id)
    ON DELETE SET NULL;
" >/dev/null

sql "
  DROP INDEX public.idx_chat_notices_room_created;
  CREATE INDEX idx_chat_notices_room_created
    ON public.chat_notices(room_id, notice_id DESC, created_at DESC);
" >/dev/null
if run_helper >/dev/null 2>&1; then
  fail "helper accepted an incompatible chat notice room-created index"
fi
sql "
  DROP INDEX public.idx_chat_notices_room_created;
  CREATE INDEX idx_chat_notices_room_created
    ON public.chat_notices(room_id, created_at DESC, notice_id DESC);
" >/dev/null

message_type_constraint_oids_before="$(sql "
  SELECT string_agg(oid::text, ':' ORDER BY conname)
  FROM pg_constraint
  WHERE conrelid = 'public.messages'::regclass
    AND conname IN ('ck_messages_message_type', 'ck_messages_system_text_only');
")"
run_helper >/dev/null
message_type_constraint_oids_after="$(sql "
  SELECT string_agg(oid::text, ':' ORDER BY conname)
  FROM pg_constraint
  WHERE conrelid = 'public.messages'::regclass
    AND conname IN ('ck_messages_message_type', 'ck_messages_system_text_only');
")"
[[ "$message_type_constraint_oids_before" =~ ^[0-9]+:[0-9]+$ \
  && "$message_type_constraint_oids_before" == "$message_type_constraint_oids_after" ]] \
  || fail "an exact v28 rerun replaced the messages constraints"

sql "
  ALTER TABLE public.messages DROP CONSTRAINT ck_messages_message_type;
  ALTER TABLE public.messages
    ADD CONSTRAINT ck_messages_message_type
    CHECK (message_type IN ('user', 'system', 'unexpected'));
" >/dev/null
if run_helper >/dev/null 2>&1; then
  fail "helper accepted an incompatible message type check"
fi
sql "
  ALTER TABLE public.messages DROP CONSTRAINT ck_messages_message_type;
  ALTER TABLE public.messages
    ADD CONSTRAINT ck_messages_message_type
    CHECK (message_type IN ('user', 'system'));
" >/dev/null

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
sql "ALTER SEQUENCE public.admin_audit_logs_audit_id_seq
  MAXVALUE 9223372036854775807 NO CYCLE CACHE 1;" >/dev/null

sql "ALTER SEQUENCE public.web_push_subscriptions_subscription_id_seq
  MAXVALUE 100 CYCLE CACHE 2;" >/dev/null
if run_helper >/dev/null 2>&1; then
  fail "helper accepted incompatible web push subscription sequence properties"
fi
web_push_sequence_state="$(sql "
  SELECT concat_ws(':', seqmax, seqcache, seqcycle)
  FROM pg_sequence
  WHERE seqrelid = 'public.web_push_subscriptions_subscription_id_seq'::regclass;
")"
[[ "$web_push_sequence_state" == "100:2:t" ]] \
  || fail "failed Web Push preflight unexpectedly rewrote the subscription sequence"
sql "ALTER SEQUENCE public.web_push_subscriptions_subscription_id_seq
  MAXVALUE 9223372036854775807 NO CYCLE CACHE 1;" >/dev/null

sql "
  CREATE COLLATION public.web_push_test_collation (provider = libc, locale = 'C');
  DROP INDEX public.uidx_web_push_subscriptions_session;
  CREATE UNIQUE INDEX uidx_web_push_subscriptions_session
    ON public.web_push_subscriptions
    (session_id COLLATE public.web_push_test_collation varchar_pattern_ops);
" >/dev/null
if run_helper >/dev/null 2>&1; then
  fail "helper accepted a web push session index with non-default collation or opclass"
fi
sql "
  DROP INDEX public.uidx_web_push_subscriptions_session;
  CREATE UNIQUE INDEX uidx_web_push_subscriptions_session
    ON public.web_push_subscriptions(session_id);
  DROP COLLATION public.web_push_test_collation;
" >/dev/null

sql "
  CREATE COLLATION public.web_push_test_collation (provider = libc, locale = 'C');
  ALTER TABLE public.web_push_subscriptions
    ALTER COLUMN session_id TYPE VARCHAR(64) COLLATE public.web_push_test_collation;
" >/dev/null
if run_helper >/dev/null 2>&1; then
  fail "helper accepted a web push session column with non-default collation"
fi
sql "
  ALTER TABLE public.web_push_subscriptions
    ALTER COLUMN session_id TYPE VARCHAR(64) COLLATE \"default\";
  DROP COLLATION public.web_push_test_collation;
" >/dev/null

sql "
  DROP TRIGGER trg_web_push_subscriptions_updated ON public.web_push_subscriptions;
  CREATE TRIGGER trg_web_push_subscriptions_updated
    BEFORE UPDATE OF p256dh ON public.web_push_subscriptions
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
" >/dev/null
if run_helper >/dev/null 2>&1; then
  fail "helper accepted a column-filtered web push updated_at trigger"
fi
sql "
  DROP TRIGGER trg_web_push_subscriptions_updated ON public.web_push_subscriptions;
  CREATE TRIGGER trg_web_push_subscriptions_updated
    BEFORE UPDATE ON public.web_push_subscriptions
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
" >/dev/null

sql "
  ALTER TABLE public.web_push_subscriptions
    DROP CONSTRAINT web_push_subscriptions_endpoint_hash_key;
  DROP INDEX public.uidx_web_push_subscriptions_session;
  CREATE INDEX idx_web_push_subscriptions_session
    ON public.web_push_subscriptions(session_id);
" >/dev/null
if run_helper >/dev/null 2>&1; then
  fail "helper accepted a web push table without endpoint_hash uniqueness"
fi
endpoint_hash_constraint_state="$(sql "
  SELECT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conrelid = 'public.web_push_subscriptions'::regclass
      AND conname = 'web_push_subscriptions_endpoint_hash_key'
  );
")"
[[ "$endpoint_hash_constraint_state" == "f" ]] \
  || fail "failed Web Push preflight unexpectedly rewrote the endpoint hash constraint"
legacy_session_index_state="$(sql "
  SELECT concat_ws(':',
    to_regclass('public.idx_web_push_subscriptions_session') IS NOT NULL,
    to_regclass('public.uidx_web_push_subscriptions_session') IS NULL
  );
")"
[[ "$legacy_session_index_state" == "t:t" ]] \
  || fail "failed Web Push preflight ran the session-cardinality migration"

echo "Admin dashboard PostgreSQL migration tests passed."
