#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
helper="$root/deploy/onprem/scripts/db-restore-rehearsal.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail() { echo "db restore rehearsal test failed: $*" >&2; exit 1; }
assert_file_contains() { grep -F -- "$2" "$1" >/dev/null || fail "missing [$2] in $1"; }

bin="$tmp/bin"; mkdir -p "$bin"
state="$tmp/state"; mkdir -p "$state"
log="$tmp/calls.log"; : > "$log"
cat > "$bin/sha256sum" <<'EOF'
#!/usr/bin/env bash
printf '%s  %s\n' "${FAKE_SHA256:-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}" "$1"
EOF
cat > "$bin/flock" <<'EOF'
#!/usr/bin/env bash
if [[ "${FAKE_FLOCK_FAIL:-0}" == 1 ]]; then exit 1; fi
exit 0
EOF
cat > "$bin/psql" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'psql %s\n' "$*" >> "$FAKE_LOG"
printf 'pg_env=%s,%s,%s,%s\n' "${PGHOST:-}" "${PGPORT:-}" "${PGSERVICE:-}" "${PGPASSWORD:-}" >> "$FAKE_LOG"
if [[ "${FAKE_PSQL_FAIL:-0}" == 1 ]]; then exit 1; fi
if [[ "${FAKE_EXISTING:-0}" == 1 && "$*" == *"pg_database"* ]]; then printf '1\n'; fi
EOF
cat > "$bin/createdb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'createdb %s\n' "$*" >> "$FAKE_LOG"
if [[ "${FAKE_CREATEDB_FAIL:-0}" == 1 ]]; then exit 1; fi
touch "$FAKE_DB_MARKER"
EOF
cat > "$bin/dropdb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'dropdb %s\n' "$*" >> "$FAKE_LOG"
if [[ "${FAKE_DROP_FAIL:-0}" == 1 ]]; then exit 1; fi
rm -f "$FAKE_DB_MARKER"
EOF
cat > "$bin/pg_restore" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == --version ]]; then printf 'pg_restore (PostgreSQL) 18.4\n'; exit 0; fi
printf 'pg_restore %s\n' "$*" >> "$FAKE_LOG"
list=''
for arg in "$@"; do
  [[ "$arg" != - ]] || exit 14
  case "$arg" in --use-list=*) list="${arg#*=}";; esac
done
stdin_payload="$(cat)"
[[ -n "$stdin_payload" ]] || exit 15
if [[ "${1:-}" == --list ]]; then cat "$FAKE_TOC"; exit 0; fi
if [[ -n "$list" && -n "${FAKE_CAPTURE_LIST:-}" ]]; then cp "$list" "$FAKE_CAPTURE_LIST"; fi
if [[ "${FAKE_RESTORE_FAIL:-0}" == 1 ]]; then exit 1; fi
EOF
cat > "$bin/runuser" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'runuser %s\n' "$*" >> "$FAKE_LOG"
[[ "$1" == -u && "$2" == postgres && "$3" == -- ]] || exit 9
shift 3
if [[ "$1" == env && "$2" == -i ]]; then
  shift 2
  args=("$@"); for i in "${!args[@]}"; do [[ "${args[$i]}" == PATH=* ]] && args[$i]="PATH=$FAKE_BIN:/bin:/usr/bin"; done
  exec env -i "FAKE_LOG=$FAKE_LOG" "${args[@]}"
