#!/usr/bin/env bash
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ONPREM_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
COMPOSE_FILE="$ONPREM_DIR/compose.yml"
APP_NGINX="$ONPREM_DIR/nginx/ieum.rktclgh.site.conf"
FILES_NGINX="$ONPREM_DIR/nginx/files.rktclgh.site.conf"
STAGING_NGINX="$ONPREM_DIR/nginx/ieum1.rktclgh.site.conf"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_file() {
  [ -f "$1" ] || fail "missing $1"
}

assert_contains() {
  file=$1
  pattern=$2
  grep -Eq "$pattern" "$file" || fail "$file does not match $pattern"
}

assert_not_contains() {
  file=$1
  pattern=$2
  if grep -Eq "$pattern" "$file"; then
    fail "$file unexpectedly matches $pattern"
  fi
}

service_block() {
  service=$1
  awk -v service="$service" '
    $0 == "  " service ":" { found = 1 }
    found && ($0 ~ /^  [A-Za-z0-9_-]+:/ || $0 ~ /^networks:/) && $0 != "  " service ":" { exit }
    found { print }
  ' "$COMPOSE_FILE"
}

network_section() {
  printf '%s\n' "$1" | awk '/^    networks:/{inside=1; next} inside && /^    [^ ]/{exit} inside{print}'
}

assert_file "$COMPOSE_FILE"
assert_file "$APP_NGINX"
assert_file "$FILES_NGINX"
assert_file "$STAGING_NGINX"

MAIN_BLOCK=$(service_block app-main)
AI_BLOCK=$(service_block app-ai)
[ -n "$MAIN_BLOCK" ] || fail "missing app-main service block"
[ -n "$AI_BLOCK" ] || fail "missing app-ai service block"

# Compose contract: immutable images, loopback-only ports, server-owned env files,
# host-gateway access, external networks, and no implicit/public network.
assert_contains "$COMPOSE_FILE" '^services:'
assert_contains "$COMPOSE_FILE" '^  app-main:'
assert_contains "$COMPOSE_FILE" '^  app-ai:'
printf '%s\n' "$MAIN_BLOCK" | grep -Eq '^[[:space:]]+image:[[:space:]]*\$\{APP_MAIN_IMAGE_DIGEST\}$' || fail "app-main image contract"
printf '%s\n' "$AI_BLOCK" | grep -Eq '^[[:space:]]+image:[[:space:]]*\$\{APP_AI_IMAGE_DIGEST\}$' || fail "app-ai image contract"
printf '%s\n' "$MAIN_BLOCK" | grep -Eq '^[[:space:]]+- "127\.0\.0\.1:18080:8080"$' || fail "app-main port contract"
printf '%s\n' "$AI_BLOCK" | grep -Eq '^[[:space:]]+- "127\.0\.0\.1:18084:8081"$' || fail "app-ai port contract"
printf '%s\n' "$MAIN_BLOCK" | grep -Eq '/etc/ieum/app-main\.env' || fail "app-main env_file contract"
printf '%s\n' "$AI_BLOCK" | grep -Eq '/etc/ieum/app-ai\.env' || fail "app-ai env_file contract"
printf '%s\n' "$MAIN_BLOCK" | grep -Eq 'host\.docker\.internal:host-gateway' || fail "app-main host gateway contract"
printf '%s\n' "$AI_BLOCK" | grep -Eq 'host\.docker\.internal:host-gateway' || fail "app-ai host gateway contract"
if printf '%s\n' "$MAIN_BLOCK" | grep -Eq 'files\.rktclgh\.site:host-gateway'; then fail "app-main must not override the public files hostname"; fi
if printf '%s\n' "$AI_BLOCK" | grep -Eq 'files\.rktclgh\.site:host-gateway'; then fail "app-ai must use the publicly trusted files hostname, not the Origin CA directly"; fi
printf '%s\n' "$MAIN_BLOCK" | grep -Eq 'restart:[[:space:]]*unless-stopped' || fail "app-main restart contract"
printf '%s\n' "$AI_BLOCK" | grep -Eq 'restart:[[:space:]]*unless-stopped' || fail "app-ai restart contract"
assert_contains "$COMPOSE_FILE" 'external:[[:space:]]*true'
assert_contains "$COMPOSE_FILE" 'name:[[:space:]]*ieum$'
assert_contains "$COMPOSE_FILE" 'name:[[:space:]]*ieum-minio$'
printf '%s\n' "$MAIN_BLOCK" | grep -Eq '^      minio:' \
  || fail "app-main must join the external MinIO network"
printf '%s\n' "$MAIN_BLOCK" | grep -Eq '^      ieum:' \
  || fail "app-main must join the ieum network"
main_network_count=$(printf '%s\n' "$MAIN_BLOCK" | awk '/^    networks:/{inside=1; next} inside && /^    [^ ]/{exit} inside && /^      [A-Za-z0-9_-]+:/{count++} END{print count+0}')
[ "$main_network_count" -eq 2 ] || fail "app-main must have exactly ieum and minio networks"
AI_NETWORKS=$(network_section "$AI_BLOCK")
if printf '%s\n' "$AI_NETWORKS" | grep -Eq 'minio'; then
  fail "app-ai must not join the MinIO network"
