#!/usr/bin/env bash
set -euo pipefail
umask 077

die() { echo "db verify: $*" >&2; exit 2; }
usage() { echo "usage: $0 capture --kind {source|rehearsal-target|production-target} --service NAME --output ABSOLUTE_PATH --dump-sha256 SHA256 | $0 compare --source REPORT --target REPORT" >&2; exit 2; }
is_abs() { [[ "$1" = /* && "$1" != *$'\n'* ]]; }
mode_of() { stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1"; }
owner_of() { stat -c '%u' "$1" 2>/dev/null || stat -f '%u' "$1"; }
regular_private() { local f=$1 label=$2; [[ -f "$f" && ! -L "$f" ]] || die "$label must be a regular non-symlink file"; [[ "$(owner_of "$f")" == "$EUID" ]] || die "$label has unexpected owner"; [[ "$(mode_of "$f")" == 600 ]] || die "$label must have mode 0600"; }
require_libpq() { [[ -n "${PGSERVICEFILE:-}" && -n "${PGPASSFILE:-}" ]] || die "PGSERVICEFILE and PGPASSFILE are required"; regular_private "$PGSERVICEFILE" PGSERVICEFILE; regular_private "$PGPASSFILE" PGPASSFILE; }
require_output() { local f=$1 parent; is_abs "$f" || die "--output must be absolute"; [[ ! -L "$f" ]] || die "--output must not be a symlink"; parent=${f%/*}; [[ -n "$parent" ]] || parent=/; [[ -d "$parent" && ! -L "$parent" ]] || die "output directory is unsafe"; [[ "$(owner_of "$parent")" == "$EUID" ]] || die "output directory has unexpected owner"; [[ "$(mode_of "$parent")" == 700 ]] || die "output directory must have mode 0700"; [[ ! -e "$f" ]] || regular_private "$f" output; }
require_report() { is_abs "$1" || die "report path must be absolute"; regular_private "$1" report; }
valid_service() { [[ "$1" =~ ^[A-Za-z0-9_.-]+$ ]]; }
valid_dump_sha256() { [[ "$1" =~ ^[0-9a-f]{64}$ ]]; }
valid_kind() { [[ "$1" == source || "$1" == rehearsal-target || "$1" == production-target ]]; }
expected_service() { case "$1" in source) echo ieum_rds;; rehearsal-target) echo ieum_target_rehearsal;; production-target) echo ieum_target;; esac; }

capture() {
  require_libpq; require_output "$output"; valid_dump_sha256 "$dump_sha256" || die "--dump-sha256 must be exactly 64 lowercase hex characters"; command -v psql >/dev/null 2>&1 || die "psql is required"
  local parent=${output%/*} tmp; tmp=$(mktemp "$parent/.db-verify.XXXXXX") || die "unable to create temporary report"; chmod 600 "$tmp"; trap 'rm -f "$tmp"' EXIT
  # Feed the script through --file so psql handles the \gexec meta-command.
  PGSERVICE="$service" PGSERVICEFILE="$PGSERVICEFILE" PGPASSFILE="$PGPASSFILE" psql --no-password --no-psqlrc --quiet --tuples-only --no-align --set=ON_ERROR_STOP=1 --file=- <<SQL >"$tmp" || die "psql capture failed"
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT 'meta|kind=' || '$kind';
SELECT 'meta|database=' || current_database();
SELECT 'meta|server_version_num=' || current_setting('server_version_num');
SELECT 'extension|' || extname || '|' || extversion FROM pg_extension ORDER BY extname;
SELECT format('SELECT ''table|%s.%s|'' || count(*) FROM ONLY %I.%I;', quote_ident(n.nspname), quote_ident(c.relname), n.nspname, c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relkind = 'r' AND n.nspname = 'public' ORDER BY n.nspname, c.relname
\gexec
SELECT 'vector|' || quote_ident(n.nspname) || '.' || quote_ident(c.relname) || '|' || quote_ident(a.attname) || '|' || a.atttypmod FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_type t ON t.oid = a.atttypid WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND t.typname = 'vector' AND NOT a.attisdropped ORDER BY c.relname, a.attname;
SELECT 'index|' || quote_ident(i.schemaname) || '.' || quote_ident(i.indexname) || '|' || am.amname || '|' || COALESCE((SELECT string_agg(opc.opcname, ',' ORDER BY op.ordinality) FROM unnest(x.indclass) WITH ORDINALITY op(oid, ordinality) JOIN pg_opclass opc ON opc.oid = op.oid), '') || '|' || CASE WHEN x.indisvalid THEN 'valid' ELSE 'invalid' END || '|' || CASE WHEN x.indisready THEN 'ready' ELSE 'not-ready' END FROM pg_indexes i JOIN pg_namespace n ON n.nspname = i.schemaname JOIN pg_class c ON c.relname = i.indexname AND c.relnamespace = n.oid AND c.relkind = 'i' JOIN pg_index x ON x.indexrelid = c.oid JOIN pg_am am ON am.oid = c.relam ORDER BY i.schemaname, i.indexname;
SELECT 'invalid_index_count|' || count(*) FROM pg_index WHERE NOT indisvalid;
SELECT 'owner_mismatch_count|' || count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relkind IN ('r','p','S','v','m') AND n.nspname = 'public' AND pg_get_userbyid(c.relowner) <> 'ieum' AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e');
SELECT 'namespace|' || COALESCE('{' || string_agg(datname, ',' ORDER BY datname) || '}', '{}') FROM pg_database WHERE datname ~ '^ieum($|_)';
COMMIT;
SQL
  printf 'meta|dump_sha256=%s\n' "$dump_sha256" >>"$tmp"
  LC_ALL=C awk 'NF && $0 != "BEGIN" && $0 != "COMMIT" { if ($0 !~ /^(meta|extension|table|vector|index|invalid_index_count|owner_mismatch_count|namespace)\|/) exit 1; print }' "$tmp" >"$output" || die "capture returned an invalid report"
  chmod 600 "$tmp"; validate_contract "$tmp"; [[ "$kind" == source ]] || require_target_ownership_clean "$tmp"; mv "$tmp" "$output"; trap - EXIT
}

validate_contract() {
  local report=$1 report_kind expected_database expected_major
  report_kind=$(awk -F= '/^meta\|kind=/{print $2; exit}' "$report")
  case "$report_kind" in
    source) expected_database=ieum; expected_major=18;;
    rehearsal-target) expected_database=ieum_rehearsal; expected_major=17;;
    production-target) expected_database=ieum; expected_major=17;;
    *) die "report kind is invalid";;
  esac
  grep -Fqx "meta|database=$expected_database" "$report" || die "report database must be $expected_database"
  awk -F= -v expected="$expected_major" '/^meta\|server_version_num=/ { count++; value = $2 } END { exit !(count == 1 && value ~ /^[0-9]+$/ && int(value / 10000) == expected) }' "$report" || die "report PostgreSQL major does not match kind"
  awk -F= '/^meta\|dump_sha256=/ { count++; value = $2 } END { exit !(count == 1 && length(value) == 64 && value ~ /^[0-9a-f]+$/) }' "$report" || die "report must contain exactly one valid dump checksum"
  grep -E '^extension\|pgcrypto\|' "$report" >/dev/null || die "pgcrypto extension is required"
  grep -E '^extension\|postgis\|' "$report" >/dev/null || die "postgis extension is required"
  grep -E '^extension\|vector\|' "$report" >/dev/null || die "vector extension is required"
  grep -Fqx 'invalid_index_count|0' "$report" || die "report contains invalid indexes"
  grep -Fqx 'vector|public.ai_question_tasks|embedding|768' "$report" || die "ai_question_tasks embedding dimension is not 768"
  grep -Fqx 'vector|public.knowledge_chunks|embedding|768' "$report" || die "knowledge_chunks embedding dimension is not 768"
  grep -Fqx 'index|public.idx_knowledge_chunks_embedding_hnsw|hnsw|vector_cosine_ops|valid|ready' "$report" || die "required knowledge_chunks HNSW vector index is not valid and ready"
}

require_target_ownership_clean() {
  local report=$1
  awk -F'|' '
    $1 == "owner_mismatch_count" && NF == 2 { count++; value = $2 }
    END { exit !(count == 1 && value == "0") }
  ' "$report" || die "target report must contain owner_mismatch_count|0"
}

normalize_comparable_contract() {
  sed -e '/^meta|kind=/d' -e '/^meta|database=/d' -e '/^meta|server_version_num=/d' -e '/^namespace|/d' -e '/^owner_mismatch_count|/d' "$1" |
    awk -F'|' 'BEGIN { OFS = FS } $1 == "extension" { print $1, $2; next } { print }' |
    LC_ALL=C sort
}

compare() {
  require_report "$source"; require_report "$target"
  local source_kind target_kind
  source_kind=$(awk -F= '/^meta\|kind=/{print $2; exit}' "$source"); target_kind=$(awk -F= '/^meta\|kind=/{print $2; exit}' "$target")
  [[ "$source_kind" == source ]] || die "source report kind must be source"
  valid_kind "$target_kind" && [[ "$target_kind" != source ]] || die "target report kind must be a target kind"
  validate_contract "$source"; validate_contract "$target"; require_target_ownership_clean "$target"
  [[ "$(awk -F= '/^meta\|dump_sha256=/{print $2; exit}' "$source")" == "$(awk -F= '/^meta\|dump_sha256=/{print $2; exit}' "$target")" ]] || die "source and target dump checksums differ"
  diff -u <(normalize_comparable_contract "$source") <(normalize_comparable_contract "$target") >/dev/null || die "source and target contracts differ"
  if [[ "$target_kind" == production-target ]]; then [[ "$(awk -F'|' '/^namespace\|/{print $2; exit}' "$target")" == '{ieum}' ]] || die "production namespace is not exactly {ieum}"; fi
}

[[ $# -gt 0 ]] || usage
command_name=$1; shift; service=''; kind=''; output=''; source=''; target=''; dump_sha256=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --service) [[ $# -ge 2 ]] || die "--service requires a value"; service=$2; shift 2;;
    --kind) [[ $# -ge 2 ]] || die "--kind requires a value"; kind=$2; shift 2;;
    --output) [[ $# -ge 2 ]] || die "--output requires a value"; output=$2; shift 2;;
    --source) [[ $# -ge 2 ]] || die "--source requires a value"; source=$2; shift 2;;
    --target) [[ $# -ge 2 ]] || die "--target requires a value"; target=$2; shift 2;;
    --dump-sha256) [[ $# -ge 2 ]] || die "--dump-sha256 requires a value"; dump_sha256=$2; shift 2;;
    *) die "unsupported argument: $1";;
  esac
done
case "$command_name" in
  capture) valid_kind "$kind" || die "invalid --kind"; [[ -n "$service" ]] && valid_service "$service" || die "invalid --service"; [[ "$service" == "$(expected_service "$kind")" ]] || die "service does not match kind"; [[ -n "$output" ]] || die "--output is required"; capture;;
  compare) [[ -n "$source" && -n "$target" ]] || die "--source and --target are required"; compare;;
  *) usage;;
esac
