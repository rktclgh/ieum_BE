#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [[ -n "${MIGRATION_RUNTIME_ENV:-}" ]]; then
	[[ -f "$MIGRATION_RUNTIME_ENV" && ! -L "$MIGRATION_RUNTIME_ENV" ]] || {
		echo "MIGRATION_RUNTIME_ENV must be a regular file" >&2
		exit 2
	}
	runtime_env_mode="$(stat -c '%a' "$MIGRATION_RUNTIME_ENV" 2>/dev/null || stat -f '%Lp' "$MIGRATION_RUNTIME_ENV")"
	[[ "$runtime_env_mode" == "600" ]] || {
		echo "MIGRATION_RUNTIME_ENV must have mode 600" >&2
		exit 2
	}

	set -a
	# shellcheck source=/dev/null
	source "$MIGRATION_RUNTIME_ENV"
	set +a

	for variable_name in SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD; do
		[[ -n "${!variable_name:-}" ]] || {
			echo "$variable_name is required in MIGRATION_RUNTIME_ENV" >&2
			exit 2
		}
	done

	if [[ "$SPRING_DATASOURCE_URL" =~ ^jdbc:postgresql://([^/:?]+)(:([0-9]+))?/([^/?]+)(\?.*)?$ ]]; then
		export PGHOST="${BASH_REMATCH[1]}"
		export PGPORT="${BASH_REMATCH[3]:-5432}"
		export PGDATABASE="${BASH_REMATCH[4]}"
	else
		echo "SPRING_DATASOURCE_URL must be a PostgreSQL JDBC URL" >&2
		exit 2
	fi
	export PGUSER="$SPRING_DATASOURCE_USERNAME"
	export PGPASSWORD="$SPRING_DATASOURCE_PASSWORD"
fi

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

CREATE OR REPLACE FUNCTION pg_temp.message_type_contract_state()
RETURNS text
LANGUAGE plpgsql
AS $function$
DECLARE
  column_count integer;
  message_type_constraint_count integer;
  system_text_only_constraint_count integer;
  message_type_attnum smallint;
  content_attnum smallint;
  image_file_id_attnum smallint;
  column_exact boolean;
  message_type_constraint_exact boolean;
  system_text_only_constraint_exact boolean;
BEGIN
  IF to_regclass('public.messages') IS NULL THEN
    RETURN 'mismatch';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_class table_class
    JOIN pg_namespace table_namespace ON table_namespace.oid = table_class.relnamespace
    WHERE table_class.oid = 'public.messages'::regclass
      AND table_namespace.nspname = 'public'
      AND table_class.relkind = 'r'
      AND table_class.relpersistence = 'p'
      AND NOT table_class.relispartition
  ) THEN
    RETURN 'mismatch';
  END IF;

  SELECT count(*)
  INTO column_count
  FROM pg_attribute
  WHERE attrelid = 'public.messages'::regclass
    AND attname = 'message_type'
    AND attnum > 0
    AND NOT attisdropped;

  SELECT count(*)
  INTO message_type_constraint_count
  FROM pg_constraint
  WHERE conrelid = 'public.messages'::regclass
    AND conname = 'ck_messages_message_type';

  SELECT count(*)
  INTO system_text_only_constraint_count
  FROM pg_constraint
  WHERE conrelid = 'public.messages'::regclass
    AND conname = 'ck_messages_system_text_only';

  IF column_count = 0 THEN
    RETURN CASE
      WHEN message_type_constraint_count = 0 AND system_text_only_constraint_count = 0 THEN 'absent'
      ELSE 'mismatch'
    END;
  END IF;

  IF message_type_constraint_count <> 1 OR system_text_only_constraint_count <> 1 THEN
    RETURN 'mismatch';
  END IF;

  SELECT attnum
  INTO message_type_attnum
  FROM pg_attribute
  WHERE attrelid = 'public.messages'::regclass
    AND attname = 'message_type'
    AND attnum > 0
    AND NOT attisdropped;

  SELECT attnum
  INTO content_attnum
  FROM pg_attribute
  WHERE attrelid = 'public.messages'::regclass
    AND attname = 'content'
    AND attnum > 0
    AND NOT attisdropped;

  SELECT attnum
  INTO image_file_id_attnum
  FROM pg_attribute
  WHERE attrelid = 'public.messages'::regclass
    AND attname = 'image_file_id'
    AND attnum > 0
    AND NOT attisdropped;

  SELECT count(*) = 1
    AND bool_and(
      attribute.atttypid = 'character varying'::regtype
      AND attribute.atttypmod = 20
      AND attribute.attnotnull
      AND attribute.attgenerated = ''
      AND attribute.attidentity = ''
      AND attribute.atthasdef
      AND default_value.oid IS NOT NULL
      AND regexp_replace(
        pg_get_expr(default_value.adbin, default_value.adrelid),
        '([[:space:]()]|::character varying)',
        '',
        'g'
      ) = '''user'''
    )
  INTO column_exact
  FROM pg_attribute attribute
  LEFT JOIN pg_attrdef default_value
    ON default_value.adrelid = attribute.attrelid
   AND default_value.adnum = attribute.attnum
  WHERE attribute.attrelid = 'public.messages'::regclass
    AND attribute.attname = 'message_type'
    AND attribute.attnum > 0
    AND NOT attribute.attisdropped;

  SELECT count(*) = 1
    AND bool_and(
      constraint_row.contype = 'c'
      AND constraint_row.convalidated
      AND NOT constraint_row.connoinherit
      AND NOT constraint_row.condeferrable
      AND NOT constraint_row.condeferred
      AND constraint_row.conkey = ARRAY[message_type_attnum]
      AND regexp_replace(
        regexp_replace(
          pg_get_expr(constraint_row.conbin, constraint_row.conrelid),
          '::(character varying|text)(\[\])?',
          '',
          'g'
        ),
        '[[:space:]()]',
        '',
        'g'
      ) = 'message_type=ANYARRAY[''user'',''system'']'
    )
  INTO message_type_constraint_exact
  FROM pg_constraint constraint_row
  WHERE constraint_row.conrelid = 'public.messages'::regclass
    AND constraint_row.conname = 'ck_messages_message_type';

  SELECT count(*) = 1
    AND bool_and(
      constraint_row.contype = 'c'
      AND constraint_row.convalidated
      AND NOT constraint_row.connoinherit
      AND NOT constraint_row.condeferrable
      AND NOT constraint_row.condeferred
      AND constraint_row.conkey = ARRAY[message_type_attnum, content_attnum, image_file_id_attnum]
      AND regexp_replace(
        regexp_replace(
          pg_get_expr(constraint_row.conbin, constraint_row.conrelid),
          '::(character varying|text)(\[\])?',
          '',
          'g'
        ),
        '[[:space:]()]',
        '',
        'g'
      ) = 'message_type<>''system''ORcontentISNOTNULLANDimage_file_idISNULL'
    )
  INTO system_text_only_constraint_exact
  FROM pg_constraint constraint_row
  WHERE constraint_row.conrelid = 'public.messages'::regclass
    AND constraint_row.conname = 'ck_messages_system_text_only';

  RETURN CASE
    WHEN column_exact AND message_type_constraint_exact AND system_text_only_constraint_exact THEN 'exact'
    ELSE 'mismatch'
  END;
END
$function$;

CREATE OR REPLACE FUNCTION pg_temp.message_reply_contract_state()
RETURNS text
LANGUAGE plpgsql
AS $function$
DECLARE
  column_count integer;
  constraint_count integer;
  reply_to_attnum smallint;
  message_id_attnum smallint;
  column_exact boolean;
  constraint_exact boolean;
BEGIN
  IF to_regclass('public.messages') IS NULL THEN
    RETURN 'mismatch';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_class table_class
    JOIN pg_namespace table_namespace ON table_namespace.oid = table_class.relnamespace
    WHERE table_class.oid = 'public.messages'::regclass
      AND table_namespace.nspname = 'public'
      AND table_class.relkind = 'r'
      AND table_class.relpersistence = 'p'
      AND NOT table_class.relispartition
  ) THEN
    RETURN 'mismatch';
  END IF;

  SELECT count(*)
  INTO column_count
  FROM pg_attribute
  WHERE attrelid = 'public.messages'::regclass
    AND attname = 'reply_to_message_id'
    AND attnum > 0
    AND NOT attisdropped;

  SELECT count(*)
  INTO constraint_count
  FROM pg_constraint
  WHERE conrelid = 'public.messages'::regclass
    AND conname = 'fk_messages_reply_to_message';

  IF column_count = 0 THEN
    RETURN CASE
      WHEN constraint_count = 0 THEN 'absent'
      ELSE 'mismatch'
    END;
  END IF;

  IF constraint_count <> 1 THEN
    RETURN 'mismatch';
  END IF;

  SELECT attnum
  INTO reply_to_attnum
  FROM pg_attribute
  WHERE attrelid = 'public.messages'::regclass
    AND attname = 'reply_to_message_id'
    AND attnum > 0
    AND NOT attisdropped;

  SELECT attnum
  INTO message_id_attnum
  FROM pg_attribute
  WHERE attrelid = 'public.messages'::regclass
    AND attname = 'message_id'
    AND attnum > 0
    AND NOT attisdropped;

  SELECT count(*) = 1
    AND bool_and(
      attribute.atttypid = 'bigint'::regtype
      AND attribute.atttypmod = -1
      AND NOT attribute.attnotnull
      AND attribute.attgenerated = ''
      AND attribute.attidentity = ''
      AND NOT attribute.atthasdef
    )
  INTO column_exact
  FROM pg_attribute attribute
  WHERE attribute.attrelid = 'public.messages'::regclass
    AND attribute.attname = 'reply_to_message_id'
    AND attribute.attnum > 0
    AND NOT attribute.attisdropped;

  SELECT count(*) = 1
    AND bool_and(
      constraint_row.contype = 'f'
      AND constraint_row.convalidated
      AND NOT constraint_row.condeferrable
      AND NOT constraint_row.condeferred
      AND constraint_row.conkey = ARRAY[reply_to_attnum]
      AND constraint_row.confrelid = 'public.messages'::regclass
      AND constraint_row.confkey = ARRAY[message_id_attnum]
      AND constraint_row.confmatchtype = 's'
      AND constraint_row.confupdtype = 'a'
      AND constraint_row.confdeltype = 'n'
    )
  INTO constraint_exact
  FROM pg_constraint constraint_row
  WHERE constraint_row.conrelid = 'public.messages'::regclass
    AND constraint_row.conname = 'fk_messages_reply_to_message';

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
        ) = 'action=ANYARRAY[''USER_SANCTION_CREATED''::text,''USER_ACTIVATED''::text,''USER_ROLE_CHANGED''::text,''REPORT_CONFIRMED''::text,''REPORT_DISMISSED''::text,''INQUIRY_ANSWERED''::text,''KNOWLEDGE_RELATION_APPROVED''::text,''KNOWLEDGE_RELATION_REJECTED''::text]'
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
        ) = 'target_type=ANYARRAY[''user''::text,''report''::text,''inquiry''::text,''knowledge_relation_candidate''::text]'
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

