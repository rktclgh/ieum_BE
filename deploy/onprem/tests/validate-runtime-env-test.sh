#!/usr/bin/env bash
set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
VALIDATOR="$SCRIPT_DIR/../scripts/validate-runtime-env.sh"
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ieum-env-test.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT

pass=0
fail=0
assert_success() {
  if "$@" >/dev/null 2>"$TMP_DIR/stderr"; then pass=$((pass + 1)); else
    printf 'FAIL (expected success): %s\n' "$*" >&2; cat "$TMP_DIR/stderr" >&2; fail=$((fail + 1))
  fi
}
assert_failure() {
  if "$@" >/dev/null 2>"$TMP_DIR/stderr"; then
    printf 'FAIL (expected failure): %s\n' "$*" >&2; fail=$((fail + 1))
  else pass=$((pass + 1)); fi
}
replace_line() {
  local file=$1 key=$2 replacement=$3 tmp
  tmp="$file.replace.$$"
  awk -v key="$key" -v replacement="$replacement" '
    $0 ~ ("^" key "=") { print replacement; found=1; next }
    { print }
    END { exit(found ? 0 : 1) }
  ' "$file" > "$tmp" || { rm -f "$tmp"; return 1; }
  mv "$tmp" "$file"
}

cat >"$TMP_DIR/main.env" <<'EOF'
SERVER_PORT=8080
SERVER_FORWARD_HEADERS_STRATEGY=native
SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/ieum
REDIS_HOST=host.docker.internal
REDIS_PORT=6379
REDIS_DATABASE=1
REDIS_PASSWORD=redacted
CORS_ALLOWED_ORIGINS=https://ieum.rktclgh.site,https://ieum1.rktclgh.site
COOKIE_SECURE=true
WEB_PUSH_ENABLED=true
WEB_PUSH_VAPID_PUBLIC_KEY=fixture-public-key
WEB_PUSH_VAPID_PRIVATE_KEY=fixture-private-key
WEB_PUSH_VAPID_SUBJECT=mailto:ops@example.test
APP_AI_REPORT_ENABLED=true
APP_AI_REPORT_BASE_URL=http://app-ai:8081
APP_AI_REPORT_ALLOWED_HOSTS=app-ai
APP_AI_QUESTION_ANSWER_DISPATCH_ENABLED=true
APP_AI_QUESTION_ANSWER_DISPATCH_BASE_URL=http://app-ai:8081
APP_AI_QUESTION_ANSWER_DISPATCH_ALLOWED_HOSTS=app-ai
APP_AI_ACCEPTED_ANSWER_DISPATCH_ENABLED=true
APP_AI_ACCEPTED_ANSWER_DISPATCH_BASE_URL=http://app-ai:8081
APP_AI_ACCEPTED_ANSWER_DISPATCH_ALLOWED_HOSTS=app-ai
AWS_S3_BUCKET=ieum-files
AWS_S3_ENDPOINT=http://minio:9000
AWS_S3_PRESIGN_ENDPOINT=https://files.rktclgh.site
AWS_S3_PATH_STYLE_ACCESS_ENABLED=true
AWS_ACCESS_KEY_ID=fixture-access-key
AWS_SECRET_ACCESS_KEY=fixture-secret-key
AWS_S3_REGION=us-east-1
AWS_S3_API_CALL_TIMEOUT_SECONDS=10
AWS_S3_API_CALL_ATTEMPT_TIMEOUT_SECONDS=3
APP_AI_INTERNAL_CALLBACK_TOKEN=shared-token
EOF
cat >"$TMP_DIR/ai.env" <<'EOF'
SERVER_PORT=8081
SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/ieum
AWS_REGION=ap-northeast-2
AWS_ACCESS_KEY_ID=fixture-access-key
AWS_SECRET_ACCESS_KEY=fixture-secret-key
APP_AI_BEDROCK_REGION=ap-northeast-2
APP_AI_FEATURES_REPORT_REVIEW_ENABLED=true
APP_AI_FEATURES_QUESTION_ANSWER_ENABLED=true
APP_AI_FEATURES_ACCEPTED_ANSWER_INGESTION_ENABLED=true
APP_AI_GEMINI_API_KEY=fixture-gemini-key
APP_AI_REPORT_IMAGE_ALLOWED_HOSTS=files.rktclgh.site
APP_AI_QUESTION_CALLBACK_BASE_ORIGIN=http://app-main:8080
APP_AI_QUESTION_CALLBACK_ALLOWED_ORIGINS=http://app-main:8080
APP_AI_QUESTION_CALLBACK_CONNECT_TIMEOUT=2s
APP_AI_QUESTION_CALLBACK_READ_TIMEOUT=5s
APP_AI_INTERNAL_CALLBACK_TOKEN=shared-token
EOF

