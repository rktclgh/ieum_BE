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
SET search_path = pg_catalog, public;
SELECT pg_advisory_lock(hashtextextended('ieum:admin-dashboard:v25-v26', 0));

CREATE OR REPLACE FUNCTION pg_temp.auth_version_contract_state()
RETURNS text
LANGUAGE plpgsql
AS $function$
DECLARE
  column_count integer;
  reserved_constraint_count integer;
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

  SELECT count(*)
  INTO reserved_constraint_count
  FROM pg_constraint
  WHERE conrelid = 'public.users'::regclass
    AND conname = 'ck_users_auth_version_nonnegative';

  IF column_count = 0 THEN
    RETURN CASE
      WHEN reserved_constraint_count = 0 THEN 'absent'
      ELSE 'mismatch'
    END;
  END IF;

  SELECT count(*) = 1
    AND bool_and(
      attribute.atttypid = 'bigint'::regtype
      AND attribute.atttypmod = -1
      AND attribute.attnotnull
      AND attribute.attgenerated = ''
      AND attribute.attidentity = ''
      AND attribute.atthasdef
      AND default_value.oid IS NOT NULL
      AND regexp_replace(
        pg_get_expr(default_value.adbin, default_value.adrelid),
        '([[:space:]()]|::bigint)',
        '',
        'g'
      ) = '0'
    )
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
      constraint_row.contype = 'c'
      AND constraint_row.convalidated
      AND NOT constraint_row.connoinherit
      AND NOT constraint_row.condeferrable
      AND NOT constraint_row.condeferred
      AND constraint_row.conkey = ARRAY[
        (
          SELECT attnum
          FROM pg_attribute
          WHERE attrelid = 'public.users'::regclass
            AND attname = 'auth_version'
            AND attnum > 0
            AND NOT attisdropped
        )
      ]::smallint[]
      AND
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
    AND constraint_row.conname = 'ck_users_auth_version_nonnegative';

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
  table_oid oid;
  columns_exact boolean;
  sequence_exact boolean;
  constraints_exact boolean;
  indexes_exact boolean;