fi
exec "$@"
EOF
chmod +x "$bin"/*

service="$tmp/service.conf"; passfile="$tmp/passfile"; dump="$tmp/dump.sql"; evidence="$tmp/evidence"
: > "$service"; : > "$passfile"; printf 'fake dump\n' > "$dump"; mkdir "$evidence"
chmod 600 "$service" "$passfile" "$dump"
chmod 700 "$evidence"
toc="$tmp/input.toc"
cat > "$toc" <<'EOF'
1; 3079 100 EXTENSION - pgcrypto
2; 3079 101 EXTENSION - pgcrypto_app
3; 0 0 COMMENT - EXTENSION pgcrypto
4; 0 0 COMMENT - EXTENSION pgcrypto_app
5; 1259 200 TABLE - app_pgcrypto
6; 3079 102 EXTENSION - postgis
7; 3079 103 EXTENSION - vector
8; 0 23653 TABLE DATA public spatial_ref_sys rdsadmin
EOF
capture="$tmp/captured.toc"
export PATH="$bin:$PATH" FAKE_LOG="$log" FAKE_TOC="$toc" FAKE_CAPTURE_LIST="$capture" FAKE_DB_MARKER="$state/db" IEUM_DEPLOY_LOCK_PATH="$tmp/lock"
export PGSERVICEFILE="$service" PGPASSFILE="$passfile" FAKE_SHA256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
export IEUM_TEST_MODE=1 IEUM_SOURCE_PG_RESTORE_BIN="$bin/pg_restore"

root_probe="$tmp/root-probe.sh"
sed -e '/^command_name=/,$d' -e 's/if \[\[ "$EUID" -eq 0 \]\]; then/if true; then/' "$helper" >"$root_probe"
printf '%s\n' "PATH=\"$bin:\$PATH\"" 'run_local_pg psql --no-password --dbname=postgres' >>"$root_probe"
PATH="$bin:$PATH" FAKE_LOG="$log" FAKE_BIN="$bin" PGHOST=hostile PGSERVICE=hostile PGSERVICEFILE=hostile PGPASSFILE=hostile PGPASSWORD=hostile PGUSER=hostile PGDATABASE=hostile PGPORT=9999 bash "$root_probe" >/dev/null || fail "root local maintenance probe failed"
grep -F 'runuser -u postgres -- env -i PATH=' "$log" >/dev/null || fail "root maintenance was not wrapped with clean env"
grep -F 'PGHOST=/var/run/postgresql' "$log" >/dev/null || fail "root maintenance did not force Unix socket"
! grep -F 'pg_env=hostile' "$log" >/dev/null || fail "hostile PG environment reached local maintenance"

test -x "$helper" || fail "helper is missing or not executable"

chmod 644 "$dump"
if "$helper" restore --admin-service ieum_target_admin --dump "$dump" --sha256 "$FAKE_SHA256" --evidence-dir "$evidence" >/dev/null 2>&1; then
  fail "world-readable dump was accepted"
fi
[[ ! -e "$state/db" ]] || fail "insecure dump check performed DDL"
chmod 600 "$dump"

insecure_evidence="$tmp/insecure-evidence"; mkdir "$insecure_evidence"; chmod 755 "$insecure_evidence"
: > "$log"
if "$helper" restore --admin-service ieum_target_admin --dump "$dump" --sha256 "$FAKE_SHA256" --evidence-dir "$insecure_evidence" >/dev/null 2>&1; then
  fail "non-root-only evidence directory was accepted"
fi
[[ ! -s "$log" ]] || fail "insecure evidence directory check touched database commands"

lock_victim="$tmp/lock-victim"; printf 'unchanged' > "$lock_victim"
lock_link="$tmp/lock-link"; ln -s "$lock_victim" "$lock_link"
if IEUM_DEPLOY_LOCK_PATH="$lock_link" "$helper" assert-absent --admin-service ieum_target_admin >/dev/null 2>&1; then
  fail "symlinked lock path was accepted"
fi
test "$(cat "$lock_victim")" = unchanged || fail "lock setup modified a symlink target"

insecure_lock_dir="$tmp/insecure-lock-dir"; mkdir "$insecure_lock_dir"; chmod 755 "$insecure_lock_dir"
if IEUM_DEPLOY_LOCK_PATH="$insecure_lock_dir/deploy.lock" "$helper" assert-absent --admin-service ieum_target_admin >/dev/null 2>&1; then
  fail "non-private lock directory was accepted"
fi

if "$helper" restore --admin-service ieum_target_admin --dump "$dump" --sha256 "${FAKE_SHA256%?}b" --evidence-dir "$evidence" >/dev/null 2>&1; then
  fail "checksum failure unexpectedly succeeded"
fi
[[ ! -e "$state/db" ]] || fail "checksum failure performed DDL"

: > "$log"
if FAKE_CREATEDB_FAIL=1 "$helper" restore --admin-service ieum_target_admin --dump "$dump" --sha256 "$FAKE_SHA256" --evidence-dir "$evidence" >/dev/null 2>&1; then
  fail "createdb failure unexpectedly succeeded"
fi

wrong_client="$tmp/pg_restore17"
printf '%s\n' '#!/usr/bin/env bash' 'printf "pg_restore (PostgreSQL) 17.6\\n"' >"$wrong_client"; chmod 755 "$wrong_client"
: >"$log"; rm -f "$state/db"
if IEUM_SOURCE_PG_RESTORE_BIN="$wrong_client" "$helper" restore --admin-service ieum_target_admin --dump "$dump" --sha256 "$FAKE_SHA256" --evidence-dir "$evidence" >/dev/null 2>&1; then fail "wrong PostgreSQL client major was accepted"; fi
if grep -Eq '^(createdb|dropdb|pg_restore) ' "$log"; then fail "wrong PostgreSQL client ran database commands"; fi
writable_client="$tmp/pg_restore-writable"; cp "$bin/pg_restore" "$writable_client"; chmod 777 "$writable_client"
writable_client_physical="$(cd "${writable_client%/*}" && pwd -P)/${writable_client##*/}"
if IEUM_TEST_MODE=0 IEUM_SOURCE_PG_RESTORE_BIN="$writable_client" "$helper" restore --admin-service ieum_target_admin --dump "$dump" --sha256 "$FAKE_SHA256" --evidence-dir "$evidence" >/dev/null 2>&1; then fail "writable override client was accepted"; fi
root_probe="$tmp/client-root-probe.sh"; root_bin="$tmp/root-bin"; mkdir -p "$root_bin"
runtime_dir="$tmp/restore-runtime"; mkdir -p "$runtime_dir"; chmod 700 "$runtime_dir"
cat >"$root_bin/stat" <<'EOF'
#!/usr/bin/env bash
case "$*" in *'%u'*) printf '0\n' ;; *'%a'*) printf '%s\n' "${FAKE_STAT_MODE:-777}" ;; *) exit 9 ;; esac
EOF
cat >"$root_bin/chown" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod 755 "$root_bin/stat" "$root_bin/chown"
sed -e '/^command_name=/,$d' -e 's/if \[\[ "$EUID" -eq 0 \]\]; then/if true; then/' "$helper" >"$root_probe"
printf '%s\n' "PATH=\"$root_bin:/bin:/usr/bin\" IEUM_SOURCE_PG_RESTORE_BIN=\"$writable_client_physical\" IEUM_TEST_MODE=1 configure_source_pg_restore" >>"$root_probe"
printf '%s\n' "export PATH=\"$root_bin:/bin:/usr/bin\" IEUM_TEST_MODE=1 IEUM_PG_RESTORE_RUNTIME_DIR=\"$runtime_dir\"" 'restore_list="$(create_pg_restore_list rehearsal)"' 'printf "RESTORE_LIST=%s\n" "$restore_list"' >>"$root_probe"
if bash "$root_probe" >"$tmp/root-probe.out" 2>"$tmp/root-probe.err"; then fail "root simulation accepted writable PG18 client"; fi
grep -F 'writable by group or other' "$tmp/root-probe.err" >/dev/null || fail "root simulation rejected writable client for wrong reason"
if ! FAKE_STAT_MODE=755 bash "$root_probe" >"$tmp/root-probe-755.out" 2>"$tmp/root-probe-755.err"; then
  cat "$tmp/root-probe-755.err" >&2
  fail "root simulation rejected a root-owned mode 0755 PG18 client"
