#!/usr/bin/env bash
set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROVISIONER="$SCRIPT_DIR/../scripts/provision-runtime-env.sh"
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ieum-runtime-provision-test.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT

pass=0
fail=0

# Root installs the validator under the public helper name, not beside the
# provisioner source filename.  Keep that production invocation contract
# explicit so a root bootstrap cannot render env files with a missing validator.
if grep -Fq "validator='/usr/local/sbin/ieum-validate-runtime-env'" "$PROVISIONER"; then
  pass=$((pass + 1))
else
  printf 'FAIL (production validator default is not the installed helper)\n' >&2
  fail=$((fail + 1))
fi

assert_success() {
  if "$@" >"$TMP_DIR/stdout" 2>"$TMP_DIR/stderr"; then pass=$((pass + 1)); else
    printf 'FAIL (expected success): %s\n' "$*" >&2
    cat "$TMP_DIR/stderr" >&2
    fail=$((fail + 1))
  fi
}
assert_failure() {
  if "$@" >"$TMP_DIR/stdout" 2>"$TMP_DIR/stderr"; then
    printf 'FAIL (expected failure): %s\n' "$*" >&2
    fail=$((fail + 1))
  else pass=$((pass + 1)); fi
}
write_private() {
  local path=$1
  shift
  printf '%s\n' "$@" > "$path"
  chmod 600 "$path"
}
run_provisioner() {
  IEUM_PROVISION_RUNTIME_ENV_TEST_MODE=1 \
  IEUM_PROVISION_RUNTIME_ENV_EXPECTED_OWNER="$(id -un)" \
  "$PROVISIONER" \
    --app-main-base "$TMP_DIR/app-main.env.deploy" \
    --app-ai-base "$TMP_DIR/app-ai.env.deploy" \
    --app-main-overlay "$TMP_DIR/app-main.overlay" \
    --app-ai-overlay "$TMP_DIR/app-ai.overlay" \
    --db-overlay "$TMP_DIR/db.overlay" \
    --output-dir "$TMP_DIR/output" \
    --validator "$TMP_DIR/fake-validator.sh"
}

write_private "$TMP_DIR/app-main.env.deploy" \
  '# base values are non-secret defaults' \
  'SERVER_PORT=8080' \
  'SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/ieum' \
  'SPRING_DATASOURCE_USERNAME=old-user' \
  'SPRING_DATASOURCE_PASSWORD=old-password' \
  'AWS_ACCESS_KEY_ID=old-access' \
  'AWS_SECRET_ACCESS_KEY=old-secret' \
  'REDIS_PASSWORD=old-redis' \
  'APP_AI_INTERNAL_CALLBACK_TOKEN=shared-token'
write_private "$TMP_DIR/app-ai.env.deploy" \
  'SERVER_PORT=8081' \
  'SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/ieum' \
  'SPRING_DATASOURCE_USERNAME=old-user' \
  'SPRING_DATASOURCE_PASSWORD=old-password' \
  'AWS_ACCESS_KEY_ID=old-access' \
  'AWS_SECRET_ACCESS_KEY=old-secret' \
  'APP_AI_GEMINI_API_KEY=fixture-gemini-key' \
  'APP_AI_INTERNAL_CALLBACK_TOKEN=shared-token'
write_private "$TMP_DIR/app-main.overlay" \
  'AWS_ACCESS_KEY_ID=main-access-fixture' \
  'AWS_SECRET_ACCESS_KEY=main-secret-fixture' \
  'REDIS_PASSWORD=redis-fixture'
write_private "$TMP_DIR/app-ai.overlay" \
  'AWS_ACCESS_KEY_ID=ai-access-fixture' \
  'AWS_SECRET_ACCESS_KEY=ai-secret-fixture'
write_private "$TMP_DIR/db.overlay" \
  'SPRING_DATASOURCE_USERNAME=ieum' \
  'SPRING_DATASOURCE_PASSWORD=database-fixture'