CREATE OR REPLACE FUNCTION pg_temp.web_push_subscription_contract_state()
RETURNS text
LANGUAGE plpgsql
AS $function$
DECLARE
  table_oid oid;
  columns_exact boolean;
  sequence_exact boolean;
  constraints_exact boolean;
  base_indexes_exact boolean;
  final_indexes_exact boolean;
  trigger_exact boolean;
BEGIN
  SELECT table_class.oid
  INTO table_oid
  FROM pg_class table_class
  JOIN pg_namespace table_namespace ON table_namespace.oid = table_class.relnamespace
  WHERE table_namespace.nspname = 'public'
    AND table_class.relname = 'web_push_subscriptions';

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
  ) OR to_regclass('public.users') IS NULL THEN
    RETURN 'mismatch';
  END IF;

  WITH expected_columns(attnum, attname, type_name, attnotnull, default_expression) AS (
    VALUES
      (1::smallint, 'subscription_id'::name, 'bigint'::text, true,
        'nextval(''web_push_subscriptions_subscription_id_seq''::regclass)'::text),
      (2::smallint, 'user_id'::name, 'bigint'::text, true, NULL::text),
      (3::smallint, 'session_id'::name, 'character varying(64)'::text, true, NULL::text),
      (4::smallint, 'endpoint'::name, 'text'::text, true, NULL::text),
      (5::smallint, 'endpoint_hash'::name, 'character(64)'::text, true, NULL::text),
      (6::smallint, 'p256dh'::name, 'character varying(512)'::text, true, NULL::text),
      (7::smallint, 'auth_secret'::name, 'character varying(256)'::text, true, NULL::text),
      (8::smallint, 'binding_version'::name, 'bigint'::text, true, '1'::text),
      (9::smallint, 'expires_at'::name, 'timestamp with time zone'::text, false, NULL::text),
      (10::smallint, 'created_at'::name, 'timestamp with time zone'::text, true, 'now()'::text),
      (11::smallint, 'updated_at'::name, 'timestamp with time zone'::text, true, 'now()'::text)
  )
  SELECT
    (
      SELECT count(*) = 11
      FROM pg_attribute
      WHERE attrelid = table_oid
        AND attnum > 0
        AND NOT attisdropped
    )
    AND count(*) = 11
    AND bool_and(
      attribute.attnum = expected.attnum
      AND attribute.attname = expected.attname
      AND format_type(attribute.atttypid, attribute.atttypmod) = expected.type_name
      AND attribute.attnotnull = expected.attnotnull
      AND attribute.attgenerated = ''
      AND attribute.attidentity = ''
      AND attribute.atthasdef = (expected.default_expression IS NOT NULL)
      AND (
        (expected.default_expression IS NULL AND default_value.oid IS NULL)
        OR (
          expected.default_expression IS NOT NULL
          AND default_value.oid IS NOT NULL
          AND regexp_replace(
            pg_get_expr(default_value.adbin, default_value.adrelid),
            '[[:space:]]',
            '',
            'g'
          ) = regexp_replace(expected.default_expression, '[[:space:]]', '', 'g')
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
    to_regclass(pg_get_serial_sequence('public.web_push_subscriptions', 'subscription_id'))
      = to_regclass('public.web_push_subscriptions_subscription_id_seq')
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
    AND sequence_class.relname = 'web_push_subscriptions_subscription_id_seq';

  SELECT count(*) = 4
    AND count(*) FILTER (
      WHERE conname = 'web_push_subscriptions_pkey'
        AND contype = 'p'
        AND constraint_row.convalidated
        AND NOT constraint_row.condeferrable
        AND NOT constraint_row.condeferred
        AND constraint_row.conkey = ARRAY[1]::smallint[]
    ) = 1
    AND count(*) FILTER (
      WHERE conname = 'web_push_subscriptions_endpoint_hash_key'
        AND contype = 'u'
        AND constraint_row.convalidated
        AND NOT constraint_row.condeferrable
        AND NOT constraint_row.condeferred
        AND constraint_row.conkey = ARRAY[5]::smallint[]
    ) = 1
    AND count(*) FILTER (
      WHERE conname = 'web_push_subscriptions_user_id_fkey'
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
        AND constraint_row.confdeltype = 'c'
        AND NOT constraint_row.condeferrable
        AND NOT constraint_row.condeferred
    ) = 1
    AND count(*) FILTER (
      WHERE conname = 'web_push_subscriptions_binding_version_check'
        AND contype = 'c'
        AND constraint_row.convalidated
        AND NOT constraint_row.connoinherit
        AND NOT constraint_row.condeferrable
        AND NOT constraint_row.condeferred
        AND constraint_row.conkey = ARRAY[8]::smallint[]
        AND regexp_replace(
          pg_get_expr(constraint_row.conbin, constraint_row.conrelid),
          '[[:space:]()]',
          '',
          'g'
        ) = 'binding_version>0'
    ) = 1
  INTO constraints_exact
  FROM pg_constraint constraint_row
  WHERE constraint_row.conrelid = table_oid
    AND constraint_row.contype <> 'n';

  WITH expected_indexes(index_name, is_unique, is_primary, key_attnum) AS (
    VALUES
      ('web_push_subscriptions_pkey'::name, true, true, 1::smallint),
      ('web_push_subscriptions_endpoint_hash_key'::name, true, false, 5::smallint),
      ('idx_web_push_subscriptions_user'::name, false, false, 2::smallint),
      ('idx_web_push_subscriptions_session'::name, false, false, 3::smallint)
  ), actual_indexes AS (
    SELECT index_class.relname,
           index_class.relkind,
           index_class.relpersistence,
           index_namespace.nspname,
           index_method.amname,
           index_row.indisvalid,
           index_row.indisready,
           index_row.indislive,
           index_row.indisunique,
           index_row.indisprimary,
           index_row.indisexclusion,
           index_row.indpred,
           index_row.indexprs,
           index_row.indnatts,
           index_row.indnkeyatts,
           index_row.indkey,
           index_row.indoption,
           index_row.indcollation,
           index_row.indclass
    FROM pg_index index_row
    JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
    JOIN pg_namespace index_namespace ON index_namespace.oid = index_class.relnamespace
    JOIN pg_am index_method ON index_method.oid = index_class.relam
    WHERE index_row.indrelid = table_oid
  )
  SELECT (SELECT count(*) = 4 FROM actual_indexes)
    AND count(*) = 4
    AND bool_and(
      actual.relkind = 'i'
      AND actual.relpersistence = 'p'
      AND actual.nspname = 'public'
      AND actual.amname = 'btree'
      AND actual.indisvalid
      AND actual.indisready
      AND actual.indislive
      AND actual.indisunique = expected.is_unique
      AND actual.indisprimary = expected.is_primary
      AND NOT actual.indisexclusion
      AND actual.indpred IS NULL
      AND actual.indexprs IS NULL
      AND actual.indnatts = 1
      AND actual.indnkeyatts = 1
      AND actual.indkey::text = expected.key_attnum::text
      AND actual.indoption::text = '0'
      AND index_column.attcollation = index_type.typcollation
      AND actual.indcollation::text = index_column.attcollation::text
      AND actual.indclass::text = (
        SELECT default_opclass.oid::text
        FROM pg_opclass default_opclass
        JOIN pg_am default_opclass_method ON default_opclass_method.oid = default_opclass.opcmethod
        WHERE default_opclass_method.amname = 'btree'
          AND default_opclass.opcdefault
          AND default_opclass.opcintype = index_column.atttypid
      )
    )
  INTO base_indexes_exact
  FROM expected_indexes expected
  JOIN actual_indexes actual ON actual.relname = expected.index_name
  JOIN pg_attribute index_column
    ON index_column.attrelid = table_oid
   AND index_column.attnum = expected.key_attnum
   AND NOT index_column.attisdropped
  JOIN pg_type index_type ON index_type.oid = index_column.atttypid;

  WITH expected_indexes(index_name, is_unique, is_primary, key_attnum) AS (
    VALUES
      ('web_push_subscriptions_pkey'::name, true, true, 1::smallint),
      ('web_push_subscriptions_endpoint_hash_key'::name, true, false, 5::smallint),
      ('idx_web_push_subscriptions_user'::name, false, false, 2::smallint),
      ('uidx_web_push_subscriptions_session'::name, true, false, 3::smallint)
  ), actual_indexes AS (
    SELECT index_class.relname,
           index_class.relkind,
           index_class.relpersistence,
           index_namespace.nspname,
           index_method.amname,
           index_row.indisvalid,
           index_row.indisready,
           index_row.indislive,
           index_row.indisunique,
           index_row.indisprimary,
           index_row.indisexclusion,
           index_row.indpred,
           index_row.indexprs,
           index_row.indnatts,
           index_row.indnkeyatts,
           index_row.indkey,
           index_row.indoption,
           index_row.indcollation,
           index_row.indclass
    FROM pg_index index_row
    JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
    JOIN pg_namespace index_namespace ON index_namespace.oid = index_class.relnamespace
    JOIN pg_am index_method ON index_method.oid = index_class.relam
    WHERE index_row.indrelid = table_oid
  )
  SELECT (SELECT count(*) = 4 FROM actual_indexes)
    AND count(*) = 4
    AND bool_and(
      actual.relkind = 'i'
      AND actual.relpersistence = 'p'
      AND actual.nspname = 'public'
      AND actual.amname = 'btree'
      AND actual.indisvalid
      AND actual.indisready
      AND actual.indislive
      AND actual.indisunique = expected.is_unique
      AND actual.indisprimary = expected.is_primary
      AND NOT actual.indisexclusion
      AND actual.indpred IS NULL
      AND actual.indexprs IS NULL
      AND actual.indnatts = 1
      AND actual.indnkeyatts = 1
      AND actual.indkey::text = expected.key_attnum::text
      AND actual.indoption::text = '0'
      AND index_column.attcollation = index_type.typcollation
      AND actual.indcollation::text = index_column.attcollation::text
      AND actual.indclass::text = (
        SELECT default_opclass.oid::text
        FROM pg_opclass default_opclass
        JOIN pg_am default_opclass_method ON default_opclass_method.oid = default_opclass.opcmethod
        WHERE default_opclass_method.amname = 'btree'
          AND default_opclass.opcdefault
          AND default_opclass.opcintype = index_column.atttypid
      )
    )
  INTO final_indexes_exact
  FROM expected_indexes expected
  JOIN actual_indexes actual ON actual.relname = expected.index_name
  JOIN pg_attribute index_column
    ON index_column.attrelid = table_oid
   AND index_column.attnum = expected.key_attnum
   AND NOT index_column.attisdropped
  JOIN pg_type index_type ON index_type.oid = index_column.atttypid;

  SELECT count(*) = 1
    AND bool_and(
      trigger_row.tgname = 'trg_web_push_subscriptions_updated'
      AND trigger_row.tgfoid = to_regprocedure('public.set_updated_at()')
      AND trigger_row.tgtype = 19
      AND trigger_row.tgenabled = 'O'
      AND trigger_row.tgattr = ''::int2vector
      AND trigger_row.tgqual IS NULL
      AND octet_length(trigger_row.tgargs) = 0
      AND NOT trigger_row.tgdeferrable
      AND NOT trigger_row.tginitdeferred
    )
  INTO trigger_exact
  FROM pg_trigger trigger_row
  WHERE trigger_row.tgrelid = table_oid
    AND NOT trigger_row.tgisinternal;

  IF NOT COALESCE(columns_exact AND sequence_exact AND constraints_exact AND trigger_exact, false) THEN
    RETURN 'mismatch';
  END IF;
  IF COALESCE(final_indexes_exact, false) THEN
    RETURN 'exact';
  END IF;
  IF COALESCE(base_indexes_exact, false) THEN
    RETURN 'base';
  END IF;
  RETURN 'mismatch';
END
$function$;

DO $preflight$
DECLARE
  schedule_title_exists boolean;
  schedule_location_exists boolean;
  schedule_target_enum_exists boolean;
  report_schedule_id_exists boolean;
  report_schedule_fk_exists boolean;
  report_schedule_constraints_exist boolean;
  report_schedule_index_exists boolean;
  report_schedule_trigger_exists boolean;
BEGIN
  IF pg_temp.auth_version_contract_state() = 'mismatch' THEN
    RAISE EXCEPTION 'partial or incompatible users.auth_version schema';
  END IF;
  IF pg_temp.message_type_contract_state() = 'mismatch' THEN
    RAISE EXCEPTION 'partial or incompatible messages.message_type schema';
  END IF;
  IF pg_temp.message_reply_contract_state() = 'mismatch' THEN
    RAISE EXCEPTION 'partial or incompatible messages.reply_to_message_id schema';
  END IF;
  IF pg_temp.admin_audit_contract_state() = 'mismatch' THEN
    RAISE EXCEPTION 'partial or incompatible admin_audit_logs schema';
  END IF;
  IF pg_temp.web_push_subscription_contract_state() = 'mismatch' THEN
    RAISE EXCEPTION 'partial or incompatible web_push_subscriptions schema';
  END IF;

  IF to_regclass('public.meeting_schedules') IS NOT NULL THEN
    SELECT
      EXISTS (
        SELECT 1
        FROM pg_attribute
        WHERE attrelid = to_regclass('public.meeting_schedules')
          AND attname = 'title'
          AND attnum > 0
          AND NOT attisdropped
      ),
      EXISTS (
        SELECT 1
        FROM pg_attribute
        WHERE attrelid = to_regclass('public.meeting_schedules')
          AND attname = 'location_name'
          AND attnum > 0
          AND NOT attisdropped
      )
    INTO schedule_title_exists, schedule_location_exists;

    IF schedule_title_exists <> schedule_location_exists THEN
      RAISE EXCEPTION 'partial or incompatible meeting_schedules detail schema';
    END IF;
  END IF;

  SELECT EXISTS (
    SELECT 1
    FROM pg_enum
    WHERE enumtypid = to_regtype('public.report_target_type')
      AND enumlabel = 'schedule'
  )
  INTO schedule_target_enum_exists;

  SELECT EXISTS (
    SELECT 1
    FROM pg_attribute
    WHERE attrelid = to_regclass('public.reports')
      AND attname = 'schedule_id'
      AND attnum > 0
      AND NOT attisdropped
  )
  INTO report_schedule_id_exists;

  IF NOT schedule_target_enum_exists AND report_schedule_id_exists THEN
    RAISE EXCEPTION 'partial or incompatible reports schedule target schema';
  END IF;

  IF schedule_target_enum_exists AND report_schedule_id_exists THEN
    SELECT EXISTS (
      SELECT 1
      FROM pg_constraint
      WHERE conrelid = to_regclass('public.reports')
        AND conname = 'fk_reports_schedule'
        AND contype = 'f'
        AND convalidated
        AND confrelid = to_regclass('public.meeting_schedules')
        AND confdeltype = 'n'
    )
    INTO report_schedule_fk_exists;

    SELECT count(*) = 3
      AND bool_and(convalidated)
    INTO report_schedule_constraints_exist
    FROM pg_constraint
    WHERE conrelid = to_regclass('public.reports')
      AND conname IN (
        'ck_reports_target_xor',
        'ck_reports_schedule_manual_only',
        'ck_reports_schedule_reported_user'
      );

    SELECT EXISTS (
      SELECT 1
      FROM pg_index index_row
      JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
      WHERE index_row.indrelid = to_regclass('public.reports')
        AND index_class.relname = 'idx_reports_schedule'
        AND index_row.indisvalid
    )
    INTO report_schedule_index_exists;

    SELECT EXISTS (
      SELECT 1
      FROM pg_trigger trigger_row
      JOIN pg_proc function_row ON function_row.oid = trigger_row.tgfoid
      WHERE trigger_row.tgrelid = to_regclass('public.reports')
        AND trigger_row.tgname = 'trg_reports_target_integrity'
        AND NOT trigger_row.tgisinternal
        AND position('schedule_id' IN pg_get_functiondef(function_row.oid)) > 0
    )
    INTO report_schedule_trigger_exists;

    IF NOT (
      report_schedule_fk_exists
      AND report_schedule_constraints_exist
      AND report_schedule_index_exists
      AND report_schedule_trigger_exists
    ) THEN
      RAISE EXCEPTION 'partial or incompatible reports schedule target schema';
    END IF;
  END IF;
END
$preflight$;

SELECT to_regclass('public.ai_report_policy_rules') IS NOT NULL AS apply_report_policy_migrations \gset
SELECT pg_temp.auth_version_contract_state() = 'absent' AS apply_auth_version_migration \gset
SELECT pg_temp.admin_audit_contract_state() = 'absent' AS apply_admin_audit_migration \gset
SELECT pg_temp.web_push_subscription_contract_state() = 'absent' AS apply_web_push_subscription_base_migration \gset
SELECT pg_temp.message_type_contract_state() = 'absent' AS apply_message_type_migration \gset
SELECT (
  to_regclass('public.meeting_schedules') IS NOT NULL
  AND (
    NOT EXISTS (
      SELECT 1
      FROM pg_attribute
      WHERE attrelid = to_regclass('public.meeting_schedules')
        AND attname = 'title'
        AND attnum > 0
        AND NOT attisdropped
    )
    OR NOT EXISTS (
      SELECT 1
      FROM pg_attribute
      WHERE attrelid = to_regclass('public.meeting_schedules')
        AND attname = 'location_name'
        AND attnum > 0
        AND NOT attisdropped
    )
  )
) AS apply_meeting_schedule_details_migration \gset
SELECT (
  to_regtype('public.report_target_type') IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM pg_enum
    WHERE enumtypid = to_regtype('public.report_target_type')
      AND enumlabel = 'schedule'
  )
) AS apply_schedule_report_target_enum_migration \gset
SELECT pg_temp.message_reply_contract_state() = 'absent' AS apply_message_reply_migration \gset

\if :apply_report_policy_migrations
\i db/migrations/v24_seed_report_policy_rules.sql
\i db/migrations/v27_report_policy_sanction_durations.sql
\endif

\if :apply_auth_version_migration
\i db/migrations/v25_user_auth_version.sql
\endif
\if :apply_web_push_subscription_base_migration
\i db/migrations/v25_web_push_subscriptions.sql
\endif
SELECT pg_temp.web_push_subscription_contract_state() = 'base' AS apply_web_push_session_cardinality_migration \gset
\if :apply_admin_audit_migration
\i db/migrations/v26_admin_audit_logs.sql
\endif
\if :apply_web_push_session_cardinality_migration
\i db/migrations/v26_web_push_session_cardinality.sql
\endif
\if :apply_message_type_migration
\i db/migrations/v28_chat_system_messages.sql
\endif
\if :apply_meeting_schedule_details_migration
\i db/migrations/v29_meeting_schedule_details.sql
\endif
\if :apply_schedule_report_target_enum_migration
\i db/migrations/v30_report_schedule_target_enum.sql
\endif
SELECT (
  to_regclass('public.reports') IS NOT NULL
  AND to_regclass('public.meeting_schedules') IS NOT NULL
  AND EXISTS (
    SELECT 1
    FROM pg_enum
    WHERE enumtypid = to_regtype('public.report_target_type')
      AND enumlabel = 'schedule'
  )
  AND NOT EXISTS (
    SELECT 1
    FROM pg_attribute
    WHERE attrelid = to_regclass('public.reports')
      AND attname = 'schedule_id'
      AND attnum > 0
      AND NOT attisdropped
  )
) AS apply_schedule_report_target_migration \gset
\if :apply_schedule_report_target_migration
\i db/migrations/v31_report_schedule_target.sql
\endif
\if :apply_message_reply_migration
\i db/migrations/v32_chat_message_reply.sql
\endif
SELECT NOT EXISTS (
  SELECT 1
  FROM pg_constraint
  WHERE conrelid = 'public.ai_question_tasks'::regclass
    AND conname = 'ck_ai_question_tasks_grounding_status'
) AS apply_question_ai_ungrounded_migration \gset
\if :apply_question_ai_ungrounded_migration
\i db/migrations/v33_question_ai_ungrounded_answer.sql
\endif
\i db/migrations/v34_question_ai_ungrounded_answer_validate.sql
\i db/migrations/v35_knowledge_relation_candidates.sql
SELECT (
  to_regclass('public.meeting_schedules') IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM pg_attribute
    WHERE attrelid = to_regclass('public.meeting_schedules')
      AND attname = 'starts_on'
      AND attnum > 0
      AND NOT attisdropped
  )
) AS apply_meeting_schedule_date_time_migration \gset
\if :apply_meeting_schedule_date_time_migration
\i db/migrations/v36_meeting_schedule_date_time.sql
\endif
\i db/migrations/v37_notification_i18n.sql

DO $verify$
BEGIN
  IF pg_temp.auth_version_contract_state() <> 'exact' THEN
    RAISE EXCEPTION 'users.auth_version schema verification failed';
  END IF;
  IF pg_temp.message_type_contract_state() <> 'exact' THEN
    RAISE EXCEPTION 'messages.message_type schema verification failed';
  END IF;
  IF pg_temp.message_reply_contract_state() <> 'exact' THEN
    RAISE EXCEPTION 'messages.reply_to_message_id schema verification failed';
  END IF;
  IF pg_temp.admin_audit_contract_state() <> 'exact' THEN
    RAISE EXCEPTION 'admin_audit_logs schema verification failed';
  END IF;
  IF pg_temp.web_push_subscription_contract_state() <> 'exact' THEN
    RAISE EXCEPTION 'web_push_subscriptions schema verification failed: expected exact v25/v26 contract';
  END IF;
END
$verify$;

SELECT pg_advisory_unlock(hashtextextended('ieum:admin-dashboard:v25-v26', 0));
\echo 'Admin dashboard schema verification passed.'
SQL
