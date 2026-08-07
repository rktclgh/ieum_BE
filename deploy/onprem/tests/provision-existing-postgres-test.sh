#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
script="$root/deploy/onprem/scripts/provision-existing-postgres.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail() { printf '%s\n' "provision existing postgres test failed: $*" >&2; exit 1; }
[[ -x "$script" ]] || fail "script missing or not executable"
if "$script" >/dev/null 2>&1; then fail "non-root invocation was accepted"; fi

test_script="$tmp/script"; cp "$script" "$test_script"
sed -i.bak 's/  \[\[ "\$EUID" -eq 0 \]\] || die "must run as root"/  : # test harness bypasses the already-tested root gate/' "$test_script"
rm -f "$test_script.bak"; chmod 755 "$test_script"
etc="$tmp/etc/ieum"; mkdir -p "$etc"; chmod 700 "$etc"
cred="$etc/postgres.app.env"
printf '%s\n' 'SPRING_DATASOURCE_USERNAME=ieum' 'SPRING_DATASOURCE_PASSWORD=deadbeef0123456789' >"$cred"; chmod 600 "$cred"
log="$tmp/calls.log"; : >"$log"; bin="$tmp/bin"; mkdir -p "$bin"
cat >"$bin/runuser" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'runuser argv=%s\n' "$*" >>"$FAKE_LOG"
[[ "$1" == -u && "$2" == postgres && "$3" == -- ]] || exit 9
shift 3
if [[ "$1" == env && "$2" == -i ]]; then
  shift 2
  args=("$@"); for i in "${!args[@]}"; do [[ "${args[$i]}" == PATH=* ]] && args[$i]="PATH=$FAKE_BIN:/bin:/usr/bin"; done
  exec env -i "FAKE_LOG=$FAKE_LOG" "${args[@]}"
fi
exec "$@"
EOF
cat >"$bin/psql" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'psql argv=%s\n' "$*" >>"$FAKE_LOG"
printf 'pg_env=%s,%s,%s,%s\n' "${PGHOST:-}" "${PGPORT:-}" "${PGSERVICE:-}" "${PGPASSWORD:-}" >>"$FAKE_LOG"
if [[ "$*" == *'--file='* && "$*" != *'--file=-'* ]]; then exit 13; fi
case "$*" in
  *server_version_num*) printf '170004\n' ;;
  *pg_available_extensions*) printf 'pgcrypto\npostgis\nvector\n' ;;
  *pg_roles*|*pg_database*) : ;;
  *) : ;;
esac
EOF
cat >"$bin/createdb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'createdb argv=%s\n' "$*" >>"$FAKE_LOG"
EOF
cat >"$bin/stat" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  *'%u'*) printf '0\n' ;;
  *'%a'*|*'%Lp'*)
    case "${@: -1}" in */ieum) printf '700\n' ;; *) printf '600\n' ;; esac ;;
  *) exec /usr/bin/stat "$@" ;;
esac
EOF
cat >"$bin/chown" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == root:root ]] || exit 9
shift
exit 0
EOF
chmod 755 "$bin"/*

run_valid() {
  PATH="$bin:$PATH" FAKE_LOG="$log" FAKE_BIN="$bin" PGHOST=hostile PGSERVICE=hostile PGSERVICEFILE=hostile PGPASSFILE=hostile PGPASSWORD=hostile PGUSER=hostile PGDATABASE=hostile PGPORT=9999 IEUM_ETC_DIR="$etc" IEUM_POSTGRES_CREDENTIALS_FILE="$cred" "$test_script" >"$tmp/out" 2>"$tmp/err"
}
run_valid || { cat "$tmp/err" >&2; fail "valid provisioning failed"; }
grep -Fx 'provision existing postgres: PASS' "$tmp/out" >/dev/null || fail "missing success marker"
! grep -F 'deadbeef' "$log" >/dev/null || fail "password leaked into command argv/log"
! grep -F 'deadbeef' "$tmp/out" >/dev/null || fail "password leaked into stdout"
! grep -F 'deadbeef' "$tmp/err" >/dev/null || fail "password leaked into stderr"
! grep -F 'pg_env=hostile' "$log" >/dev/null || fail "hostile PG environment reached provision commands"
grep -F 'pg_env=/var/run/postgresql,5432,,' "$log" >/dev/null || fail "clean PG environment was not forced"
pass_mode="$(stat -f '%Lp' "$etc/postgres.pgpass" 2>/dev/null || stat -c '%a' "$etc/postgres.pgpass")"
service_mode="$(stat -f '%Lp' "$etc/postgres.pg_service.conf" 2>/dev/null || stat -c '%a' "$etc/postgres.pg_service.conf")"
[[ "$pass_mode" == 600 && "$service_mode" == 600 ]] || fail "libpq files are not mode 0600"
grep -Fx '127.0.0.1:5432:*:ieum:deadbeef0123456789' "$etc/postgres.pgpass" >/dev/null || fail "pass file content incorrect"
for service_name in ieum_target_admin ieum_target ieum_target_rehearsal; do
  grep -Fx "[$service_name]" "$etc/postgres.pg_service.conf" >/dev/null || fail "missing service [$service_name]"
done
grep -A4 -Fx '[ieum_target]' "$etc/postgres.pg_service.conf" | grep -Fx 'dbname=ieum' >/dev/null || fail "target service database incorrect"
grep -A4 -Fx '[ieum_target_rehearsal]' "$etc/postgres.pg_service.conf" | grep -Fx 'dbname=ieum_rehearsal' >/dev/null || fail "rehearsal service database incorrect"
awk '/server_version_num|pg_available_extensions|pg_roles|pg_database/ { print NR }' "$log" >"$tmp/preflight-lines"
[[ "$(sed -n '1p' "$tmp/preflight-lines")" -lt "$(sed -n '2p' "$tmp/preflight-lines")" ]] || fail "version check did not precede extension check"
[[ "$(sed -n '2p' "$tmp/preflight-lines")" -lt "$(sed -n '3p' "$tmp/preflight-lines")" ]] || fail "extension check did not precede role check"

printf '%s\n' 'SPRING_DATASOURCE_USERNAME=ieum' 'SPRING_DATASOURCE_PASSWORD=not-hex' >"$cred"
if run_valid >/dev/null 2>&1; then fail "unsafe password was accepted"; fi
printf '%s\n' 'SPRING_DATASOURCE_USERNAME=other' 'SPRING_DATASOURCE_PASSWORD=deadbeef' >"$cred"
if run_valid >/dev/null 2>&1; then fail "unsafe username was accepted"; fi
rm -f "$cred"; ln -s "$etc/postgres.pg_service.conf" "$cred"
if run_valid >/dev/null 2>&1; then fail "symlink credentials were accepted"; fi

printf '%s\n' 'provision-existing-postgres test: PASS'
