#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
helper="$root/deploy/onprem/scripts/db-verify.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail() { echo "db verify test failed: $*" >&2; exit 1; }
contains() { grep -F -- "$2" "$1" >/dev/null || fail "missing [$2] in $1"; }

bin="$tmp/bin"; mkdir -p "$bin"
log="$tmp/psql.log"; : > "$log"
cat > "$bin/psql" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_LOG"
printf 'service=%s\n' "${PGSERVICE:-}" >> "$FAKE_LOG"
sql="$(cat)"
printf 'stdin=%s\n' "$sql" >> "$FAKE_LOG"
if [[ "$*" == *'\gexec'* ]]; then
  printf 'ERROR: syntax error at or near "\\gexec"\n' >&2
  exit 1
fi
[[ "$sql" == *'\gexec'* ]] || { printf 'ERROR: expected SQL on stdin\n' >&2; exit 1; }
[[ "$sql" != *';\gexec'* ]] || { printf 'ERROR: gexec query was terminated before the meta-command\n' >&2; exit 1; }
[[ "${FAKE_FAIL:-0}" == 1 ]] && exit 1
printf 'BEGIN\n%s\nCOMMIT\n' "$FAKE_OUTPUT"
EOF
chmod +x "$bin/psql"

service="$tmp/service.conf"; passfile="$tmp/passfile"; out="$tmp/source.report"
dump_sha256='0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
: > "$service"; : > "$passfile"; chmod 600 "$service" "$passfile"
export PATH="$bin:$PATH" FAKE_LOG="$log" PGSERVICEFILE="$service" PGPASSFILE="$passfile"
export FAKE_OUTPUT='meta|kind=source
meta|database=ieum
meta|server_version_num=180000
extension|pgcrypto|1.3
extension|postgis|3.4
extension|vector|0.7
table|public.ai_question_tasks|2
table|public.knowledge_chunks|4
vector|public.ai_question_tasks|embedding|768
vector|public.knowledge_chunks|embedding|768
index|public.idx_knowledge_chunks_embedding_hnsw|hnsw|vector_cosine_ops|valid|ready
invalid_index_count|0
owner_mismatch_count|1
namespace|{ieum}'

test -x "$helper" || fail "helper is missing or not executable"
if "$helper" capture --kind source --service ieum_rds --output "$out" --dump-sha256 "$dump_sha256" >/dev/null 2>&1; then
  :
else
  fail "valid capture failed"
fi
contains "$log" "service=ieum_rds"
contains "$log" "--file=-"
contains "$log" "stdin="
contains "$log" '\gexec'
if grep -E -- '(^|[[:space:]])-c([[:space:]]|$)' "$log" >/dev/null; then fail "gexec SQL was passed with psql -c"; fi
contains "$log" "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY"
contains "$log" "pg_depend"
contains "$log" "d.objid = c.oid"
contains "$log" "deptype = 'e'"
contains "$log" "c.relkind IN ('r', 'p')"
contains "$log" "'|' || a.atttypmod FROM pg_attribute"
if grep -F -- "a.atttypmod - 4" "$log" >/dev/null; then fail "vector dimensions must use pgvector typmod directly"; fi
contains "$log" "COMMIT"
! grep -E -- 'postgresql://|PGPASSWORD|password=|--password' "$log" >/dev/null || fail "secret/DSN argument leaked"
contains "$out" 'table|public.ai_question_tasks|2'
contains "$out" 'vector|public.knowledge_chunks|embedding|768'

target="$tmp/target.report"
sed -e 's/meta|kind=source/meta|kind=rehearsal-target/' -e 's/meta|database=ieum/meta|database=ieum_rehearsal/' -e 's/meta|server_version_num=180000/meta|server_version_num=170000/' -e 's/owner_mismatch_count|1/owner_mismatch_count|0/' "$out" > "$target"
chmod 600 "$target"
if ! "$helper" compare --source "$out" --target "$target" >/dev/null; then fail "equal reports did not compare"; fi

sed -e 's/extension|pgcrypto|1.3/extension|pgcrypto|1.4/' -e 's/extension|postgis|3.4/extension|postgis|3.6.4/' -e 's/extension|vector|0.7/extension|vector|0.8.6/' "$target" > "$tmp/target.compatible-extension-versions"
chmod 600 "$tmp/target.compatible-extension-versions"
if ! "$helper" compare --source "$out" --target "$tmp/target.compatible-extension-versions" >/dev/null; then
  fail "cross-major target with the same extension set but different package versions did not compare"
fi

sed 's/meta|database=ieum_rehearsal/meta|database=wrong_rehearsal_database/' "$target" > "$tmp/target.wrong-database"
chmod 600 "$tmp/target.wrong-database"
if "$helper" compare --source "$out" --target "$tmp/target.wrong-database" >/dev/null 2>&1; then
  fail "rehearsal target from the wrong database was accepted"
fi

sed 's/meta|server_version_num=170000/meta|server_version_num=180000/' "$target" > "$tmp/target.wrong-version"
chmod 600 "$tmp/target.wrong-version"
if "$helper" compare --source "$out" --target "$tmp/target.wrong-version" >/dev/null 2>&1; then
  fail "rehearsal target with the wrong PostgreSQL major was accepted"
