#!/usr/bin/env bash
set -u

# Usage: validate-runtime-env.sh <app-main|app-ai> <env-file> [sibling-env-file]
# The optional sibling file enables a secret-safe equality check for the shared
# APP_AI_INTERNAL_CALLBACK_TOKEN. Values are parsed, never sourced or printed.

usage() {
  printf 'usage: %s <app-main|app-ai> <env-file> [sibling-env-file]\n' "$0" >&2
  exit 64
}

fail() {
  printf 'runtime environment validation failed: %s\n' "$1" >&2
  exit 1
}

[[ $# -ge 2 && $# -le 3 ]] || usage
service=$1
env_file=$2
sibling_file=${3-}
case "$service" in app-main|app-ai) ;; *) usage ;; esac
[[ -f "$env_file" && -r "$env_file" ]] || fail "${service} env file is unreadable"
if [[ -n "$sibling_file" && (! -f "$sibling_file" || ! -r "$sibling_file") ]]; then
  fail "sibling env file is unreadable"
fi

parse_env() {
  local file=$1 line line_number=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line_number=$((line_number + 1))
    line=${line%$'\r'}
    [[ -z "$line" || "$line" == \#* ]] && continue
    [[ "$line" =~ ^[A-Za-z_][A-Za-z0-9_]*=.*$ ]] || fail "malformed entry at line ${line_number}"
  done < "$file"
  awk -F= '
    /^[[:space:]]*(#|$)/ { next }
    !/^[A-Za-z_][A-Za-z0-9_]*=/ { exit 1 }
    { if (++seen[$1] > 1) exit 2 }
  ' "$file" || fail "malformed or duplicate runtime key"
}

parse_env "$env_file"

has_key() { awk -F= -v wanted="$1" '$1 == wanted { found=1 } END { exit(found ? 0 : 1) }' "$env_file"; }
value_of() { awk -F= -v wanted="$1" '$1 == wanted { value=substr($0, index($0, "=") + 1); found=1 } END { if (found) printf "%s", value }' "$env_file"; }
require_key() {
  local key=$1
  has_key "$key" || fail "missing key ${key}"
}
require_nonempty() {
  local key=$1 value
  require_key "$key"
  value=$(value_of "$key")
  [[ -n "$value" ]] || fail "blank key ${key}"
}
require_exact() {
  local key=$1 expected=$2 actual
  require_key "$key"
  actual=$(value_of "$key")
  [[ "$actual" == "$expected" ]] || fail "invalid value for ${key}"
}
contains_csv_token() {
  local csv=$1 wanted=$2 token
  IFS=',' read -r -a tokens <<< "$csv"
  for token in "${tokens[@]}"; do
    [[ "$token" == "$wanted" ]] && return 0
  done
  return 1
}

# Never allow an AWS private database/address in deployment host-like values.
# Do not scan arbitrary secrets: a token is opaque data, not a network address.
scan_unsafe_addresses() {
  local file=$1 line key value lower
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]] || continue
    key=${line%%=*}
    case "$key" in
      *_URL|*_URI|*_ORIGIN|*_DOMAIN|*_ADDRESS|*_IP|*_HOST|*_HOSTS|*_HOSTNAME|*_ENDPOINT|*_ALLOWED_ORIGINS) ;;
      *) continue ;;
    esac
    value=${line#*=}
    lower=$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')
    [[ "$lower" == *rds*.amazonaws.com* || "$lower" == *172.31.* ]] && fail "unsafe AWS private address in ${key}"
  done < "$file"
}

scan_unsafe_addresses "$env_file"

require_nonempty APP_AI_INTERNAL_CALLBACK_TOKEN

