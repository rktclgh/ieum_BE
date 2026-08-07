#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
helper="$root/deploy/onprem/scripts/db-preflight.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail() { echo "db preflight test failed: $*" >&2; exit 1; }
assert_log() { grep -F -- "$2" "$1" >/dev/null || fail "missing [$2] in log"; }

bin="$tmp/bin"; mkdir -p "$bin"
log="$tmp/psql.log"; : >"$log"
cat >"$bin/psql" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'db=%s args=%s query=%s\n' "${PGDATABASE:-}" "$*" "${*: -1}" >>"$FAKE_LOG"
case "${FAKE_MODE:-ok}" in
  bad-version) [[ "${*: -1}" == *server_version_num* ]] && printf '160005\n' ;;
  missing-ext) [[ "${*: -1}" == *pg_available_extensions* ]] && printf 'pgcrypto\npostgis\n' ;;
  missing-installed) [[ "${*: -1}" == *pg_extension* ]] && printf 'pgcrypto\npostgis\n' ;;
  bad-role) [[ "${*: -1}" == *pg_roles* ]] && printf 'ieum|t|t|f|f|f|f\n' ;;
  bad-replication) [[ "${*: -1}" == *pg_roles* ]] && printf 'ieum|t|f|f|f|t|f\n' ;;
  bad-bypass) [[ "${*: -1}" == *pg_roles* ]] && printf 'ieum|t|f|f|f|f|t\n' ;;
  bad-vector) [[ "${*: -1}" == *vector_dims* ]] && printf '767\n' ;;
  *)
    case "${*: -1}" in
      *server_version_num*) printf '170002\n' ;;
      *pg_available_extensions*) printf 'pgcrypto\npostgis\nvector\n' ;;
      *pg_extension*) printf 'pgcrypto\npostgis\nvector\n' ;;
      *pg_roles*) printf 'ieum|t|f|f|f|f|f\n' ;;
      *vector_dims*) printf 'BEGIN\nCREATE TABLE\nINSERT 0 1\n768\nROLLBACK\n' ;;
    esac
    ;;
esac
EOF
chmod 755 "$bin/psql"

service="$tmp/service"; passfile="$tmp/pass"
: >"$service"; : >"$passfile"; chmod 600 "$service" "$passfile"
export PATH="$bin:$PATH" FAKE_LOG="$log" PGSERVICEFILE="$service" PGPASSFILE="$passfile"

test -x "$helper" || fail "helper missing or not executable"

if "$helper" --kind rehearsal --admin-service ieum_target_admin --dbname ieum >/dev/null 2>&1; then
  fail "database argument was accepted"
fi
if "$helper" --kind rehearsal --admin-service wrong >/dev/null 2>&1; then
  fail "unexpected admin service was accepted"
fi
if "$helper" --kind rehearsal --admin-service ieum_target_admin >/dev/null 2>&1; then
  :
else
  fail "valid rehearsal preflight failed"
fi
assert_log "$log" '--dbname=ieum_rehearsal'
assert_log "$log" '--no-password'
assert_log "$log" '--no-psqlrc'
assert_log "$log" '--set=ON_ERROR_STOP=1'
grep -F 'CREATE TEMP TABLE' "$log" >/dev/null || fail "vector probe did not use a temporary table transaction"
if grep -E -- '--dbname=(ieum|ieum_test|ieum_restore)( |$)' "$log" >/dev/null; then fail "literal target leaked"; fi

if FAKE_MODE=bad-version "$helper" --kind cluster --admin-service ieum_target_admin >/dev/null 2>&1; then
  fail "non-PG17 cluster was accepted"
fi
if FAKE_MODE=missing-ext "$helper" --kind cluster --admin-service ieum_target_admin >/dev/null 2>&1; then
  fail "cluster missing extension was accepted"
fi
if FAKE_MODE=bad-role "$helper" --kind production --admin-service ieum_target_admin >/dev/null 2>&1; then
  fail "privileged application role was accepted"
fi
if FAKE_MODE=bad-replication "$helper" --kind production --admin-service ieum_target_admin >/dev/null 2>&1; then
  fail "replication-capable application role was accepted"
fi
if FAKE_MODE=bad-bypass "$helper" --kind production --admin-service ieum_target_admin >/dev/null 2>&1; then
  fail "bypass-RLS application role was accepted"
fi
if FAKE_MODE=bad-vector "$helper" --kind production --admin-service ieum_target_admin >/dev/null 2>&1; then
  fail "bad vector probe was accepted"
fi

chmod 644 "$passfile"
if "$helper" --kind cluster --admin-service ieum_target_admin >/dev/null 2>&1; then
  fail "insecure passfile was accepted"
fi

echo "db-preflight test: PASS"