fi
printf '%s\n' "$AI_NETWORKS" | grep -Eq '^      - ieum$' || fail "app-ai must join only the ieum network"
unexpected_ai_networks=$(printf '%s\n' "$AI_NETWORKS" | grep -E '^      - ' | grep -Ev '^      - ieum$' || true)
[ -z "$unexpected_ai_networks" ] || fail "app-ai has an unexpected network"
# The existing MinIO container owns the `minio` alias on the external network;
# defining that alias on app-main would incorrectly point `minio` back to app-main.
assert_not_contains "$COMPOSE_FILE" 'aliases:'
assert_not_contains "$COMPOSE_FILE" 'APP_MAIN_PRIVATE_BIND_ADDRESS|APP_AI_BIND_ADDRESS|172\.31\.'

# Exactly two loopback port mappings are allowed; this rejects accidental public
# bindings and catches ports added to either service block.
if ! awk '
  /^[[:space:]]+- "[^"]+:[0-9]+:[0-9]+"/ {
    if ($0 !~ /127\.0\.0\.1:18080:8080/ && $0 !~ /127\.0\.0\.1:18084:8081/) { bad = 1 }
    total++
  }
  END { if (total != 2 || bad) exit 1 }
' "$COMPOSE_FILE"; then
  fail "compose contains an unapproved or missing port mapping"
fi
# Reject unquoted short mappings and long-form Compose ports so they cannot evade
# the exact two approved loopback mappings above.
if grep -Eq '^[[:space:]]+-[[:space:]]+[0-9]+:[0-9]+' "$COMPOSE_FILE"; then
  fail "unquoted numeric port mapping is not allowed"
fi
if grep -Eq '^[[:space:]]+(published|target|host_ip):' "$COMPOSE_FILE"; then
  fail "long-form Compose port mapping is not allowed"
fi
assert_not_contains "$COMPOSE_FILE" 'network_mode:|^[[:space:]]+default:'

# Do not bake a personal/public origin address into deployment topology. Loopback
# is intentional for the Nginx-to-host service boundary; the only non-loopback
# literals allowed are Cloudflare proxy CIDRs used by `set_real_ip_from` on the
# separate Cloudflare-proxied staging vhost.
if grep -E -n '(^|[^0-9])([0-9]{1,3}\.){3}[0-9]{1,3}([^0-9]|$)' \
  "$COMPOSE_FILE" "$APP_NGINX" "$FILES_NGINX" "$STAGING_NGINX" | grep -Ev '127\.0\.0\.1|:[0-9]+:[[:space:]]*set_real_ip_from[[:space:]]+'; then
  fail "unexpected personal or public origin IPv4 literal in on-prem topology"
fi

# Application vhost contract: ACME/HTTPS, blocked internal surfaces, and WS/SSE
# proxy behavior to the loopback app-main port only.
assert_contains "$APP_NGINX" 'server_name[[:space:]]+ieum\.rktclgh\.site;'
assert_contains "$APP_NGINX" 'listen[[:space:]]+80;'
assert_contains "$APP_NGINX" 'location[[:space:]]+\^~[[:space:]]+/\.well-known/acme-challenge/'
assert_contains "$APP_NGINX" 'listen[[:space:]]+443[[:space:]]+ssl'
assert_contains "$APP_NGINX" 'ssl_certificate'
assert_contains "$APP_NGINX" '/etc/cloudflare/rktclgh\.site\.pem'
assert_contains "$APP_NGINX" 'ssl_certificate_key[[:space:]]+/etc/cloudflare/rktclgh\.site\.key;'
assert_not_contains "$APP_NGINX" '/etc/letsencrypt/'
assert_contains "$APP_NGINX" 'real_ip_header[[:space:]]+CF-Connecting-IP;'
assert_contains "$APP_NGINX" 'set_real_ip_from[[:space:]]+190\.93\.240\.0/20;'
assert_contains "$APP_NGINX" 'set_real_ip_from[[:space:]]+2405:8100::/32;'
assert_contains "$APP_NGINX" 'return[[:space:]]+301[[:space:]]+https://\$host\$request_uri;'
assert_contains "$APP_NGINX" 'location[[:space:]]+\^~[[:space:]]+/actuator/'
assert_contains "$APP_NGINX" 'location[[:space:]]+\^~[[:space:]]+/api/v1/internal/'
assert_contains "$APP_NGINX" 'location[[:space:]]+\^~[[:space:]]+/swagger-ui/'
assert_contains "$APP_NGINX" 'location[[:space:]]+\^~[[:space:]]+/v3/api-docs/'
assert_contains "$APP_NGINX" 'proxy_pass[[:space:]]+http://127\.0\.0\.1:18080;'
assert_contains "$APP_NGINX" 'proxy_set_header[[:space:]]+Upgrade[[:space:]]+\$http_upgrade;'
assert_contains "$APP_NGINX" 'proxy_set_header[[:space:]]+Connection[[:space:]]+\$connection_upgrade;'
assert_contains "$APP_NGINX" 'proxy_buffering[[:space:]]+off;'
assert_not_contains "$APP_NGINX" 'proxy_pass[[:space:]]+http://127\.0\.0\.1:(8080|18081)'