fi
grep -F "RESTORE_LIST=$runtime_dir/" "$tmp/root-probe-755.out" >/dev/null || fail "root restore list was not staged in the postgres-accessible runtime directory"
if grep -Fq 'dropdb ' "$log"; then
  fail "createdb failure attempted to drop an unproven rehearsal database"
fi

if ! "$helper" restore --admin-service ieum_target_admin --dump "$dump" --sha256 "$FAKE_SHA256" --evidence-dir "$evidence"; then
  cat "$evidence/db-restore-rehearsal.log" >&2 || true; cat "$log" >&2 || true
  fail "valid restore failed"
fi
assert_file_contains "$log" "createdb --no-password --maintenance-db=postgres --template=template0 --owner=ieum ieum_rehearsal"
assert_file_contains "$log" "--role=ieum"
assert_file_contains "$log" "--single-transaction"
grep -E '^pg_restore .*--use-list=[^[:space:]]+$' "$log" >/dev/null || fail "rehearsal dump was not streamed through stdin"
! grep -E '^pg_restore .*([[:space:]]|^)-([[:space:]]|$)' "$log" >/dev/null || fail "rehearsal passed a literal dash instead of using stdin"
assert_file_contains "$capture" "EXTENSION - pgcrypto_app"
assert_file_contains "$capture" "COMMENT - EXTENSION pgcrypto_app"
if grep -E 'EXTENSION[[:space:]]+-[[:space:]]+(pgcrypto|postgis|vector)$' "$capture" >/dev/null; then fail "extension entry was not filtered"; fi
if grep -E 'TABLE DATA[[:space:]]+public[[:space:]]+spatial_ref_sys([[:space:]]|$)' "$capture" >/dev/null; then fail "PostGIS-managed spatial_ref_sys data was not filtered"; fi
if grep -E -- '--(database|db)(=|[[:space:]])' "$log" >/dev/null; then fail "generic database argument leaked"; fi

if FAKE_EXISTING=1 "$helper" restore --admin-service ieum_target_admin --dump "$dump" --sha256 "$FAKE_SHA256" --evidence-dir "$evidence" >/dev/null 2>&1; then
  fail "existing rehearsal database was accepted"
fi

FAKE_RESTORE_FAIL=1 "$helper" restore --admin-service ieum_target_admin --dump "$dump" --sha256 "$FAKE_SHA256" --evidence-dir "$evidence" >/dev/null 2>&1 || true
[[ ! -e "$state/db" ]] || fail "restore failure did not clean partial database"

: > "$log"; touch "$state/db"
"$helper" cleanup --admin-service ieum_target_admin --evidence-dir "$evidence"
assert_file_contains "$log" "dropdb --no-password --maintenance-db=postgres --if-exists ieum_rehearsal"
[[ ! -e "$state/db" ]] || fail "cleanup did not drop rehearsal database"
"$helper" assert-absent --admin-service ieum_target_admin

if FAKE_PSQL_FAIL=1 "$helper" assert-absent --admin-service ieum_target_admin >/dev/null 2>&1; then
  fail "assert-absent accepted a failed database query"
fi

touch "$state/db"
if FAKE_DROP_FAIL=1 "$helper" cleanup --admin-service ieum_target_admin --evidence-dir "$evidence" >/dev/null 2>&1; then
  fail "cleanup accepted a failed dropdb command"
fi
rm -f "$state/db"

echo "db-restore-rehearsal test: PASS"