assert_success "$VALIDATOR" app-main "$TMP_DIR/main.env" "$TMP_DIR/ai.env"
assert_success "$VALIDATOR" app-ai "$TMP_DIR/ai.env" "$TMP_DIR/main.env"

cp "$TMP_DIR/main.env" "$TMP_DIR/missing-stage-cors.env"
replace_line "$TMP_DIR/missing-stage-cors.env" CORS_ALLOWED_ORIGINS CORS_ALLOWED_ORIGINS=https://ieum.rktclgh.site
assert_failure "$VALIDATOR" app-main "$TMP_DIR/missing-stage-cors.env"

cp "$TMP_DIR/main.env" "$TMP_DIR/blank-vapid.env"
replace_line "$TMP_DIR/blank-vapid.env" WEB_PUSH_VAPID_PRIVATE_KEY WEB_PUSH_VAPID_PRIVATE_KEY=
assert_failure "$VALIDATOR" app-main "$TMP_DIR/blank-vapid.env"

cp "$TMP_DIR/main.env" "$TMP_DIR/blank-redis-password.env"
replace_line "$TMP_DIR/blank-redis-password.env" REDIS_PASSWORD REDIS_PASSWORD=
assert_failure "$VALIDATOR" app-main "$TMP_DIR/blank-redis-password.env"

cp "$TMP_DIR/main.env" "$TMP_DIR/unisolated-redis-database.env"
replace_line "$TMP_DIR/unisolated-redis-database.env" REDIS_DATABASE REDIS_DATABASE=0
assert_failure "$VALIDATOR" app-main "$TMP_DIR/unisolated-redis-database.env"

cp "$TMP_DIR/main.env" "$TMP_DIR/blank-s3-key.env"
replace_line "$TMP_DIR/blank-s3-key.env" AWS_ACCESS_KEY_ID AWS_ACCESS_KEY_ID=
assert_failure "$VALIDATOR" app-main "$TMP_DIR/blank-s3-key.env"

cp "$TMP_DIR/main.env" "$TMP_DIR/blank-s3-secret.env"
replace_line "$TMP_DIR/blank-s3-secret.env" AWS_SECRET_ACCESS_KEY AWS_SECRET_ACCESS_KEY=
assert_failure "$VALIDATOR" app-main "$TMP_DIR/blank-s3-secret.env"

cp "$TMP_DIR/main.env" "$TMP_DIR/unsupported-session-token.env"
printf 'AWS_SESSION_TOKEN=unsupported-session-token\n' >> "$TMP_DIR/unsupported-session-token.env"
assert_failure "$VALIDATOR" app-main "$TMP_DIR/unsupported-session-token.env"

cp "$TMP_DIR/main.env" "$TMP_DIR/report-disabled.env"
replace_line "$TMP_DIR/report-disabled.env" APP_AI_REPORT_ENABLED APP_AI_REPORT_ENABLED=false
assert_failure "$VALIDATOR" app-main "$TMP_DIR/report-disabled.env"

cp "$TMP_DIR/ai.env" "$TMP_DIR/blank-gemini.env"
replace_line "$TMP_DIR/blank-gemini.env" APP_AI_GEMINI_API_KEY APP_AI_GEMINI_API_KEY=
assert_failure "$VALIDATOR" app-ai "$TMP_DIR/blank-gemini.env"

cp "$TMP_DIR/ai.env" "$TMP_DIR/question-disabled.env"
replace_line "$TMP_DIR/question-disabled.env" APP_AI_FEATURES_QUESTION_ANSWER_ENABLED APP_AI_FEATURES_QUESTION_ANSWER_ENABLED=false
assert_failure "$VALIDATOR" app-ai "$TMP_DIR/question-disabled.env"

cp "$TMP_DIR/ai.env" "$TMP_DIR/blank-bedrock-key.env"
replace_line "$TMP_DIR/blank-bedrock-key.env" AWS_ACCESS_KEY_ID AWS_ACCESS_KEY_ID=
assert_failure "$VALIDATOR" app-ai "$TMP_DIR/blank-bedrock-key.env"

cp "$TMP_DIR/ai.env" "$TMP_DIR/blank-bedrock-secret.env"
replace_line "$TMP_DIR/blank-bedrock-secret.env" AWS_SECRET_ACCESS_KEY AWS_SECRET_ACCESS_KEY=
assert_failure "$VALIDATOR" app-ai "$TMP_DIR/blank-bedrock-secret.env"

cp "$TMP_DIR/main.env" "$TMP_DIR/bad.env"
replace_line "$TMP_DIR/bad.env" AWS_S3_PRESIGN_ENDPOINT AWS_S3_PRESIGN_ENDPOINT=http://files.rktclgh.site
assert_failure "$VALIDATOR" app-main "$TMP_DIR/bad.env"