# Staging origin contract: a separate Cloudflare-proxied hostname can verify the
# new stack without changing the existing public Ieum hostname. It must use the
# existing wildcard Origin certificate, proxy only to the new loopback port, and
# keep every internal surface private.
assert_contains "$STAGING_NGINX" 'server_name[[:space:]]+ieum1\.rktclgh\.site;'
assert_contains "$STAGING_NGINX" 'listen[[:space:]]+80;'
assert_contains "$STAGING_NGINX" 'listen[[:space:]]+443[[:space:]]+ssl'
assert_contains "$STAGING_NGINX" '/etc/cloudflare/rktclgh\.site\.pem'
assert_contains "$STAGING_NGINX" 'real_ip_header[[:space:]]+CF-Connecting-IP;'
assert_contains "$STAGING_NGINX" 'set_real_ip_from[[:space:]]+190\.93\.240\.0/20;'
assert_contains "$STAGING_NGINX" 'set_real_ip_from[[:space:]]+2405:8100::/32;'
assert_not_contains "$STAGING_NGINX" 'set_real_ip_from[[:space:]]+190\.93\.240\.0/22;'
assert_contains "$STAGING_NGINX" 'location[[:space:]]+\^~[[:space:]]+/actuator/'
assert_contains "$STAGING_NGINX" 'location[[:space:]]+\^~[[:space:]]+/api/v1/internal/'
assert_contains "$STAGING_NGINX" 'proxy_pass[[:space:]]+http://127\.0\.0\.1:18080;'
assert_contains "$STAGING_NGINX" 'proxy_set_header[[:space:]]+Upgrade[[:space:]]+\$http_upgrade;'
assert_contains "$STAGING_NGINX" 'proxy_set_header[[:space:]]+Connection[[:space:]]+"upgrade";'
assert_not_contains "$STAGING_NGINX" 'proxy_pass[[:space:]]+http://127\.0\.0\.1:(8080|18081)'
# Before public-write cutover, both the HTTP redirect vhost and HTTPS origin
# must reject mutating methods while preserving GET/HEAD/OPTIONS proxy paths.
[ "$(grep -Ec 'if \(\$request_method !~ \^\(GET\|HEAD\|OPTIONS\)\$\)' "$STAGING_NGINX")" -eq 2 ] \
  || fail "staging must gate methods in both HTTP and HTTPS server blocks"
[ "$(grep -Ec 'return 405;' "$STAGING_NGINX")" -eq 2 ] \
  || fail "staging method gate must reject mutating methods with 405"
assert_not_contains "$STAGING_NGINX" 'limit_except[[:space:]]+(GET|HEAD|OPTIONS)'
assert_contains "$STAGING_NGINX" 'location[[:space:]]+~[[:space:]]+\^/ws'
assert_contains "$STAGING_NGINX" 'location[[:space:]]+\^~[[:space:]]+/api/v1/sse/'

# MinIO API vhost contract: preserve SigV4 Host, URI and query string; expose no
# console route. Host validation with Nginx/MinIO is expected on the target host.
assert_contains "$FILES_NGINX" 'server_name[[:space:]]+files\.rktclgh\.site;'
assert_contains "$FILES_NGINX" 'listen[[:space:]]+80;'
assert_contains "$FILES_NGINX" 'location[[:space:]]+\^~[[:space:]]+/\.well-known/acme-challenge/'
assert_contains "$FILES_NGINX" 'listen[[:space:]]+443[[:space:]]+ssl'
assert_contains "$FILES_NGINX" '/etc/cloudflare/rktclgh\.site\.pem'
assert_contains "$FILES_NGINX" 'ssl_certificate_key[[:space:]]+/etc/cloudflare/rktclgh\.site\.key;'
assert_not_contains "$FILES_NGINX" '/etc/letsencrypt/'
assert_contains "$FILES_NGINX" 'real_ip_header[[:space:]]+CF-Connecting-IP;'
assert_contains "$FILES_NGINX" 'set_real_ip_from[[:space:]]+190\.93\.240\.0/20;'
assert_contains "$FILES_NGINX" 'set_real_ip_from[[:space:]]+2405:8100::/32;'
assert_contains "$FILES_NGINX" 'proxy_pass[[:space:]]+http://127\.0\.0\.1:19000;'
assert_contains "$FILES_NGINX" 'proxy_set_header[[:space:]]+Host[[:space:]]+\$http_host;'
assert_contains "$FILES_NGINX" 'proxy_request_buffering[[:space:]]+off;'
assert_contains "$FILES_NGINX" 'proxy_buffering[[:space:]]+off;'
assert_not_contains "$FILES_NGINX" '19001|console|rewrite[[:space:]]'

echo "On-prem topology contract passed."
