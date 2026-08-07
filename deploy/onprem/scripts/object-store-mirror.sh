#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

die() { printf 'ieum object-store mirror: %s\n' "$1" >&2; exit 64; }
usage() {
  printf 'usage: %s [--env-file <absolute-file>] <dry-run|copy|verify> [--allow-existing-target]\n' "$0" >&2
  exit 64
}

test_mode=false
if [[ "$EUID" -eq 0 ]]; then
  PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'; export PATH
  ENV_FILE=/etc/ieum/object-store-mirror.env
  EVIDENCE_DIR=/var/lib/ieum/object-store-mirror
  DOCKER_BIN=/usr/bin/docker
else
  [[ "${IEUM_OBJECT_STORE_MIRROR_TEST_MODE:-}" == 1 ]] || die 'must run as root'
  test_mode=true
  ENV_FILE=${IEUM_OBJECT_STORE_MIRROR_ENV_FILE:-}
  EVIDENCE_DIR=${IEUM_OBJECT_STORE_MIRROR_EVIDENCE_DIR:-}
  DOCKER_BIN=${IEUM_OBJECT_STORE_MIRROR_DOCKER_BIN:-/usr/bin/docker}
  [[ "$ENV_FILE" = /* && "$EVIDENCE_DIR" = /* && "$DOCKER_BIN" = /* ]] || die 'test paths must be absolute'
fi

allow_existing=false
env_file_arg=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) [[ $# -ge 2 ]] || die '--env-file requires a value'; env_file_arg=$2; shift 2 ;;
    dry-run|copy|verify) [[ -z "${operation:-}" ]] || die 'operation was specified more than once'; operation=$1; shift ;;
    --allow-existing-target) allow_existing=true; shift ;;
    --remove|--watch) die "unsupported destructive/long-running flag: $1" ;;
    *) die "unsupported argument: $1" ;;
  esac
done
[[ -n "${operation:-}" ]] || usage
if [[ -n "$env_file_arg" ]]; then ENV_FILE=$env_file_arg; fi
[[ "$ENV_FILE" = /* ]] || die 'environment file must be an absolute path'

mode_of() { stat -c '%a' -- "$1" 2>/dev/null || stat -f '%Lp' -- "$1"; }
owner_of() { stat -c '%u' -- "$1" 2>/dev/null || stat -f '%u' -- "$1"; }
expected_owner=$(id -u)
require_private_file() {
  local file=$1 label=$2
  [[ -f "$file" && ! -L "$file" ]] || die "$label must be a regular non-symlink file"
  [[ "$(owner_of "$file")" == "$expected_owner" ]] || die "$label has an unexpected owner"
  [[ "$(mode_of "$file")" == 600 ]] || die "$label must have mode 0600"
}
require_private_dir() {
  local dir=$1 label=$2
  [[ -d "$dir" && ! -L "$dir" ]] || die "$label must be a regular non-symlink directory"
  [[ "$(owner_of "$dir")" == "$expected_owner" ]] || die "$label has an unexpected owner"
  [[ "$(mode_of "$dir")" == 700 ]] || die "$label must have mode 0700"
}
prepare_private_artifact() {
  local file=$1 label=$2
  if [[ -e "$file" || -L "$file" ]]; then
    require_private_file "$file" "$label"
  else
    : >"$file"
    chmod 600 "$file"
    require_private_file "$file" "$label"
  fi
}
require_private_file "$ENV_FILE" 'environment file'
if [[ ! -e "$EVIDENCE_DIR" ]]; then
  mkdir -p -- "$EVIDENCE_DIR"
  chmod 700 -- "$EVIDENCE_DIR"
fi
require_private_dir "$EVIDENCE_DIR" 'evidence directory'
[[ -x "$DOCKER_BIN" ]] || die 'docker executable is missing'

allowed_keys=' MIRROR_SOURCE_ENDPOINT MIRROR_SOURCE_ACCESS_KEY MIRROR_SOURCE_SECRET_KEY MIRROR_SOURCE_BUCKET MIRROR_TARGET_ENDPOINT MIRROR_TARGET_ACCESS_KEY MIRROR_TARGET_SECRET_KEY MIRROR_TARGET_BUCKET '
seen_keys='|'
unset MIRROR_SOURCE_ENDPOINT MIRROR_SOURCE_ACCESS_KEY MIRROR_SOURCE_SECRET_KEY MIRROR_SOURCE_BUCKET MIRROR_TARGET_ENDPOINT MIRROR_TARGET_ACCESS_KEY MIRROR_TARGET_SECRET_KEY MIRROR_TARGET_BUCKET
while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "$line" || "$line" == \#* ]] && continue
  [[ "$line" =~ ^([A-Z][A-Z0-9_]*)=([^[:cntrl:]]*)$ ]] || die 'environment file contains an invalid line'
  key=${BASH_REMATCH[1]}; value=${BASH_REMATCH[2]}
  [[ "$allowed_keys" == *" $key "* ]] || die "environment file contains an unsupported key: $key"
  [[ "$seen_keys" != *"|$key|"* ]] || die "environment file contains a duplicate key: $key"
  [[ -n "$value" ]] || die "environment value is empty: $key"
  seen_keys="${seen_keys}${key}|"
  case "$key" in
    MIRROR_SOURCE_ENDPOINT) MIRROR_SOURCE_ENDPOINT=$value ;;
    MIRROR_SOURCE_ACCESS_KEY) MIRROR_SOURCE_ACCESS_KEY=$value ;;
    MIRROR_SOURCE_SECRET_KEY) MIRROR_SOURCE_SECRET_KEY=$value ;;
    MIRROR_SOURCE_BUCKET) MIRROR_SOURCE_BUCKET=$value ;;
    MIRROR_TARGET_ENDPOINT) MIRROR_TARGET_ENDPOINT=$value ;;
    MIRROR_TARGET_ACCESS_KEY) MIRROR_TARGET_ACCESS_KEY=$value ;;
    MIRROR_TARGET_SECRET_KEY) MIRROR_TARGET_SECRET_KEY=$value ;;
    MIRROR_TARGET_BUCKET) MIRROR_TARGET_BUCKET=$value ;;
  esac
done <"$ENV_FILE"
for key in MIRROR_SOURCE_ENDPOINT MIRROR_SOURCE_ACCESS_KEY MIRROR_SOURCE_SECRET_KEY MIRROR_SOURCE_BUCKET MIRROR_TARGET_ENDPOINT MIRROR_TARGET_ACCESS_KEY MIRROR_TARGET_SECRET_KEY MIRROR_TARGET_BUCKET; do
  case "$key" in
    MIRROR_SOURCE_ENDPOINT) value=${MIRROR_SOURCE_ENDPOINT:-} ;;
    MIRROR_SOURCE_ACCESS_KEY) value=${MIRROR_SOURCE_ACCESS_KEY:-} ;;
    MIRROR_SOURCE_SECRET_KEY) value=${MIRROR_SOURCE_SECRET_KEY:-} ;;
    MIRROR_SOURCE_BUCKET) value=${MIRROR_SOURCE_BUCKET:-} ;;
    MIRROR_TARGET_ENDPOINT) value=${MIRROR_TARGET_ENDPOINT:-} ;;
    MIRROR_TARGET_ACCESS_KEY) value=${MIRROR_TARGET_ACCESS_KEY:-} ;;
    MIRROR_TARGET_SECRET_KEY) value=${MIRROR_TARGET_SECRET_KEY:-} ;;
    MIRROR_TARGET_BUCKET) value=${MIRROR_TARGET_BUCKET:-} ;;
  esac
  [[ -n "$value" ]] || die "environment value is missing: $key"
done

for endpoint_key in MIRROR_SOURCE_ENDPOINT MIRROR_TARGET_ENDPOINT; do
  case "$endpoint_key" in
    MIRROR_SOURCE_ENDPOINT) endpoint=$MIRROR_SOURCE_ENDPOINT ;;
    MIRROR_TARGET_ENDPOINT) endpoint=$MIRROR_TARGET_ENDPOINT ;;
  esac
  [[ "$endpoint" =~ ^https?://[^/[:space:]]+/?$ ]] || die "$endpoint_key must be an http(s) endpoint"
done
for bucket_key in MIRROR_SOURCE_BUCKET MIRROR_TARGET_BUCKET; do
  case "$bucket_key" in
    MIRROR_SOURCE_BUCKET) bucket=$MIRROR_SOURCE_BUCKET ;;
    MIRROR_TARGET_BUCKET) bucket=$MIRROR_TARGET_BUCKET ;;
  esac
  [[ "$bucket" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || die "$bucket_key is not a valid bucket name"
done
[[ "$MIRROR_SOURCE_BUCKET" == ieum-prod-files ]] || die 'source bucket must be ieum-prod-files'
[[ "$MIRROR_TARGET_BUCKET" == ieum-files ]] || die 'target bucket must be ieum-files'
for secret_key in MIRROR_SOURCE_ACCESS_KEY MIRROR_SOURCE_SECRET_KEY MIRROR_TARGET_ACCESS_KEY MIRROR_TARGET_SECRET_KEY; do
  case "$secret_key" in
    MIRROR_SOURCE_ACCESS_KEY) secret=$MIRROR_SOURCE_ACCESS_KEY ;;
    MIRROR_SOURCE_SECRET_KEY) secret=$MIRROR_SOURCE_SECRET_KEY ;;
    MIRROR_TARGET_ACCESS_KEY) secret=$MIRROR_TARGET_ACCESS_KEY ;;
    MIRROR_TARGET_SECRET_KEY) secret=$MIRROR_TARGET_SECRET_KEY ;;
  esac
  [[ "$secret" != *$'\n'* && "$secret" != *$'\r'* ]] || die "$secret_key contains a newline"
done

MINIO_CONTAINER=vlainter-minio
[[ "$("$DOCKER_BIN" inspect -f '{{.State.Running}}' "$MINIO_CONTAINER" 2>/dev/null)" == true ]] || die 'existing MinIO container is not running'
network_json=$("$DOCKER_BIN" inspect -f '{{json .NetworkSettings.Networks}}' "$MINIO_CONTAINER" 2>/dev/null) || die 'unable to inspect existing MinIO network'
NETWORK_JSON=$network_json python3 - <<'PY'
import json, os
try:
    networks=json.loads(os.environ['NETWORK_JSON'])
    aliases=networks['ieum-minio']['Aliases']
except (KeyError, TypeError, json.JSONDecodeError):
    raise SystemExit('existing MinIO container is not attached to ieum-minio')
if aliases.count('minio') != 1:
    raise SystemExit('existing MinIO container must have exactly one minio DNS alias')
PY

config_dir=$(mktemp -d "$EVIDENCE_DIR/.mc-config.XXXXXX")
chmod 700 "$config_dir"
trap 'rm -rf -- "${config_dir:-}"' EXIT
config_json="$config_dir/config.json"
export MIRROR_SOURCE_ENDPOINT MIRROR_SOURCE_ACCESS_KEY MIRROR_SOURCE_SECRET_KEY MIRROR_TARGET_ENDPOINT MIRROR_TARGET_ACCESS_KEY MIRROR_TARGET_SECRET_KEY
python3 - "$config_json" <<'PY'
import json, os, sys
out = sys.argv[1]
def value(name): return os.environ[name]
aliases = {
  "source": {"url": value("MIRROR_SOURCE_ENDPOINT"), "accessKey": value("MIRROR_SOURCE_ACCESS_KEY"), "secretKey": value("MIRROR_SOURCE_SECRET_KEY"), "api": "S3v4", "path": "auto"},
  "target": {"url": value("MIRROR_TARGET_ENDPOINT"), "accessKey": value("MIRROR_TARGET_ACCESS_KEY"), "secretKey": value("MIRROR_TARGET_SECRET_KEY"), "api": "S3v4", "path": "auto"},
}
with open(out, "w", encoding="utf-8") as f:
    json.dump({"version": "10", "aliases": aliases}, f, separators=(",", ":"))
PY
chmod 600 "$config_json"

mc() {
  "$DOCKER_BIN" exec -i "$MINIO_CONTAINER" sh -c '
    set -eu
    config_dir=/tmp/ieum-mc-$$
    trap '\''rm -rf -- "$config_dir"'\'' EXIT
    mkdir -m 700 -- "$config_dir"
    cat >"$config_dir/config.json"
    mc --config-dir "$config_dir" "$@"
  ' sh "$@" <"$config_json"
}
source_path="source/$MIRROR_SOURCE_BUCKET"
target_path="target/$MIRROR_TARGET_BUCKET"

source_ls="$EVIDENCE_DIR/source.ls.json"; target_ls="$EVIDENCE_DIR/target.ls.json"
prepare_private_artifact "$source_ls" 'listing/evidence artifact'
mc ls --recursive --json "$source_path" >"$source_ls" || die 'unable to list source object store'
chmod 600 "$source_ls"
if [[ "$operation" == dry-run ]]; then
  mc mirror --dry-run --overwrite --retry --summary "$source_path" "$target_path" >/dev/null || die 'unable to run mirror dry-run'
  printf 'ieum object-store mirror: dry-run source=%s target=%s\n' "$MIRROR_SOURCE_BUCKET" "$MIRROR_TARGET_BUCKET"
  exit 0
fi

prepare_private_artifact "$target_ls" 'listing/evidence artifact'
mc ls --recursive --json "$target_path" >"$target_ls" || die 'unable to list target object store'
chmod 600 "$source_ls" "$target_ls"
if [[ "$operation" == copy && "$allow_existing" != true ]] && [[ -s "$target_ls" ]]; then
  die 'target bucket is not empty; pass --allow-existing-target explicitly'
fi

if [[ "$operation" == copy ]]; then
  mc mirror --overwrite --retry --summary "$source_path" "$target_path" >/dev/null || die 'unable to mirror source objects'
  printf 'ieum object-store mirror: copy complete\n'
  exit 0
fi

source_du="$EVIDENCE_DIR/source.du.json"; target_du="$EVIDENCE_DIR/target.du.json"
prepare_private_artifact "$source_du" 'listing/evidence artifact'
prepare_private_artifact "$target_du" 'listing/evidence artifact'
mc du --json "$source_path" >"$source_du" || die 'unable to capture source usage'
mc du --json "$target_path" >"$target_du" || die 'unable to capture target usage'
chmod 600 "$source_du" "$target_du"
sample_manifest="$EVIDENCE_DIR/sample-sha256.json"
prepare_private_artifact "$sample_manifest" 'listing/evidence artifact'
mc_wrapper="$config_dir/mc-wrapper"
cat >"$mc_wrapper" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
"$DOCKER_BIN" exec -i "$MINIO_CONTAINER" sh -c '
  set -eu
  config_dir=/tmp/ieum-mc-$$
  trap '\''rm -rf -- "$config_dir"'\'' EXIT
  mkdir -m 700 -- "$config_dir"
  cat >"$config_dir/config.json"
  mc --config-dir "$config_dir" "$@"
' sh "$@" <"$MC_CONFIG_JSON"
EOF
chmod 700 "$mc_wrapper"
export DOCKER_BIN MINIO_CONTAINER MC_CONFIG_JSON MC_WRAPPER MIRROR_SOURCE_BUCKET MIRROR_TARGET_BUCKET
MC_CONFIG_JSON="$config_json" MC_WRAPPER="$mc_wrapper"
python3 - "$source_du" "$target_du" "$source_ls" "$target_ls" "$sample_manifest" <<'PY'
import hashlib, json, os, subprocess, sys
source_du, target_du, source_ls, target_ls, out = sys.argv[1:]
def totals(path):
    objects = size = None
    for line in open(path, encoding='utf-8'):
        try: item=json.loads(line)
        except json.JSONDecodeError: continue
        if 'objects' in item: objects = (objects or 0) + int(item['objects'])
        if 'size' in item: size = (size or 0) + int(item['size'])
    if objects is None or size is None: raise SystemExit('invalid du JSON')
    return objects, size
if totals(source_du) != totals(target_du):
    raise SystemExit('source and target usage differs')
def all_entries(path):
    result=[]
    for line in open(path, encoding='utf-8'):
        try: item=json.loads(line)
        except json.JSONDecodeError: continue
        key=item.get('key') or item.get('name') or item.get('Key')
        if key is not None: result.append((str(key), int(item.get('size', item.get('Size', 0)))))
    return sorted(result)
source_all, target_all = all_entries(source_ls), all_entries(target_ls)
if source_all != target_all:
    raise SystemExit('source and target key-size listings differ')
def select(entries):
    selected=[]; prefixes=set()
    for key, size in entries:
        prefix=key.split('/', 1)[0]
        if prefix not in prefixes:
            selected.append((key,size)); prefixes.add(prefix)
        if len(selected) == 10: return selected
    for entry in entries:
        if entry not in selected: selected.append(entry)
        if len(selected) == 10: break
    return selected
src = select(source_all)
def mc_json(alias, key, command):
    bucket = os.environ['MIRROR_SOURCE_BUCKET'] if alias == 'source' else os.environ['MIRROR_TARGET_BUCKET']
    raw=subprocess.check_output([os.environ['MC_WRAPPER'], command, '--json', f'{alias}/{bucket}/{key}'])
    for line in reversed(raw.decode().splitlines()):
        try: return json.loads(line)
        except json.JSONDecodeError: continue
    raise SystemExit(f'invalid mc {command} JSON')
payload=[]
for key, size in src:
    row={"key": key, "size": size}
    for alias in ('source','target'):
        stat=mc_json(alias, key, 'stat')
        row[alias+'_content_type']=stat.get('contentType') or stat.get('metadata',{}).get('Content-Type')
        bucket = os.environ['MIRROR_SOURCE_BUCKET'] if alias == 'source' else os.environ['MIRROR_TARGET_BUCKET']
        path=f'{alias}/{bucket}/{key}'
        raw=subprocess.check_output([os.environ['MC_WRAPPER'], 'cat', path])
        row[alias+'_sha256']=hashlib.sha256(raw).hexdigest()
    if row['source_content_type'] != row['target_content_type']: raise SystemExit('content-type metadata differs')
    if row['source_sha256'] != row['target_sha256']: raise SystemExit('bounded object content differs')
    payload.append(row)
json.dump(payload, open(out,'w',encoding='utf-8'), separators=(',',':'))
PY
chmod 600 "$sample_manifest"
printf 'ieum object-store mirror: verify complete\n'
