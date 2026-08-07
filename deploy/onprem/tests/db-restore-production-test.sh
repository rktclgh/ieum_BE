#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
helper="$root/deploy/onprem/scripts/db-restore-production.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail() { echo "db restore production test failed: $*" >&2; exit 1; }

bin="$tmp/bin"; mkdir -p "$bin"
log="$tmp/calls.log"; : > "$log"
state="$tmp/state"; mkdir -p "$state"
for command in flock; do
  cat > "$bin/$command" <<'EOF'
#!/usr/bin/env bash
printf '%s %s\n' "$(basename "$0")" "$*" >> "$FAKE_LOG"
if [[ "${FAKE_REMOVE_FENCE_ON_LOCK:-0}" == 1 ]]; then
  rm -f "$IEUM_PRODUCTION_WRITE_FENCE_PATH"
fi
exit 0
EOF
  chmod +x "$bin/$command"
done
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
if [[ "${FAKE_DROP_AFTER_REMOVE_FAIL_ONCE:-0}" == 1 && ! -e "$FAKE_DROP_AFTER_REMOVE_FAIL_MARKER" ]]; then
  touch "$FAKE_DROP_AFTER_REMOVE_FAIL_MARKER"
  exit 1
fi
EOF
cat > "$bin/pg_restore" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == --version ]]; then printf 'pg_restore (PostgreSQL) 18.4\n'; exit 0; fi
printf 'pg_restore %s\n' "$*" >> "$FAKE_LOG"
for arg in "$@"; do [[ "$arg" != - ]] || exit 14; done
stdin_payload="$(cat)"
[[ -n "$stdin_payload" ]] || exit 15
if [[ "${1:-}" == --list ]]; then
  if [[ "${FAKE_TARGET_BACKUP_LIST_FAIL:-0}" == 1 && ! -e "$FAKE_TARGET_BACKUP_LIST_MARKER" ]]; then
    touch "$FAKE_TARGET_BACKUP_LIST_MARKER"
    exit 1
  fi
  if [[ -n "${FAKE_TOC:-}" ]]; then cat "$FAKE_TOC"; else printf '1; 0 0 TABLE DATA\n'; fi
  exit 0
fi
for arg in "$@"; do
  case "$arg" in
    --use-list=*) cp "${arg#*=}" "$FAKE_CAPTURE_LIST" ;;
  esac
done
if [[ "${FAKE_RESTORE_FAIL_ONCE:-0}" == 1 && ! -e "$FAKE_RESTORE_FAIL_MARKER" ]]; then
  touch "$FAKE_RESTORE_FAIL_MARKER"
  exit 1
fi
EOF
cat > "$bin/pg_dump" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == --version ]]; then printf 'pg_dump (PostgreSQL) 17.6\n'; exit 0; fi
printf 'pg_dump %s\n' "$*" >> "$FAKE_LOG"
if [[ "${FAKE_REMOVE_FENCE_ON_BACKUP:-0}" == 1 ]]; then
  rm -f "$IEUM_PRODUCTION_WRITE_FENCE_PATH"
fi
[[ "$*" != *'--file='* ]] || exit 13
printf 'target backup\n'
EOF
cat > "$bin/sha256sum" <<'EOF'
#!/usr/bin/env bash
printf '%s  %s\n' "${FAKE_SHA256:-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}" "$1"
EOF
cat > "$bin/psql" <<'EOF'
#!/usr/bin/env bash
printf 'psql %s\n' "$*" >> "$FAKE_LOG"
printf 'pg_env=%s,%s,%s,%s\n' "${PGHOST:-}" "${PGPORT:-}" "${PGSERVICE:-}" "${PGPASSWORD:-}" >> "$FAKE_LOG"
if [[ "${FAKE_REMAINING_TARGET_CONNECTIONS:-0}" == 1 && "$*" == *"pg_stat_activity"* ]]; then
  printf '1\n'
fi
if [[ "$*" == *"datname = 'ieum_rehearsal'"* && -e "${FAKE_REHEARSAL_DB_MARKER:-}" ]]; then
  printf '1\n'
fi
if [[ -e "$FAKE_DB_MARKER" && "$*" == *"pg_database"* && "$*" != *"ieum_rehearsal"* ]]; then
  printf '1\n'