BEGIN
  SELECT table_class.oid
  INTO table_oid
  FROM pg_class table_class
  JOIN pg_namespace table_namespace ON table_namespace.oid = table_class.relnamespace
  WHERE table_namespace.nspname = 'public'
    AND table_class.relname = 'admin_audit_logs';

  IF table_oid IS NULL THEN
    RETURN 'absent';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_class table_class
    WHERE table_class.oid = table_oid
      AND table_class.relkind = 'r'
      AND table_class.relpersistence = 'p'
      AND NOT table_class.relispartition
  ) THEN
    RETURN 'mismatch';
  END IF;

  WITH expected_columns(attnum, attname, atttypid, attnotnull, default_expression) AS (
    VALUES
      (1::smallint, 'audit_id'::name, 'bigint'::regtype::oid, true,
        'nextval(''admin_audit_logs_audit_id_seq''::regclass)'::text),
      (2::smallint, 'actor_user_id'::name, 'bigint'::regtype::oid, false, NULL::text),
      (3::smallint, 'action'::name, 'text'::regtype::oid, true, NULL::text),
      (4::smallint, 'target_type'::name, 'text'::regtype::oid, true, NULL::text),
      (5::smallint, 'target_id'::name, 'bigint'::regtype::oid, true, NULL::text),
      (6::smallint, 'details'::name, 'jsonb'::regtype::oid, true, NULL::text),
      (7::smallint, 'created_at'::name, 'timestamp with time zone'::regtype::oid, true, 'now()'::text)
  )
  SELECT
    (
      SELECT count(*) = 7
      FROM pg_attribute
      WHERE attrelid = table_oid
        AND attnum > 0
        AND NOT attisdropped
    )
    AND count(*) = 7
    AND bool_and(
      attribute.attnum = expected.attnum
      AND attribute.attname = expected.attname
      AND attribute.atttypid = expected.atttypid
      AND attribute.atttypmod = -1
      AND attribute.attnotnull = expected.attnotnull
      AND attribute.attgenerated = ''
      AND attribute.attidentity = ''
      AND attribute.atthasdef = (expected.default_expression IS NOT NULL)
      AND (
        (expected.default_expression IS NULL AND default_value.oid IS NULL)
        OR (
          expected.default_expression IS NOT NULL
          AND default_value.oid IS NOT NULL
          AND pg_get_expr(default_value.adbin, default_value.adrelid) = expected.default_expression
        )
      )
    )
  INTO columns_exact
  FROM expected_columns expected
  JOIN pg_attribute attribute
    ON attribute.attrelid = table_oid
   AND attribute.attnum = expected.attnum
   AND attribute.attname = expected.attname
   AND NOT attribute.attisdropped
  LEFT JOIN pg_attrdef default_value
    ON default_value.adrelid = attribute.attrelid
   AND default_value.adnum = attribute.attnum;

  SELECT
    to_regclass(pg_get_serial_sequence('public.admin_audit_logs', 'audit_id'))
      = to_regclass('public.admin_audit_logs_audit_id_seq')
    AND count(*) = 1
    AND bool_and(
      sequence_class.relkind = 'S'
      AND sequence_class.relpersistence = 'p'
      AND sequence_row.seqtypid = 'bigint'::regtype
      AND sequence_row.seqstart = 1
      AND sequence_row.seqincrement = 1
      AND sequence_row.seqmax = 9223372036854775807
      AND sequence_row.seqmin = 1
      AND sequence_row.seqcache = 1
      AND NOT sequence_row.seqcycle
      AND dependency.classid = 'pg_class'::regclass
      AND dependency.objsubid = 0
      AND dependency.refclassid = 'pg_class'::regclass
      AND dependency.refobjid = table_oid
      AND dependency.refobjsubid = 1
      AND dependency.deptype = 'a'
    )
  INTO sequence_exact
  FROM pg_class sequence_class
  JOIN pg_namespace sequence_namespace ON sequence_namespace.oid = sequence_class.relnamespace
  JOIN pg_sequence sequence_row ON sequence_row.seqrelid = sequence_class.oid
  JOIN pg_depend dependency
    ON dependency.objid = sequence_class.oid
   AND dependency.classid = 'pg_class'::regclass
   AND dependency.objsubid = 0
   AND dependency.refclassid = 'pg_class'::regclass
   AND dependency.deptype = 'a'
  WHERE sequence_namespace.nspname = 'public'
    AND sequence_class.relname = 'admin_audit_logs_audit_id_seq';

  SELECT count(*) = 5
    AND count(*) FILTER (
      WHERE conname = 'admin_audit_logs_pkey'
        AND contype = 'p'
        AND constraint_row.convalidated
        AND NOT constraint_row.condeferrable
        AND NOT constraint_row.condeferred
        AND constraint_row.conkey = ARRAY[1]::smallint[]
    ) = 1
    AND count(*) FILTER (
      WHERE conname = 'admin_audit_logs_actor_user_id_fkey'
        AND contype = 'f'
        AND constraint_row.convalidated
        AND constraint_row.conkey = ARRAY[2]::smallint[]
        AND constraint_row.confrelid = 'public.users'::regclass
        AND constraint_row.confkey = ARRAY[
          (
            SELECT attnum
            FROM pg_attribute
            WHERE attrelid = 'public.users'::regclass
              AND attname = 'user_id'
              AND attnum > 0
              AND NOT attisdropped
          )
        ]::smallint[]
        AND constraint_row.confmatchtype = 's'
        AND constraint_row.confupdtype = 'a'
        AND constraint_row.confdeltype = 'n'
        AND NOT constraint_row.condeferrable
        AND NOT constraint_row.condeferred
    ) = 1
    AND count(*) FILTER (
      WHERE conname = 'ck_admin_audit_logs_action'
        AND contype = 'c'
        AND constraint_row.convalidated
        AND NOT constraint_row.connoinherit
        AND regexp_replace(
          pg_get_expr(constraint_row.conbin, constraint_row.conrelid),
          '[[:space:]()]',
          '',
          'g'
        ) = 'action=ANYARRAY[''USER_SANCTION_CREATED''::text,''USER_ACTIVATED''::text,''USER_ROLE_CHANGED''::text,''REPORT_CONFIRMED''::text,''REPORT_DISMISSED''::text,''INQUIRY_ANSWERED''::text]'
    ) = 1
    AND count(*) FILTER (
      WHERE conname = 'ck_admin_audit_logs_target_type'
        AND contype = 'c'
        AND constraint_row.convalidated
        AND NOT constraint_row.connoinherit
        AND regexp_replace(
          pg_get_expr(constraint_row.conbin, constraint_row.conrelid),
          '[[:space:]()]',
          '',
          'g'
        ) = 'target_type=ANYARRAY[''user''::text,''report''::text,''inquiry''::text]'
    ) = 1
    AND count(*) FILTER (
      WHERE conname = 'ck_admin_audit_logs_details_object'
        AND contype = 'c'
        AND constraint_row.convalidated
        AND NOT constraint_row.connoinherit
        AND regexp_replace(
          pg_get_expr(constraint_row.conbin, constraint_row.conrelid),
          '[[:space:]()]',
          '',
          'g'
        ) = 'jsonb_typeofdetails=''object''::text'
    ) = 1
  INTO constraints_exact
  FROM pg_constraint constraint_row
  WHERE constraint_row.conrelid = table_oid
    AND constraint_row.contype <> 'n';

  SELECT count(*) = 4
    AND count(*) FILTER (
      WHERE index_class.relname = 'admin_audit_logs_pkey'
        AND index_method.amname = 'btree'
        AND index_class.relkind = 'i'
        AND index_class.relpersistence = 'p'
        AND index_row.indisvalid
        AND index_row.indisready
        AND index_row.indislive
        AND index_row.indisunique
        AND index_row.indisprimary
        AND NOT index_row.indisexclusion
        AND index_row.indpred IS NULL
        AND index_row.indexprs IS NULL
        AND index_row.indnatts = 1
        AND index_row.indnkeyatts = 1
        AND index_row.indkey::text = '1'
        AND index_row.indoption::text = '0'
    ) = 1
    AND count(*) FILTER (
      WHERE index_class.relname = 'idx_admin_audit_logs_actor_created'
        AND index_method.amname = 'btree'
        AND index_class.relkind = 'i'
        AND index_class.relpersistence = 'p'
        AND index_row.indisvalid
        AND index_row.indisready
        AND index_row.indislive
        AND NOT index_row.indisunique
        AND NOT index_row.indisprimary
        AND NOT index_row.indisexclusion
        AND index_row.indpred IS NULL
        AND index_row.indexprs IS NULL
        AND index_row.indnatts = 3
        AND index_row.indnkeyatts = 3
        AND index_row.indkey::text = '2 7 1'
        AND index_row.indoption::text = '0 3 3'
    ) = 1
    AND count(*) FILTER (
      WHERE index_class.relname = 'idx_admin_audit_logs_target_created'
        AND index_method.amname = 'btree'
        AND index_class.relkind = 'i'
        AND index_class.relpersistence = 'p'
        AND index_row.indisvalid
        AND index_row.indisready
        AND index_row.indislive
        AND NOT index_row.indisunique
        AND NOT index_row.indisprimary
        AND NOT index_row.indisexclusion
        AND index_row.indpred IS NULL
        AND index_row.indexprs IS NULL
        AND index_row.indnatts = 4
        AND index_row.indnkeyatts = 4
        AND index_row.indkey::text = '4 5 7 1'
        AND index_row.indoption::text = '0 0 3 3'
    ) = 1
    AND count(*) FILTER (
      WHERE index_class.relname = 'idx_admin_audit_logs_created_desc'
        AND index_method.amname = 'btree'
        AND index_class.relkind = 'i'
        AND index_class.relpersistence = 'p'
        AND index_row.indisvalid
        AND index_row.indisready
        AND index_row.indislive
        AND NOT index_row.indisunique
        AND NOT index_row.indisprimary
        AND NOT index_row.indisexclusion
        AND index_row.indpred IS NULL
        AND index_row.indexprs IS NULL
        AND index_row.indnatts = 2
        AND index_row.indnkeyatts = 2
        AND index_row.indkey::text = '7 1'
        AND index_row.indoption::text = '3 3'
    ) = 1
  INTO indexes_exact
  FROM pg_index index_row
  JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
  JOIN pg_namespace index_namespace ON index_namespace.oid = index_class.relnamespace
  JOIN pg_am index_method ON index_method.oid = index_class.relam
  WHERE index_row.indrelid = table_oid
    AND index_namespace.nspname = 'public';

  RETURN CASE
    WHEN columns_exact AND sequence_exact AND constraints_exact AND indexes_exact THEN 'exact'
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

SELECT pg_temp.auth_version_contract_state() = 'absent' AS apply_auth_version_migration \gset
SELECT pg_temp.admin_audit_contract_state() = 'absent' AS apply_admin_audit_migration \gset

\if :apply_auth_version_migration
\i db/migrations/v25_user_auth_version.sql
\endif
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