fi

sed 's/meta|dump_sha256=.*/meta|dump_sha256=abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd/' "$target" > "$tmp/target.wrong-dump"
chmod 600 "$tmp/target.wrong-dump"
if "$helper" compare --source "$out" --target "$tmp/target.wrong-dump" >/dev/null 2>&1; then
  fail "target with a mismatched dump checksum was accepted"
fi

sed '/^extension|/d' "$out" > "$tmp/source.no-extensions"
sed '/^extension|/d' "$target" > "$tmp/target.no-extensions"
chmod 600 "$tmp/source.no-extensions" "$tmp/target.no-extensions"
if "$helper" compare --source "$tmp/source.no-extensions" --target "$tmp/target.no-extensions" >/dev/null 2>&1; then
  fail "reports without required extensions were accepted"
fi

sed 's/invalid_index_count|0/invalid_index_count|1/' "$target" > "$tmp/target.invalid-index-count"
chmod 600 "$tmp/target.invalid-index-count"
if "$helper" compare --source "$out" --target "$tmp/target.invalid-index-count" >/dev/null 2>&1; then
  fail "report with invalid indexes was accepted"
fi

sed 's/meta|database=ieum/meta|database=wrong_database/' "$out" > "$tmp/source.wrong-database"
sed 's/meta|database=ieum/meta|database=wrong_database/' "$target" > "$tmp/target.wrong-database"
chmod 600 "$tmp/source.wrong-database" "$tmp/target.wrong-database"
if "$helper" compare --source "$tmp/source.wrong-database" --target "$tmp/target.wrong-database" >/dev/null 2>&1; then
  fail "reports from the wrong database were accepted"
fi

if "$helper" capture --kind rehearsal-target --service ieum_target_rehearsal --output "$tmp/rehearsal-owner-bad" --dump-sha256 "$dump_sha256" >/dev/null 2>&1; then fail "rehearsal target with owner mismatch was accepted"; fi
sed 's/table|public.knowledge_chunks|4/table|public.knowledge_chunks|5/' "$target" > "$target.changed"
chmod 600 "$target.changed"
if "$helper" compare --source "$out" --target "$target.changed" >/dev/null 2>&1; then fail "count mismatch was accepted"; fi
sed 's/vector|public.knowledge_chunks|embedding|768/vector|public.knowledge_chunks|embedding|767/' "$out" > "$target.vector-bad"
chmod 600 "$target.vector-bad"
if "$helper" compare --source "$out" --target "$target.vector-bad" >/dev/null 2>&1; then fail "wrong vector dimension was accepted"; fi
sed 's/index|public.idx_knowledge_chunks_embedding_hnsw|hnsw|vector_cosine_ops|valid|ready/index|public.idx_knowledge_chunks_embedding_hnsw|hnsw|vector_l2_ops|valid|ready/' "$out" > "$target.index-bad"
chmod 600 "$target.index-bad"
if "$helper" compare --source "$out" --target "$target.index-bad" >/dev/null 2>&1; then fail "wrong vector index contract was accepted"; fi

prod="$tmp/prod.report"
sed -e 's/meta|kind=source/meta|kind=production-target/' -e 's/meta|server_version_num=180000/meta|server_version_num=170000/' -e 's/owner_mismatch_count|1/owner_mismatch_count|0/' "$out" > "$prod"
chmod 600 "$prod"
if ! "$helper" compare --source "$out" --target "$prod" >/dev/null; then fail "production namespace report should pass"; fi
sed 's/owner_mismatch_count|0/owner_mismatch_count|1/' "$prod" > "$prod.owner-bad"
chmod 600 "$prod.owner-bad"
if "$helper" compare --source "$out" --target "$prod.owner-bad" >/dev/null 2>&1; then fail "production owner mismatch was accepted"; fi
sed 's/namespace|{ieum}/namespace|{ieum,ieum_rehearsal}/' "$prod" > "$prod.bad"
chmod 600 "$prod.bad"
if "$helper" compare --source "$out" --target "$prod.bad" >/dev/null 2>&1; then fail "extra production namespace was accepted"; fi
if "$helper" compare --source relative.report --target "$prod" >/dev/null 2>&1; then fail "relative source report path was accepted"; fi

if "$helper" capture --kind invalid --service ieum_rds --output "$tmp/bad" >/dev/null 2>&1; then fail "invalid kind accepted"; fi
if "$helper" capture --kind source --service ieum_target --output "$tmp/wrong-service" >/dev/null 2>&1; then fail "source accepted a target service"; fi
insecure_parent="$tmp/insecure-parent"; mkdir "$insecure_parent"; chmod 755 "$insecure_parent"
if "$helper" capture --kind source --service ieum_rds --output "$insecure_parent/report" >/dev/null 2>&1; then fail "insecure output parent accepted"; fi
if FAKE_FAIL=1 "$helper" capture --kind source --service ieum_rds --output "$tmp/fail" --dump-sha256 "$dump_sha256" >/dev/null 2>&1; then fail "psql failure accepted"; fi

echo "db-verify test: PASS"