cat > "$TMP_DIR/fake-validator.sh" <<'EOF'
#!/usr/bin/env bash
set -u
printf '%s\n' "$1" >> "${IEUM_FAKE_VALIDATOR_CALLS:?}"
test "$#" -eq 3
test -f "$2" && test -f "$3"
EOF
chmod 700 "$TMP_DIR/fake-validator.sh"
export IEUM_FAKE_VALIDATOR_CALLS="$TMP_DIR/validator.calls"

assert_success run_provisioner
test -f "$TMP_DIR/output/app-main.env" || { printf 'FAIL (missing app-main output)\n' >&2; fail=$((fail + 1)); }
test -f "$TMP_DIR/output/app-ai.env" || { printf 'FAIL (missing app-ai output)\n' >&2; fail=$((fail + 1)); }
test "$(stat -f '%Lp' "$TMP_DIR/output/app-main.env" 2>/dev/null || stat -c '%a' "$TMP_DIR/output/app-main.env")" = 600 || fail=$((fail + 1))
test "$(stat -f '%Lp' "$TMP_DIR/output/app-ai.env" 2>/dev/null || stat -c '%a' "$TMP_DIR/output/app-ai.env")" = 600 || fail=$((fail + 1))
grep -Fqx 'SPRING_DATASOURCE_USERNAME=ieum' "$TMP_DIR/output/app-main.env" || fail=$((fail + 1))
grep -Fqx 'SPRING_DATASOURCE_PASSWORD=database-fixture' "$TMP_DIR/output/app-main.env" || fail=$((fail + 1))
grep -Fqx 'AWS_ACCESS_KEY_ID=main-access-fixture' "$TMP_DIR/output/app-main.env" || fail=$((fail + 1))
grep -Fqx 'AWS_SECRET_ACCESS_KEY=main-secret-fixture' "$TMP_DIR/output/app-main.env" || fail=$((fail + 1))
grep -Fqx 'REDIS_PASSWORD=redis-fixture' "$TMP_DIR/output/app-main.env" || fail=$((fail + 1))
grep -Fqx 'SPRING_DATASOURCE_USERNAME=ieum' "$TMP_DIR/output/app-ai.env" || fail=$((fail + 1))
grep -Fqx 'SPRING_DATASOURCE_PASSWORD=database-fixture' "$TMP_DIR/output/app-ai.env" || fail=$((fail + 1))
grep -Fqx 'AWS_ACCESS_KEY_ID=ai-access-fixture' "$TMP_DIR/output/app-ai.env" || fail=$((fail + 1))
grep -Fqx 'AWS_SECRET_ACCESS_KEY=ai-secret-fixture' "$TMP_DIR/output/app-ai.env" || fail=$((fail + 1))
test "$(grep -c '^SPRING_DATASOURCE_PASSWORD=' "$TMP_DIR/output/app-main.env")" -eq 1 || fail=$((fail + 1))
test "$(wc -l < "$TMP_DIR/validator.calls" | tr -d ' ')" -eq 2 || fail=$((fail + 1))
if grep -Eq 'main-secret-fixture|ai-secret-fixture|database-fixture|redis-fixture' "$TMP_DIR/stdout" "$TMP_DIR/stderr"; then
  printf 'FAIL (secret leaked by provisioner)\n' >&2
  fail=$((fail + 1))
else pass=$((pass + 1)); fi

cp "$TMP_DIR/app-main.env.deploy" "$TMP_DIR/malformed-base"
printf 'not-an-env-entry\n' >> "$TMP_DIR/malformed-base"
chmod 600 "$TMP_DIR/malformed-base"
assert_failure env IEUM_PROVISION_RUNTIME_ENV_TEST_MODE=1 IEUM_PROVISION_RUNTIME_ENV_EXPECTED_OWNER="$(id -un)" \
  "$PROVISIONER" --app-main-base "$TMP_DIR/malformed-base" --app-ai-base "$TMP_DIR/app-ai.env.deploy" \
  --app-main-overlay "$TMP_DIR/app-main.overlay" --app-ai-overlay "$TMP_DIR/app-ai.overlay" --db-overlay "$TMP_DIR/db.overlay" \
  --output-dir "$TMP_DIR/output-malformed" --validator "$TMP_DIR/fake-validator.sh"

