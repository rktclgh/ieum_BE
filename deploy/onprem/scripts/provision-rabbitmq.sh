#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# Provisions the RabbitMQ broker for AI job dispatch
# (docs/rabbitmq-dispatch/spec.md §11.2/§11.3/§10.1).
#
# This script only ever talks to the separate `ieum-broker` compose project
# and, through it, the RabbitMQ container's own `rabbitmqctl`. It never
# touches the `ieum` release compose project or any app-main/app-ai
# container. It is idempotent: running it again on an already-provisioned
# broker reapplies permissions/tags and generates no new secrets.

die() { printf 'ieum provision rabbitmq: %s\n' "$1" >&2; exit 2; }

test_mode=false
if [[ "$EUID" -eq 0 ]]; then
  PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'; export PATH
  ETC_DIR='/etc/ieum'
  COMPOSE_FILE='/srv/ieum/broker/compose.yml'
  BROKER_ENV_FILE="$ETC_DIR/rabbitmq.env"
  CREDENTIALS_FILE="$ETC_DIR/rabbitmq-app-credentials.env"
  DOCKER_BIN='/usr/bin/docker'
  OPENSSL_BIN='/usr/bin/openssl'
  EXPECTED_OWNER=0
else
  [[ "${IEUM_PROVISION_RABBITMQ_TEST_MODE:-}" == 1 ]] || die 'must run as root'
  test_mode=true
  ETC_DIR=${IEUM_PROVISION_RABBITMQ_ETC_DIR:-}
  COMPOSE_FILE=${IEUM_PROVISION_RABBITMQ_COMPOSE_FILE:-}
  BROKER_ENV_FILE=${IEUM_PROVISION_RABBITMQ_BROKER_ENV_FILE:-}
  CREDENTIALS_FILE=${IEUM_PROVISION_RABBITMQ_CREDENTIALS_FILE:-}
  DOCKER_BIN=${IEUM_PROVISION_RABBITMQ_DOCKER_BIN:-/usr/bin/docker}
  OPENSSL_BIN=${IEUM_PROVISION_RABBITMQ_OPENSSL_BIN:-/usr/bin/openssl}
  EXPECTED_OWNER=${IEUM_PROVISION_RABBITMQ_EXPECTED_OWNER:-$(id -u)}
  [[ "$ETC_DIR" = /* && "$COMPOSE_FILE" = /* && "$BROKER_ENV_FILE" = /* && "$CREDENTIALS_FILE" = /* && "$DOCKER_BIN" = /* && "$OPENSSL_BIN" = /* ]] \
    || die 'test paths must be absolute'
fi

readonly PROJECT_NAME='ieum-broker'
readonly VHOST='/ieum'

[[ -x "$DOCKER_BIN" ]] || die 'docker executable is missing'
[[ -x "$OPENSSL_BIN" ]] || die 'openssl executable is missing'
[[ -f "$COMPOSE_FILE" && ! -L "$COMPOSE_FILE" ]] || die 'compose file is missing or unsafe'

mode_of() { stat -c '%a' -- "$1" 2>/dev/null || stat -f '%Lp' -- "$1"; }
owner_of() { stat -c '%u' -- "$1" 2>/dev/null || stat -f '%u' -- "$1"; }
require_private_root_file() {
  local path=$1 label=$2
  [[ -f "$path" && ! -L "$path" ]] || die "$label must be a regular non-symlink file"
  [[ "$(owner_of "$path")" == "$EXPECTED_OWNER" ]] || die "$label must be owned by root"
  [[ "$(mode_of "$path")" == 600 ]] || die "$label must have mode 0600"
}
require_private_root_file "$BROKER_ENV_FILE" 'broker env file'

# Fail closed before ever calling `compose up`: a blank or still-placeholder
# admin/image value here means the broker would boot with guest-equivalent
# credentials or fail to pull an image, and every check below this point
# assumes a real broker is actually running. Never echo the value itself.
value_of_env() {
  local file=$1 key=$2
  awk -F= -v k="$key" '
    /^[[:space:]]*(#|$)/ { next }
    $1 == k { value = substr($0, index($0, "=") + 1); found = 1 }
    END { if (found) printf "%s", value }
  ' "$file"
}
validate_broker_env_file() {
  local file=$1 key value
  for key in RABBITMQ_DEFAULT_USER RABBITMQ_DEFAULT_PASS RABBITMQ_IMAGE_DIGEST; do
    value=$(value_of_env "$file" "$key")
    [[ -n "$value" ]] || die "broker env file is missing or blank ${key}"
    case "$value" in
      *CHANGE_ME*|*'<'*) die "broker env file has a placeholder value for ${key}" ;;
    esac
  done
}
validate_broker_env_file "$BROKER_ENV_FILE"

compose() {
  "$DOCKER_BIN" compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE" --env-file "$BROKER_ENV_FILE" "$@"
}
container_id() {
  compose ps -q rabbitmq
}
exec_rmq() {
  local cid
  cid=$(container_id)
  [[ -n "$cid" ]] || die 'rabbitmq container is not running'
  "$DOCKER_BIN" exec "$cid" rabbitmqctl "$@"
}
wait_healthy() {
  local cid attempt status
  cid=$(container_id)
  [[ -n "$cid" ]] || die 'rabbitmq container did not start'
  for ((attempt = 1; attempt <= 30; attempt++)); do
    status=$("$DOCKER_BIN" inspect --format '{{.State.Health.Status}}' "$cid" 2>/dev/null || printf 'unknown')
    [[ "$status" == healthy ]] && return 0
    sleep 1
  done
  die 'rabbitmq container did not become healthy'
}

generate_password() {
  "$OPENSSL_BIN" rand -hex 32
}

# Whether `user` already exists on the broker, probed via `list_users`
# (tab-separated `name\ttags` lines) rather than inferred from add_user's
# exit status.
user_exists() {
  local user=$1 listing
  listing=$(exec_rmq list_users --quiet) || die "unable to list rabbitmq users"
  printf '%s\n' "$listing" | awk -F'\t' -v u="$user" '$1 == u { found = 1 } END { exit !found }'
}

# Persists exactly one user's generated password to CREDENTIALS_FILE,
# immediately after that user's `add_user` succeeds (finding 7). Any
# existing content — including a previous run's entries for OTHER users
# that were persisted the same way — is preserved; only a stale line for
# THIS SAME key (should never happen, since a user is only ever created
# once) would be replaced. Written via a temp file + atomic `mv`, mode 0600
# throughout, so a reader never observes a partially-written file.
#
# This must happen right after `add_user`, not batched at the end of
# main() — if a later broker call (set_permissions/set_user_tags for this
# user, or anything for the next user) fails and the script dies, a
# batched writer never runs and the freshly generated password — set on
# the broker by add_user but never written anywhere else — is lost
# forever. A retry then sees the user already exists (user_exists) and
# never re-generates or re-records it, permanently locking the app out.
persist_credential() {
  local user=$1 password=$2 key user_upper stage
  user_upper=$(printf '%s' "$user" | tr '[:lower:]' '[:upper:]')
  key="${user_upper}_RABBITMQ_PASSWORD"
  stage="$CREDENTIALS_FILE.$$.tmp"
  {
    if [[ -f "$CREDENTIALS_FILE" ]]; then
      grep -v "^${key}=" "$CREDENTIALS_FILE" || true
    else
      printf '# Generated by provision-rabbitmq.sh — copy each RABBITMQ_PASSWORD value into\n'
      printf '# the matching /etc/ieum/app-main.env or /etc/ieum/app-ai.env, then delete\n'
      printf '# or re-secure this file. Never commit it.\n'
    fi
    printf '%s=%s\n' "$key" "$password"
  } >"$stage"
  chmod 600 "$stage"
  mv -f "$stage" "$CREDENTIALS_FILE"
  chmod 600 "$CREDENTIALS_FILE"
}

# A password is generated and set exactly once per account, at creation
# time. Re-running this script against an account that already exists must
# never call `change_password` — doing so with a *freshly generated*
# password (the previous bug) silently rotates the broker-side credential
# to a value nobody records, locking the app out on its next reconnect.
# `list_users` is queried first so "exists" is never inferred from
# add_user's exit status.
ensure_user() {
  local user=$1
  if user_exists "$user"; then
    printf 'ieum provision rabbitmq: account %s exists, unchanged\n' "$user" >&2
    return 0
  fi
  local password
  password=$(generate_password)
  exec_rmq add_user "$user" "$password" >/dev/null || die "unable to create rabbitmq account ${user}"
  persist_credential "$user" "$password"
}
ensure_permissions() {
  local user=$1 configure=$2 write=$3 read=$4
  exec_rmq set_permissions -p "$VHOST" "$user" "$configure" "$write" "$read" >/dev/null
}
# No arguments after the username clears every tag, so neither app account
# can ever hold the `management`/`administrator` tag (spec.md §10.1).
ensure_no_tags() {
  local user=$1
  exec_rmq set_user_tags "$user" >/dev/null
}

main() {
  compose up -d >/dev/null
  wait_healthy

  # guest is loopback-only by RabbitMQ's own default, but loopback is real
  # inside the container, so it is removed unconditionally (spec.md §10.1).
  # Idempotent: `|| true` tolerates an already-deleted guest account.
  exec_rmq delete_user guest >/dev/null 2>&1 || true

  # RabbitMQ's `queue.bind` checks WRITE on the queue being bound and READ on
  # the exchange it is bound *from* (only `basic.publish` needs write on an
  # exchange) — the previous regexes below granted write on exchanges and
  # read on the wrong namespace, so every `queue.bind` call either app makes
  # at startup was refused (spec.md §10.1, finding C1). Both app-main
  # (AiJobRabbitConfig + AiResultRabbitConfig) and app-ai
  # (AiJobRabbitConfiguration) declare the FULL topology — all 9 queues,
  # bound from all 4 exchanges — so both accounts need write on every queue
  # (bind-write-on-queue) in addition to the one exchange each actually
  # publishes to (basic.publish), and read on all 4 exchanges
  # (bind-read-on-exchange) in addition to the queue(s) each actually
  # consumes (basic.consume).
  ensure_user ieum_main
  ensure_permissions ieum_main \
    '^ieum\.(ai|main)\..*$' \
    '^ieum\.(ai|main)\..*$' \
    '^ieum\.ai\.(jobs|results|retry|dlx)$|^ieum\.main\.question-answer\.completed$'
  ensure_no_tags ieum_main

  ensure_user ieum_ai
  ensure_permissions ieum_ai \
    '^ieum\.(ai|main)\..*$' \
    '^ieum\.(ai|main)\..*$' \
    '^ieum\.ai\.(jobs|results|retry|dlx|question-answer\.dispatch|accepted-answer\.ingest)$'
  ensure_no_tags ieum_ai

  exec_rmq set_vm_memory_high_watermark absolute 512MB >/dev/null
  exec_rmq set_disk_free_limit absolute 5GB >/dev/null

  printf 'ieum provision rabbitmq: PASS\n'
}
main "$@"