fi
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
chmod +x "$bin/createdb" "$bin/dropdb" "$bin/flock" "$bin/pg_dump" "$bin/pg_restore" "$bin/sha256sum" "$bin/psql" "$bin/runuser"
cp "$bin/pg_restore" "$bin/pg_restore17"
perl -pi -e 's/18\.4/17.6/' "$bin/pg_restore17"
chmod +x "$bin/pg_restore17"

service="$tmp/service.conf"
passfile="$tmp/passfile"
dump="$tmp/ieum-final.dump"
evidence="$tmp/evidence"
: > "$service"; : > "$passfile"; printf 'fake dump\n' > "$dump"; mkdir "$evidence"
chmod 600 "$service" "$passfile" "$dump"
chmod 700 "$evidence"

export PATH="$bin:$PATH"
export FAKE_LOG="$log"
export PGSERVICEFILE="$service"
export PGPASSFILE="$passfile"
export IEUM_TEST_MODE=1 IEUM_SOURCE_PG_RESTORE_BIN="$bin/pg_restore" IEUM_TARGET_PG_DUMP_BIN="$bin/pg_dump" IEUM_TARGET_PG_RESTORE_BIN="$bin/pg_restore17"
export IEUM_DEPLOY_LOCK_PATH="$tmp/deploy.lock"
export IEUM_PRODUCTION_WRITE_FENCE_PATH="$tmp/missing-write-fence"
export FAKE_DB_MARKER="$state/db"
export FAKE_REHEARSAL_DB_MARKER="$state/rehearsal-db"
export FAKE_RESTORE_FAIL_MARKER="$state/restore-failed-once"
export FAKE_DROP_AFTER_REMOVE_FAIL_MARKER="$state/drop-removed-then-failed-once"
export FAKE_TARGET_BACKUP_LIST_MARKER="$state/target-backup-list-failed"

root_probe="$tmp/root-probe.sh"
sed -e '/^command_name=/,$d' -e 's/if \[\[ "$EUID" -eq 0 \]\]; then/if true; then/' "$helper" >"$root_probe"
printf '%s\n' "PATH=\"$bin:\$PATH\"" 'run_local_pg psql --no-password --dbname=postgres' >>"$root_probe"
PATH="$bin:$PATH" FAKE_LOG="$log" FAKE_BIN="$bin" PGHOST=hostile PGSERVICE=hostile PGSERVICEFILE=hostile PGPASSFILE=hostile PGPASSWORD=hostile PGUSER=hostile PGDATABASE=hostile PGPORT=9999 bash "$root_probe" >/dev/null || fail "root local maintenance probe failed"
grep -F 'runuser -u postgres -- env -i PATH=' "$log" >/dev/null || fail "root maintenance was not wrapped with clean env"
grep -F 'PGHOST=/var/run/postgresql' "$log" >/dev/null || fail "root maintenance did not force Unix socket"
! grep -F 'pg_env=hostile' "$log" >/dev/null || fail "hostile PG environment reached local maintenance"
: > "$log"

test -x "$helper" || fail "helper is missing or not executable"

if "$helper" restore \
  --admin-service ieum_target_admin \
  --dump "$dump" \
  --sha256 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --evidence-dir "$evidence" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "restore was accepted without a production write fence"
fi

grep -F 'production write fence is required' "$tmp/stderr" >/dev/null || fail "missing write-fence rejection"
if grep -Eq '^(psql|pg_dump|dropdb|createdb|pg_restore) ' "$log"; then
  fail "write-fence rejection touched database commands"
fi

fence="$tmp/write-fence"
printf 'IEUM_PRODUCTION_WRITE_FENCE=enabled\n' > "$fence"
chmod 600 "$fence"
export IEUM_PRODUCTION_WRITE_FENCE_PATH="$fence"

printf 'IEUM_PRODUCTION_WRITE_FENCE=enabled\nunexpected-extra-content\n' > "$fence"
: > "$log"
if "$helper" restore \
  --admin-service ieum_target_admin \
  --dump "$dump" \
  --sha256 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --evidence-dir "$evidence" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "restore accepted a write fence with extra content"
fi
grep -F 'production write fence is invalid' "$tmp/stderr" >/dev/null || fail "extra write-fence content was not rejected"
if grep -Eq '^(psql|pg_dump|dropdb|createdb|pg_restore) ' "$log"; then
  fail "invalid write fence touched database commands"
fi
printf 'IEUM_PRODUCTION_WRITE_FENCE=enabled\n' > "$fence"
chmod 600 "$fence"

