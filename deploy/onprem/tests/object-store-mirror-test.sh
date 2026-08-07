#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
helper="$root/deploy/onprem/scripts/object-store-mirror.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fail() { printf 'object-store mirror test failed: %s\n' "$1" >&2; exit 1; }

bin="$tmp/bin"; mkdir -p "$bin"
log="$tmp/docker.log"
cat >"$bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$FAKE_DOCKER_LOG"
command=''; target=''
for arg in "$@"; do
  case "$arg" in
    ls|du|cat|stat|mb|mirror) command=$arg ;;
    source/*|target/*) target=$arg ;;
  esac
done
case "$command" in
  '')
    if [[ "${1:-}" == "inspect" ]]; then
      if [[ "${2:-}" == "-f" ]]; then
        if [[ "${4:-}" == "vlainter-minio" ]]; then
          if [[ "${3:-}" == *State.Running* ]]; then
            [[ "${FAKE_MINIO_RUNNING:-1}" == 1 ]] && printf 'true\n' || printf 'false\n'
          else
            printf '{"ieum-minio":{"Aliases":["vlainter-minio","%s"]}}\n' "${FAKE_MINIO_ALIAS:-minio}"
          fi
        else
          exit 2
        fi
      else
        exit 2
      fi
    else
      exit 2
    fi
    ;;
  ls)
    if [[ "$target" == target/* && "${FAKE_TARGET_MISSING:-0}" == 1 && ! -e "${FAKE_BUCKET_MARKER:-}" ]]; then
      exit 1
    fi
    if [[ "$target" == target/* && "${FAKE_TARGET_NONEMPTY:-0}" == 1 ]]; then
      printf '{"key":"reports/a.txt","size":5}\n{"key":"reports/b.txt","size":4}\n{"key":"users/c.txt","size":5}\n'
    elif [[ "$target" == source/* ]]; then
      printf '{"key":"reports/a.txt","size":5}\n{"key":"reports/b.txt","size":4}\n{"key":"users/c.txt","size":5}\n'
    fi
    ;;
  du) printf '{"status":"success","objects":3,"size":14}\n' ;;
  stat) printf '{"status":"success","key":"%s","size":5,"contentType":"text/plain"}\n' "$target" ;;
  cat) [[ "$target" == */a.txt ]] && printf 'alpha' || printf 'beta' ;;
  mb) touch "${FAKE_BUCKET_MARKER:?}" ;;
  mirror) : ;;
  *) exit 2 ;;
esac
EOF
chmod +x "$bin/docker"

env_file="$tmp/object-store.env"; evidence="$tmp/evidence"; mkdir "$evidence"
chmod 700 "$evidence"
cat >"$env_file" <<'EOF'
MIRROR_SOURCE_ENDPOINT=https://s3.example.test
MIRROR_SOURCE_ACCESS_KEY=source-access
MIRROR_SOURCE_SECRET_KEY=source-secret
MIRROR_SOURCE_BUCKET=ieum-prod-files
MIRROR_TARGET_ENDPOINT=http://minio:9000
MIRROR_TARGET_ACCESS_KEY=target-access
MIRROR_TARGET_SECRET_KEY=target-secret
MIRROR_TARGET_BUCKET=ieum-files
EOF
chmod 600 "$env_file"
export IEUM_OBJECT_STORE_MIRROR_TEST_MODE=1 IEUM_OBJECT_STORE_MIRROR_ENV_FILE="$env_file"
export IEUM_OBJECT_STORE_MIRROR_EVIDENCE_DIR="$evidence" IEUM_OBJECT_STORE_MIRROR_DOCKER_BIN="$bin/docker"
export FAKE_DOCKER_LOG="$log"
export FAKE_BUCKET_MARKER="$tmp/bucket-created"

if FAKE_MINIO_RUNNING=0 "$helper" dry-run >/dev/null 2>"$tmp/not-running.err"; then fail 'non-running MinIO container accepted'; fi
grep -F 'not running' "$tmp/not-running.err" >/dev/null || fail 'non-running MinIO rejection missing'
if FAKE_MINIO_ALIAS=other "$helper" dry-run >/dev/null 2>"$tmp/alias.err"; then fail 'invalid MinIO DNS alias accepted'; fi
grep -F 'exactly one minio DNS alias' "$tmp/alias.err" >/dev/null || fail 'invalid MinIO alias rejection missing'

if env -u IEUM_OBJECT_STORE_MIRROR_TEST_MODE "$helper" --env-file "$env_file" dry-run >/dev/null 2>"$tmp/root.err"; then
  [[ "$EUID" -eq 0 ]] || fail 'non-root invocation was accepted without test mode'
fi

if chmod 644 "$env_file"; then
  if "$helper" dry-run >/dev/null 2>"$tmp/mode.err"; then fail 'insecure env mode accepted'; fi
  grep -F 'mode 0600' "$tmp/mode.err" >/dev/null || fail 'insecure env mode was not reported'
  chmod 600 "$env_file"
fi

ln -s "$env_file" "$tmp/env-link"
if IEUM_OBJECT_STORE_MIRROR_ENV_FILE="$tmp/env-link" "$helper" dry-run >/dev/null 2>"$tmp/link.err"; then fail 'symlink env file accepted'; fi
grep -F 'regular non-symlink file' "$tmp/link.err" >/dev/null || fail 'symlink env rejection missing'

if "$helper" --remove dry-run >/dev/null 2>"$tmp/flag.err"; then fail 'remove flag accepted'; fi
grep -F 'unsupported destructive/long-running flag' "$tmp/flag.err" >/dev/null || fail 'remove flag rejection missing'