write_private "$TMP_DIR/duplicate-overlay" \
  'AWS_ACCESS_KEY_ID=first' \
  'AWS_ACCESS_KEY_ID=second' \
  'AWS_SECRET_ACCESS_KEY=secret' \
  'REDIS_PASSWORD=redis'
assert_failure env IEUM_PROVISION_RUNTIME_ENV_TEST_MODE=1 IEUM_PROVISION_RUNTIME_ENV_EXPECTED_OWNER="$(id -un)" \
  "$PROVISIONER" --app-main-base "$TMP_DIR/app-main.env.deploy" --app-ai-base "$TMP_DIR/app-ai.env.deploy" \
  --app-main-overlay "$TMP_DIR/duplicate-overlay" --app-ai-overlay "$TMP_DIR/app-ai.overlay" --db-overlay "$TMP_DIR/db.overlay" \
  --output-dir "$TMP_DIR/output-duplicate" --validator "$TMP_DIR/fake-validator.sh"

ln -s "$TMP_DIR/app-main.overlay" "$TMP_DIR/symlink-overlay"
assert_failure env IEUM_PROVISION_RUNTIME_ENV_TEST_MODE=1 IEUM_PROVISION_RUNTIME_ENV_EXPECTED_OWNER="$(id -un)" \
  "$PROVISIONER" --app-main-base "$TMP_DIR/app-main.env.deploy" --app-ai-base "$TMP_DIR/app-ai.env.deploy" \
  --app-main-overlay "$TMP_DIR/symlink-overlay" --app-ai-overlay "$TMP_DIR/app-ai.overlay" --db-overlay "$TMP_DIR/db.overlay" \
  --output-dir "$TMP_DIR/output-symlink" --validator "$TMP_DIR/fake-validator.sh"

cp "$TMP_DIR/app-main.overlay" "$TMP_DIR/world-readable-overlay"
chmod 644 "$TMP_DIR/world-readable-overlay"
assert_failure env IEUM_PROVISION_RUNTIME_ENV_TEST_MODE=1 IEUM_PROVISION_RUNTIME_ENV_EXPECTED_OWNER="$(id -un)" \
  "$PROVISIONER" --app-main-base "$TMP_DIR/app-main.env.deploy" --app-ai-base "$TMP_DIR/app-ai.env.deploy" \
  --app-main-overlay "$TMP_DIR/world-readable-overlay" --app-ai-overlay "$TMP_DIR/app-ai.overlay" --db-overlay "$TMP_DIR/db.overlay" \
  --output-dir "$TMP_DIR/output-mode" --validator "$TMP_DIR/fake-validator.sh"

write_private "$TMP_DIR/unsupported-db" \
  'SPRING_DATASOURCE_USERNAME=ieum' \
  'SPRING_DATASOURCE_PASSWORD=database' \
  'UNSUPPORTED=value'
assert_failure env IEUM_PROVISION_RUNTIME_ENV_TEST_MODE=1 IEUM_PROVISION_RUNTIME_ENV_EXPECTED_OWNER="$(id -un)" \
  "$PROVISIONER" --app-main-base "$TMP_DIR/app-main.env.deploy" --app-ai-base "$TMP_DIR/app-ai.env.deploy" \
  --app-main-overlay "$TMP_DIR/app-main.overlay" --app-ai-overlay "$TMP_DIR/app-ai.overlay" --db-overlay "$TMP_DIR/unsupported-db" \
  --output-dir "$TMP_DIR/output-unsupported" --validator "$TMP_DIR/fake-validator.sh"

printf 'passed=%d failed=%d\n' "$pass" "$fail"
test "$fail" -eq 0