: > "$log"
touch "$FAKE_DB_MARKER"
if FAKE_REMOVE_FENCE_ON_BACKUP=1 "$helper" restore \
  --admin-service ieum_target_admin \
  --dump "$dump" \
  --sha256 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --evidence-dir "$evidence" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "restore accepted a write fence removed after the backup"
fi
grep -F 'production write fence is required' "$tmp/stderr" >/dev/null || fail "write fence was not revalidated immediately before replacement"
if grep -Eq '^dropdb ' "$log"; then
  fail "write fence removal after backup still reached destructive drop"
fi
printf 'IEUM_PRODUCTION_WRITE_FENCE=enabled\n' > "$fence"
chmod 600 "$fence"

: > "$log"
touch "$FAKE_DB_MARKER"
if FAKE_REMAINING_TARGET_CONNECTIONS=1 "$helper" restore \
  --admin-service ieum_target_admin \
  --dump "$dump" \
  --sha256 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --evidence-dir "$evidence" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "restore accepted remaining non-admin target connections"
fi
grep -F 'production target database still has non-admin connections' "$tmp/stderr" >/dev/null || fail "remaining target connections were not rejected"
grep -F 'SELECT count(*) FROM pg_stat_activity WHERE datname = '\''ieum'\'' AND pid <> pg_backend_pid();' "$log" >/dev/null || fail "remaining target connection check did not count every non-admin session"
if grep -Eq '^dropdb ' "$log"; then
  fail "remaining target connections reached destructive drop"
fi

: > "$log"
touch "$FAKE_DB_MARKER" "$FAKE_REHEARSAL_DB_MARKER"
if "$helper" restore \
  --admin-service ieum_target_admin \
  --dump "$dump" \
  --sha256 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --evidence-dir "$evidence" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "restore ran while ieum_rehearsal was present"
fi
grep -F 'rehearsal database must be absent before production restore' "$tmp/stderr" >/dev/null || fail "rehearsal database presence was not rejected"
if grep -Eq '^(pg_dump|dropdb|createdb|pg_restore) ' "$log"; then
  fail "rehearsal database guard touched backup or destructive commands"
fi
rm -f "$FAKE_REHEARSAL_DB_MARKER"

: > "$log"
touch "$FAKE_DB_MARKER"
rm -f "$FAKE_TARGET_BACKUP_LIST_MARKER"
if FAKE_TARGET_BACKUP_LIST_FAIL=1 "$helper" restore \
  --admin-service ieum_target_admin \
  --dump "$dump" \
  --sha256 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --evidence-dir "$evidence" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "restore accepted an uninspectable target backup"
fi
grep -F 'unable to inspect target backup TOC' "$tmp/stderr" >/dev/null || fail "target backup structure was not validated before replacement"
if grep -Eq '^dropdb ' "$log"; then
  fail "uninspectable target backup reached destructive drop"
fi

: > "$log"
if FAKE_REMOVE_FENCE_ON_LOCK=1 "$helper" restore \
  --admin-service ieum_target_admin \
  --dump "$dump" \
  --sha256 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --evidence-dir "$evidence" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "restore accepted a write fence removed during lock acquisition"
fi

grep -F 'production write fence is required' "$tmp/stderr" >/dev/null || fail "removed write fence was not revalidated under lock"
if grep -Eq '^(psql|pg_dump|dropdb|createdb|pg_restore) ' "$log"; then
  fail "removed write fence touched database commands"
fi
printf 'IEUM_PRODUCTION_WRITE_FENCE=enabled\n' > "$fence"
chmod 600 "$fence"

insecure_lock_dir="$tmp/insecure-locks"
mkdir "$insecure_lock_dir"
chmod 755 "$insecure_lock_dir"
: > "$log"
if IEUM_DEPLOY_LOCK_PATH="$insecure_lock_dir/deploy.lock" "$helper" restore \
  --admin-service ieum_target_admin \
  --dump "$dump" \
  --sha256 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --evidence-dir "$evidence" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "restore accepted an insecure deployment lock directory"
fi

grep -F 'deployment lock directory must have mode 0700' "$tmp/stderr" >/dev/null || fail "insecure deployment lock directory was not rejected"
if grep -Eq '^(psql|pg_dump|dropdb|createdb|pg_restore) ' "$log"; then
  fail "insecure deployment lock directory touched database commands"
fi

