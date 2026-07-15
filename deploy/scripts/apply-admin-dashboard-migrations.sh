#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

required_connection_variables=(PGHOST PGPORT PGDATABASE PGUSER)
for variable_name in "${required_connection_variables[@]}"; do
  if [[ -z "${!variable_name:-}" ]]; then
    echo "$variable_name is required" >&2
    exit 2
  fi
done

if [[ ! "$PGPORT" =~ ^[0-9]+$ ]]; then
  echo "PGPORT must be numeric" >&2
  exit 2
fi

if ! command -v psql >/dev/null 2>&1; then
  echo "psql is required" >&2
  exit 127
fi

cd "$root"

psql \
  --no-psqlrc \
  --set=ON_ERROR_STOP=1 <<'SQL'
SELECT pg_advisory_lock(hashtextextended('ieum:admin-dashboard:v25-v26', 0));

CREATE OR REPLACE FUNCTION pg_temp.auth_version_contract_state()
RETURNS text
LANGUAGE plpgsql
AS $function$
DECLARE
  column_count integer;
  column_exact boolean;
  constraint_exact boolean;
BEGIN
  IF to_regclass('public.users') IS NULL THEN
    RETURN 'mismatch';
  END IF;

  SELECT count(*)
  INTO column_count
  FROM pg_attribute
  WHERE attrelid = 'public.users'::regclass
    AND attname = 'auth_version'
    AND attnum > 0
    AND NOT attisdropped;

  IF column_count = 0 THEN
    RETURN 'absent';
  END IF;

  SELECT
    format_type(attribute.atttypid, attribute.atttypmod) = 'bigint'
    AND attribute.attnotnull
    AND regexp_replace(
      COALESCE(pg_get_expr(default_value.adbin, default_value.adrelid), ''),
      '([[:space:]()]|::bigint)',
      '',
      'g'
    ) = '0'
  INTO column_exact
  FROM pg_attribute attribute
  LEFT JOIN pg_attrdef default_value
    ON default_value.adrelid = attribute.attrelid
   AND default_value.adnum = attribute.attnum
  WHERE attribute.attrelid = 'public.users'::regclass
    AND attribute.attname = 'auth_version'
    AND attribute.attnum > 0
    AND NOT attribute.attisdropped;

  SELECT count(*) = 1
    AND bool_and(
      regexp_replace(
        pg_get_expr(constraint_row.conbin, constraint_row.conrelid),
        '[[:space:]()]',
        '',
        'g'
      ) = 'auth_version>=0'
    )
  INTO constraint_exact
  FROM pg_constraint constraint_row
  WHERE constraint_row.conrelid = 'public.users'::regclass
    AND constraint_row.conname = 'ck_users_auth_version_nonnegative'
    AND constraint_row.contype = 'c'
    AND constraint_row.convalidated;

  RETURN CASE
    WHEN column_exact AND constraint_exact THEN 'exact'
    ELSE 'mismatch'
  END;
END
$function$;

CREATE OR REPLACE FUNCTION pg_temp.admin_audit_contract_state()
RETURNS text
LANGUAGE plpgsql
AS $function$
DECLARE
  columns_exact boolean;
  constraints_exact boolean;
  foreign_key_exact boolean;
  indexes_exact boolean;