sed 's/MIRROR_SOURCE_BUCKET=ieum-prod-files/MIRROR_SOURCE_BUCKET=ieum-files/' "$env_file" >"$tmp/wrong-source.env"
chmod 600 "$tmp/wrong-source.env"
if IEUM_OBJECT_STORE_MIRROR_ENV_FILE="$tmp/wrong-source.env" "$helper" dry-run >/dev/null 2>"$tmp/wrong-source.err"; then
  fail 'wrong AWS source bucket was accepted'
fi
grep -F 'source bucket must be ieum-prod-files' "$tmp/wrong-source.err" >/dev/null || fail 'wrong source bucket rejection missing'

: >"$log"
"$helper" copy >/dev/null
grep -F -- 'exec -i vlainter-minio' "$log" >/dev/null || fail 'mirror did not use the existing MinIO container'
if grep -F -- ' run --rm ' "$log" >/dev/null; then fail 'mirror attempted to launch an ephemeral container'; fi
if grep -F -- ' mb ' "$log" >/dev/null; then fail 'copy invoked bucket creation'; fi
grep -F -- '--overwrite --retry --summary' "$log" >/dev/null || fail 'copy did not use required mirror flags'
if grep -E 'source-secret|target-secret|source-access|target-access' "$log" >/dev/null; then fail 'credentials appeared in docker argv'; fi

rm -f "$FAKE_BUCKET_MARKER"
: >"$log"
if FAKE_TARGET_MISSING=1 "$helper" copy >/dev/null 2>"$tmp/missing-target.err"; then
  fail 'copy accepted a missing target bucket'
fi
[[ ! -e "$FAKE_BUCKET_MARKER" ]] || fail 'copy created the missing target bucket'
if grep -F ' mb ' "$log" >/dev/null; then fail 'copy invoked bucket creation for a missing target'; fi

if FAKE_TARGET_NONEMPTY=1 "$helper" copy >/dev/null 2>"$tmp/nonempty.err"; then fail 'copy accepted non-empty target without explicit flag'; fi
grep -F 'target bucket is not empty' "$tmp/nonempty.err" >/dev/null || fail 'non-empty target rejection missing'
FAKE_TARGET_NONEMPTY=1 "$helper" --allow-existing-target dry-run >/dev/null || fail 'explicit non-empty target override rejected'
grep -F -- 'mirror --dry-run --overwrite --retry --summary' "$log" >/dev/null || fail 'dry-run did not invoke mirror dry-run'
rm -f "$FAKE_BUCKET_MARKER"
FAKE_TARGET_NONEMPTY=1 "$helper" dry-run >/dev/null || fail 'dry-run rejected a populated target'
[[ ! -e "$FAKE_BUCKET_MARKER" ]] || fail 'dry-run created the target bucket'
rm -f "$FAKE_BUCKET_MARKER"
if ! FAKE_TARGET_MISSING=1 "$helper" dry-run >/dev/null 2>"$tmp/dry-run-missing.err"; then
  fail 'dry-run required a target listing before invoking mirror mock'
fi

: >"$log"
FAKE_TARGET_NONEMPTY=1 "$helper" verify >/dev/null
[[ -f "$evidence/source.du.json" && -f "$evidence/target.du.json" && -f "$evidence/sample-sha256.json" ]] || fail 'verify evidence files missing'
[[ -f "$evidence/source.ls.json" && -f "$evidence/target.ls.json" ]] || fail 'verify listing artifacts missing'
grep -F 'source_sha256' "$evidence/sample-sha256.json" >/dev/null || fail 'verify sample manifest is empty'
grep -F ' du --json ' "$log" >/dev/null || fail 'verify did not capture du JSON'
grep -F ' stat --json source/ieum-prod-files/reports/a.txt' "$log" >/dev/null || fail 'verify omitted the source bucket from metadata lookup'
grep -F ' stat --json target/ieum-files/reports/a.txt' "$log" >/dev/null || fail 'verify omitted the target bucket from metadata lookup'
grep -F 'users/c.txt' "$evidence/sample-sha256.json" >/dev/null || fail 'verify did not prefer a distinct top-level prefix'
grep -F 'source_content_type' "$evidence/sample-sha256.json" >/dev/null || fail 'verify sample omitted content-type metadata'
grep -F ' cat source/ieum-prod-files/reports/a.txt' "$log" >/dev/null || fail 'verify omitted the source bucket from content lookup'
grep -F ' cat target/ieum-files/reports/a.txt' "$log" >/dev/null || fail 'verify omitted the target bucket from content lookup'
if grep -E 'source-secret|target-secret|source-access|target-access' "$log" >/dev/null; then fail 'credentials appeared in verify argv'; fi

rm -f "$evidence/source.du.json"
ln -s "$tmp/outside-du" "$evidence/source.du.json"
if FAKE_TARGET_NONEMPTY=1 "$helper" --allow-existing-target verify >/dev/null 2>"$tmp/evidence-link.err"; then
  fail 'verify accepted a symlink evidence artifact'
fi
grep -F 'listing/evidence artifact' "$tmp/evidence-link.err" >/dev/null || fail 'symlink evidence artifact rejection missing'
rm -f "$evidence/source.du.json"
FAKE_TARGET_NONEMPTY=1 "$helper" --allow-existing-target verify >/dev/null || fail 'verify failed after removing symlink artifact'

printf 'object-store mirror test: PASS\n'