: > "$log"
touch "$FAKE_DB_MARKER"
if FAKE_SHA256=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb "$helper" restore \
  --admin-service ieum_target_admin \
  --dump "$dump" \
  --sha256 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --evidence-dir "$evidence" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "restore accepted a source dump with the wrong checksum"
fi

grep -F 'dump checksum does not match' "$tmp/stderr" >/dev/null || fail "wrong source checksum was not rejected"
if grep -Eq '^(pg_dump|dropdb|createdb|pg_restore) ' "$log"; then
  fail "wrong source checksum ran backup or destructive database commands"
fi

: > "$log"
rm -f "$FAKE_DB_MARKER"
if "$helper" restore \
  --admin-service ieum_target_admin \
  --dump "$dump" \
  --sha256 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --evidence-dir "$evidence" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "restore was accepted without an existing ieum target database"
fi

grep -F 'production target database is not present' "$tmp/stderr" >/dev/null || fail "missing target-database rejection"
grep -F 'psql ' "$log" >/dev/null || fail "target-database presence was not checked"
if grep -Eq '^(pg_dump|dropdb|createdb|pg_restore) ' "$log"; then
  fail "absent-target rejection ran a destructive or backup command"
fi

wrong_client="$tmp/pg_restore17"
printf '%s\n' '#!/usr/bin/env bash' 'printf "pg_restore (PostgreSQL) 17.6\\n"' >"$wrong_client"; chmod 755 "$wrong_client"
: >"$log"; touch "$FAKE_DB_MARKER"
if IEUM_SOURCE_PG_RESTORE_BIN="$wrong_client" "$helper" restore --admin-service ieum_target_admin --dump "$dump" --sha256 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa --evidence-dir "$evidence" >/dev/null 2>&1; then fail "wrong PostgreSQL client major was accepted"; fi
if grep -Eq '^(pg_dump|dropdb|createdb|pg_restore) ' "$log"; then fail "wrong PostgreSQL client ran database commands"; fi
writable_client="$tmp/pg_restore-writable"; cp "$bin/pg_restore" "$writable_client"; chmod 777 "$writable_client"
writable_client_physical="$(cd "${writable_client%/*}" && pwd -P)/${writable_client##*/}"
if IEUM_TEST_MODE=0 IEUM_SOURCE_PG_RESTORE_BIN="$writable_client" "$helper" restore --admin-service ieum_target_admin --dump "$dump" --sha256 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa --evidence-dir "$evidence" >/dev/null 2>&1; then fail "writable override client was accepted"; fi
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
printf '%s\n' "export PATH=\"$root_bin:/bin:/usr/bin\" IEUM_TEST_MODE=1 IEUM_PG_RESTORE_RUNTIME_DIR=\"$runtime_dir\"" 'restore_list="$(create_pg_restore_list production)"' 'printf "RESTORE_LIST=%s\n" "$restore_list"' >>"$root_probe"
if bash "$root_probe" >"$tmp/root-probe.out" 2>"$tmp/root-probe.err"; then fail "root simulation accepted writable PG18 client"; fi
grep -F 'writable by group or other' "$tmp/root-probe.err" >/dev/null || fail "root simulation rejected writable client for wrong reason"
if ! FAKE_STAT_MODE=755 bash "$root_probe" >"$tmp/root-probe-755.out" 2>"$tmp/root-probe-755.err"; then
  cat "$tmp/root-probe-755.err" >&2
  fail "root simulation rejected a root-owned mode 0755 PG18 client"
fi
grep -F "RESTORE_LIST=$runtime_dir/" "$tmp/root-probe-755.out" >/dev/null || fail "root restore list was not staged in the postgres-accessible runtime directory"

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
export FAKE_TOC="$toc"
export FAKE_CAPTURE_LIST="$capture"

: > "$log"
touch "$FAKE_DB_MARKER"
if ! "$helper" restore \
  --admin-service ieum_target_admin \
  --dump "$dump" \
  --sha256 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --evidence-dir "$evidence" >"$tmp/stdout" 2>"$tmp/stderr"; then
  cat "$tmp/stderr" >&2
  fail "valid production restore failed"
fi