BEGIN
  IF to_regclass('public.admin_audit_logs') IS NULL THEN
    RETURN 'absent';
  END IF;

  SELECT count(*) = 7
    AND bool_and(
      CASE column_name
        WHEN 'audit_id' THEN
          data_type = 'bigint'
          AND is_nullable = 'NO'
          AND column_default LIKE 'nextval(%admin_audit_logs_audit_id_seq%'
        WHEN 'actor_user_id' THEN data_type = 'bigint' AND is_nullable = 'YES'
        WHEN 'action' THEN data_type = 'text' AND is_nullable = 'NO'
        WHEN 'target_type' THEN data_type = 'text' AND is_nullable = 'NO'
        WHEN 'target_id' THEN data_type = 'bigint' AND is_nullable = 'NO'
        WHEN 'details' THEN data_type = 'jsonb' AND is_nullable = 'NO'
        WHEN 'created_at' THEN
          data_type = 'timestamp with time zone'
          AND is_nullable = 'NO'
          AND regexp_replace(COALESCE(column_default, ''), '[[:space:]]', '', 'g') = 'now()'
        ELSE false
      END
    )
  INTO columns_exact
  FROM information_schema.columns
  WHERE table_schema = 'public'
    AND table_name = 'admin_audit_logs';

  SELECT count(*) = 5
    AND count(*) FILTER (
      WHERE conname = 'admin_audit_logs_pkey'
        AND contype = 'p'
    ) = 1
    AND count(*) FILTER (
      WHERE conname = 'admin_audit_logs_actor_user_id_fkey'
        AND contype = 'f'
    ) = 1
    AND count(*) FILTER (
      WHERE conname = 'ck_admin_audit_logs_action'
        AND contype = 'c'
        AND pg_get_expr(conbin, conrelid) LIKE '%USER_SANCTION_CREATED%'
        AND pg_get_expr(conbin, conrelid) LIKE '%USER_ACTIVATED%'
        AND pg_get_expr(conbin, conrelid) LIKE '%USER_ROLE_CHANGED%'
        AND pg_get_expr(conbin, conrelid) LIKE '%REPORT_CONFIRMED%'
        AND pg_get_expr(conbin, conrelid) LIKE '%REPORT_DISMISSED%'
        AND pg_get_expr(conbin, conrelid) LIKE '%INQUIRY_ANSWERED%'
    ) = 1
    AND count(*) FILTER (
      WHERE conname = 'ck_admin_audit_logs_target_type'
        AND contype = 'c'
        AND pg_get_expr(conbin, conrelid) LIKE '%user%'
        AND pg_get_expr(conbin, conrelid) LIKE '%report%'
        AND pg_get_expr(conbin, conrelid) LIKE '%inquiry%'
    ) = 1
    AND count(*) FILTER (
      WHERE conname = 'ck_admin_audit_logs_details_object'
        AND contype = 'c'
        AND pg_get_expr(conbin, conrelid) LIKE '%jsonb_typeof(details)%'
        AND pg_get_expr(conbin, conrelid) LIKE '%object%'
    ) = 1
  INTO constraints_exact
  FROM pg_constraint
  WHERE conrelid = 'public.admin_audit_logs'::regclass;

  SELECT count(*) = 1
  INTO foreign_key_exact
  FROM pg_constraint foreign_key
  JOIN pg_attribute source_column
    ON source_column.attrelid = foreign_key.conrelid
   AND source_column.attnum = foreign_key.conkey[1]
  JOIN pg_attribute target_column
    ON target_column.attrelid = foreign_key.confrelid
   AND target_column.attnum = foreign_key.confkey[1]
  WHERE foreign_key.conrelid = 'public.admin_audit_logs'::regclass
    AND foreign_key.conname = 'admin_audit_logs_actor_user_id_fkey'
    AND foreign_key.contype = 'f'
    AND foreign_key.confrelid = 'public.users'::regclass
    AND foreign_key.confdeltype = 'n'
    AND source_column.attname = 'actor_user_id'
    AND target_column.attname = 'user_id';

  SELECT count(*) = 4
    AND count(*) FILTER (
      WHERE indexname = 'admin_audit_logs_pkey'
    ) = 1
    AND count(*) FILTER (
      WHERE indexname = 'idx_admin_audit_logs_actor_created'
        AND indexdef LIKE '%(actor_user_id, created_at DESC, audit_id DESC)'
    ) = 1
    AND count(*) FILTER (
      WHERE indexname = 'idx_admin_audit_logs_target_created'
        AND indexdef LIKE '%(target_type, target_id, created_at DESC, audit_id DESC)'
    ) = 1
    AND count(*) FILTER (
      WHERE indexname = 'idx_admin_audit_logs_created_desc'
        AND indexdef LIKE '%(created_at DESC, audit_id DESC)'
    ) = 1
  INTO indexes_exact
  FROM pg_indexes
  WHERE schemaname = 'public'
    AND tablename = 'admin_audit_logs';

  RETURN CASE
    WHEN columns_exact AND constraints_exact AND foreign_key_exact AND indexes_exact THEN 'exact'
    ELSE 'mismatch'
  END;
END
$function$;

DO $preflight$
BEGIN
  IF pg_temp.auth_version_contract_state() = 'mismatch' THEN
    RAISE EXCEPTION 'partial or incompatible users.auth_version schema';
  END IF;
  IF pg_temp.admin_audit_contract_state() = 'mismatch' THEN
    RAISE EXCEPTION 'partial or incompatible admin_audit_logs schema';
  END IF;
END
$preflight$;

SELECT pg_temp.admin_audit_contract_state() = 'absent' AS apply_admin_audit_migration \gset

\i db/migrations/v25_user_auth_version.sql
\if :apply_admin_audit_migration
\i db/migrations/v26_admin_audit_logs.sql
\endif

DO $verify$
BEGIN
  IF pg_temp.auth_version_contract_state() <> 'exact' THEN
    RAISE EXCEPTION 'users.auth_version schema verification failed';
  END IF;
  IF pg_temp.admin_audit_contract_state() <> 'exact' THEN
    RAISE EXCEPTION 'admin_audit_logs schema verification failed';
  END IF;
END
$verify$;

SELECT pg_advisory_unlock(hashtextextended('ieum:admin-dashboard:v25-v26', 0));
\echo 'Admin dashboard schema verification passed.'
SQL