if [[ "$service" == app-main ]]; then
  require_exact SERVER_PORT 8080
  require_exact SERVER_FORWARD_HEADERS_STRATEGY native
  require_exact SPRING_DATASOURCE_URL jdbc:postgresql://host.docker.internal:5432/ieum
  require_exact REDIS_HOST host.docker.internal
  require_exact REDIS_PORT 6379
  require_exact REDIS_DATABASE 1
  require_nonempty REDIS_PASSWORD
  require_exact CORS_ALLOWED_ORIGINS https://ieum.rktclgh.site,https://ieum1.rktclgh.site
  require_exact COOKIE_SECURE true
  require_exact WEB_PUSH_ENABLED true
  require_nonempty WEB_PUSH_VAPID_PUBLIC_KEY
  require_nonempty WEB_PUSH_VAPID_PRIVATE_KEY
  require_nonempty WEB_PUSH_VAPID_SUBJECT
  require_exact APP_AI_REPORT_ENABLED true
  require_exact APP_AI_REPORT_BASE_URL http://app-ai:8081
  require_exact APP_AI_REPORT_ALLOWED_HOSTS app-ai
  require_exact APP_AI_QUESTION_ANSWER_DISPATCH_ENABLED true
  require_exact APP_AI_QUESTION_ANSWER_DISPATCH_BASE_URL http://app-ai:8081
  require_exact APP_AI_QUESTION_ANSWER_DISPATCH_ALLOWED_HOSTS app-ai
  require_exact APP_AI_ACCEPTED_ANSWER_DISPATCH_ENABLED true
  require_exact APP_AI_ACCEPTED_ANSWER_DISPATCH_BASE_URL http://app-ai:8081
  require_exact APP_AI_ACCEPTED_ANSWER_DISPATCH_ALLOWED_HOSTS app-ai
  require_exact AWS_S3_BUCKET ieum-files
  require_exact AWS_S3_ENDPOINT http://minio:9000
  require_exact AWS_S3_PATH_STYLE_ACCESS_ENABLED true
  require_exact AWS_S3_REGION us-east-1
  require_nonempty AWS_ACCESS_KEY_ID
  require_nonempty AWS_SECRET_ACCESS_KEY
  has_key AWS_SESSION_TOKEN && fail "unsupported key AWS_SESSION_TOKEN"
  require_exact AWS_S3_API_CALL_TIMEOUT_SECONDS 10
  require_exact AWS_S3_API_CALL_ATTEMPT_TIMEOUT_SECONDS 3
  require_nonempty AWS_S3_PRESIGN_ENDPOINT
  presign=$(value_of AWS_S3_PRESIGN_ENDPOINT)
  case "$presign" in
    https://files.rktclgh.site) ;;
    *) fail "AWS_S3_PRESIGN_ENDPOINT must be the public HTTPS file endpoint" ;;
  esac
else
  for forbidden_key in \
    AWS_S3_BUCKET AWS_S3_ENDPOINT AWS_S3_PRESIGN_ENDPOINT \
    AWS_S3_PATH_STYLE_ACCESS_ENABLED AWS_S3_REGION \
    APP_FILE_S3_TMP_PREFIX APP_FILE_S3_FINAL_PREFIX; do
    has_key "$forbidden_key" && fail "app-ai must not define ${forbidden_key}"
  done
  require_exact SERVER_PORT 8081
  require_exact SPRING_DATASOURCE_URL jdbc:postgresql://host.docker.internal:5432/ieum
  require_exact AWS_REGION ap-northeast-2
  require_nonempty AWS_ACCESS_KEY_ID
  require_nonempty AWS_SECRET_ACCESS_KEY
  require_exact APP_AI_BEDROCK_REGION ap-northeast-2
  require_exact APP_AI_FEATURES_REPORT_REVIEW_ENABLED true
  require_exact APP_AI_FEATURES_QUESTION_ANSWER_ENABLED true
  require_exact APP_AI_FEATURES_ACCEPTED_ANSWER_INGESTION_ENABLED true
  require_nonempty APP_AI_GEMINI_API_KEY
  require_nonempty APP_AI_REPORT_IMAGE_ALLOWED_HOSTS
  allowed_hosts=$(value_of APP_AI_REPORT_IMAGE_ALLOWED_HOSTS)
  contains_csv_token "$allowed_hosts" files.rktclgh.site || fail "app-ai image host allowlist is missing files.rktclgh.site"
  require_exact APP_AI_QUESTION_CALLBACK_BASE_ORIGIN http://app-main:8080
  require_exact APP_AI_QUESTION_CALLBACK_ALLOWED_ORIGINS http://app-main:8080
  require_exact APP_AI_QUESTION_CALLBACK_CONNECT_TIMEOUT 2s
  require_exact APP_AI_QUESTION_CALLBACK_READ_TIMEOUT 5s
fi

if [[ -n "$sibling_file" ]]; then
  awk -F= '
    /^[[:space:]]*(#|$)/ { next }
    !/^[A-Za-z_][A-Za-z0-9_]*=/ { exit 1 }
    { if (++seen[$1] > 1) exit 2 }
  ' "$sibling_file" || fail "malformed or duplicate sibling runtime key"
  scan_unsafe_addresses "$sibling_file"
  sibling_token=$(awk -F= '$1 == "APP_AI_INTERNAL_CALLBACK_TOKEN" { value=substr($0, index($0, "=") + 1); found=1 } END { if (found) printf "%s", value }' "$sibling_file")
  [[ -n "$sibling_token" ]] || fail "missing sibling callback token"
  callback_token=$(value_of APP_AI_INTERNAL_CALLBACK_TOKEN)
  [[ "$callback_token" == "$sibling_token" ]] || fail "callback tokens do not match"
fi

printf '%s runtime environment validated\n' "$service"