grep -F 'createdb --no-password --maintenance-db=postgres --template=template0 --owner=ieum ieum' "$log" >/dev/null || fail "production database was not recreated as literal ieum"
grep -F 'dropdb --no-password --maintenance-db=postgres --if-exists ieum' "$log" >/dev/null || fail "production database was not dropped as literal ieum"
grep -F 'pg_dump ' "$log" >/dev/null || fail "existing ieum target was not backed up before restore"
! grep -F -- '--file=' "$log" >/dev/null || fail "root-owned target backup was passed as pg_dump --file"
grep -F -- '--format=custom' "$log" >/dev/null || fail "target backup is not custom format"
grep -F -- '--role=ieum' "$log" >/dev/null || fail "restore did not use the application role"
grep -F -- '--single-transaction' "$log" >/dev/null || fail "restore is not transactional"
grep -F -- '--dbname=ieum' "$log" >/dev/null || fail "restore did not use literal ieum"
[[ -e "$FAKE_DB_MARKER" ]] || fail "successful restore did not leave ieum present"
grep -F 'EXTENSION - pgcrypto_app' "$capture" >/dev/null || fail "similarly named application extension was filtered"
grep -F 'COMMENT - EXTENSION pgcrypto_app' "$capture" >/dev/null || fail "similarly named application extension comment was filtered"
if grep -E 'EXTENSION[[:space:]]+-[[:space:]]+(pgcrypto|postgis|vector)$' "$capture" >/dev/null; then
  fail "required extension entries were not filtered"
fi
if grep -E 'TABLE DATA[[:space:]]+public[[:space:]]+spatial_ref_sys([[:space:]]|$)' "$capture" >/dev/null; then
  fail "PostGIS-managed spatial_ref_sys data was not filtered"
fi

dump_line="$(grep -n '^pg_dump ' "$log" | head -n 1 | cut -d: -f1)"
drop_line="$(grep -n '^dropdb ' "$log" | head -n 1 | cut -d: -f1)"
create_line="$(grep -n '^createdb ' "$log" | head -n 1 | cut -d: -f1)"
restore_line="$(grep -n '^pg_restore .*--single-transaction' "$log" | head -n 1 | cut -d: -f1)"
[[ "$dump_line" -lt "$drop_line" && "$drop_line" -lt "$create_line" && "$create_line" -lt "$restore_line" ]] || fail "production restore order is unsafe"

: > "$log"
rm -f "$FAKE_RESTORE_FAIL_MARKER"
touch "$FAKE_DB_MARKER"
if FAKE_RESTORE_FAIL_ONCE=1 "$helper" restore \
  --admin-service ieum_target_admin \
  --dump "$dump" \
  --sha256 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --evidence-dir "$evidence" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "source restore failure unexpectedly succeeded"
fi

[[ -e "$FAKE_DB_MARKER" ]] || fail "source restore failure did not restore the previous target database"
[[ "$(grep -c '^dropdb ' "$log")" -eq 2 ]] || fail "source restore failure did not replace the failed target before rollback"
[[ "$(grep -c '^createdb ' "$log")" -eq 2 ]] || fail "source restore failure did not recreate the previous target database"
[[ "$(grep -c '^pg_restore .*--single-transaction' "$log")" -eq 2 ]] || fail "source restore failure did not restore the captured target backup"
[[ "$(grep -Ec '^pg_restore .* --use-list=[^[:space:]]+$' "$log")" -eq 2 ]] || fail "rollback did not stream the captured target backup"
! grep -E '^pg_restore .*([[:space:]]|^)-([[:space:]]|$)' "$log" >/dev/null || fail "rollback passed a literal dash instead of using stdin"

: > "$log"
rm -f "$FAKE_DROP_AFTER_REMOVE_FAIL_MARKER"
touch "$FAKE_DB_MARKER"
if FAKE_DROP_AFTER_REMOVE_FAIL_ONCE=1 "$helper" restore \
  --admin-service ieum_target_admin \
  --dump "$dump" \
  --sha256 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --evidence-dir "$evidence" >"$tmp/stdout" 2>"$tmp/stderr"; then
  fail "drop-after-remove failure unexpectedly succeeded"
fi

[[ -e "$FAKE_DB_MARKER" ]] || fail "drop-after-remove failure did not restore the previous target database"
[[ "$(grep -c '^dropdb ' "$log")" -eq 2 ]] || fail "drop-after-remove failure did not attempt target rollback"
grep -E '^pg_restore .* --use-list=[^[:space:]]+$' "$log" >/dev/null || fail "drop-after-remove rollback did not stream the captured target backup"
! grep -E '^pg_restore .*([[:space:]]|^)-([[:space:]]|$)' "$log" >/dev/null || fail "drop-after-remove rollback passed a literal dash instead of using stdin"

echo "db-restore-production test: PASS"