cp "$TMP_DIR/main.env" "$TMP_DIR/blank-region.env"
replace_line "$TMP_DIR/blank-region.env" AWS_S3_REGION AWS_S3_REGION=
assert_failure "$VALIDATOR" app-main "$TMP_DIR/blank-region.env"

cp "$TMP_DIR/main.env" "$TMP_DIR/blank-presign.env"
replace_line "$TMP_DIR/blank-presign.env" AWS_S3_PRESIGN_ENDPOINT AWS_S3_PRESIGN_ENDPOINT=
assert_failure "$VALIDATOR" app-main "$TMP_DIR/blank-presign.env"

cp "$TMP_DIR/main.env" "$TMP_DIR/unsafe.env"
replace_line "$TMP_DIR/unsafe.env" SPRING_DATASOURCE_URL SPRING_DATASOURCE_URL=jdbc:postgresql://prod.abc.rds.amazonaws.com:5432/ieum
assert_failure "$VALIDATOR" app-main "$TMP_DIR/unsafe.env"

cp "$TMP_DIR/main.env" "$TMP_DIR/unsafe-uri.env"
printf 'UPSTREAM_DOMAIN=172.31.10.4\n' >> "$TMP_DIR/unsafe-uri.env"
assert_failure "$VALIDATOR" app-main "$TMP_DIR/unsafe-uri.env"

cp "$TMP_DIR/ai.env" "$TMP_DIR/unsafe-ai.env"
replace_line "$TMP_DIR/unsafe-ai.env" APP_AI_QUESTION_CALLBACK_BASE_ORIGIN APP_AI_QUESTION_CALLBACK_BASE_ORIGIN=http://172.31.10.4:8080
assert_failure "$VALIDATOR" app-ai "$TMP_DIR/unsafe-ai.env"

cp "$TMP_DIR/ai.env" "$TMP_DIR/unsafe-sibling.env"
replace_line "$TMP_DIR/unsafe-sibling.env" SPRING_DATASOURCE_URL SPRING_DATASOURCE_URL=jdbc:postgresql://172.31.10.4:5432/ieum
assert_failure "$VALIDATOR" app-main "$TMP_DIR/main.env" "$TMP_DIR/unsafe-sibling.env"

cp "$TMP_DIR/main.env" "$TMP_DIR/token-substring.env"
replace_line "$TMP_DIR/token-substring.env" APP_AI_INTERNAL_CALLBACK_TOKEN APP_AI_INTERNAL_CALLBACK_TOKEN=token-rds.amazonaws.com-172.31-safe
cp "$TMP_DIR/token-substring.env" "$TMP_DIR/token-substring-sibling.env"
assert_success "$VALIDATOR" app-main "$TMP_DIR/token-substring.env" "$TMP_DIR/token-substring-sibling.env"

cp "$TMP_DIR/main.env" "$TMP_DIR/malformed.env"
printf 'not-an-env-entry\n' >> "$TMP_DIR/malformed.env"
assert_failure "$VALIDATOR" app-main "$TMP_DIR/malformed.env"

cp "$TMP_DIR/main.env" "$TMP_DIR/duplicate.env"
printf 'REDIS_PORT=6379\n' >> "$TMP_DIR/duplicate.env"
assert_failure "$VALIDATOR" app-main "$TMP_DIR/duplicate.env"

cp "$TMP_DIR/ai.env" "$TMP_DIR/bad-ai.env"
replace_line "$TMP_DIR/bad-ai.env" APP_AI_REPORT_IMAGE_ALLOWED_HOSTS APP_AI_REPORT_IMAGE_ALLOWED_HOSTS=localhost
assert_failure "$VALIDATOR" app-ai "$TMP_DIR/bad-ai.env"

cp "$TMP_DIR/ai.env" "$TMP_DIR/app-ai-with-s3.env"
printf 'AWS_S3_ENDPOINT=http://minio:9000\n' >> "$TMP_DIR/app-ai-with-s3.env"
assert_failure "$VALIDATOR" app-ai "$TMP_DIR/app-ai-with-s3.env"

cp "$TMP_DIR/main.env" "$TMP_DIR/mismatch.env"
replace_line "$TMP_DIR/mismatch.env" APP_AI_INTERNAL_CALLBACK_TOKEN APP_AI_INTERNAL_CALLBACK_TOKEN=different-token
assert_failure "$VALIDATOR" app-main "$TMP_DIR/main.env" "$TMP_DIR/mismatch.env"

output=$({ "$VALIDATOR" app-main "$TMP_DIR/bad.env"; } 2>&1 || true)
if printf '%s' "$output" | grep -Eq 'redacted|shared-token|different-token'; then
  printf 'FAIL (secret leaked in validator output)\n' >&2; fail=$((fail + 1))
else pass=$((pass + 1)); fi

printf 'passed=%d failed=%d\n' "$pass" "$fail"
test "$fail" -eq 0
