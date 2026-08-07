#!/usr/bin/env bash
set -euo pipefail
umask 077

DISPATCH_PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
ROOT_HELPER='/usr/local/sbin/ieum-deploy-release'

die() { printf 'ieum release dispatch: %s\n' "$1" >&2; exit 64; }

PATH="$DISPATCH_PATH"
export PATH

# The dispatcher is intentionally a local, root-only boundary for the
# self-hosted runner.  Test mode may substitute the helper and root check, but
# production has no environment-controlled privilege or transport fallback.
is_root() {
  [[ "$EUID" -eq 0 ]] || {
    [[ "${IEUM_RELEASE_DISPATCH_TEST_MODE:-}" == 1 &&
      "${IEUM_RELEASE_DISPATCH_TEST_ASSUME_ROOT:-}" == 1 ]]
  }
}

if [[ "$EUID" -ne 0 ]]; then
  [[ "${IEUM_RELEASE_DISPATCH_TEST_MODE:-}" == 1 ]] || die 'dispatcher must use production command paths'
  ROOT_HELPER=${IEUM_RELEASE_DISPATCH_ROOT_HELPER:-}
  [[ "$ROOT_HELPER" = /* ]] || die 'test command path must be absolute'
fi

[[ "${1-}" == '--local' ]] || die 'only local dispatch is supported'
shift
is_root || die 'local mode requires root'

case "${1-}" in
  current)
    [[ "$#" -eq 2 && "${2-}" == '--json' ]] || die 'unsupported or malformed local command'
    exec "$ROOT_HELPER" current --json </dev/null
    ;;
  apply)
    [[ "$#" -eq 7 ]] || die 'unsupported or malformed local command'
    [[ "$2" == '--release-id' && "$4" == '--expected-current' && "$6" == '--bundle-sha256' ]] || die 'unsupported or malformed local command'
    [[ "$3" =~ ^r-[0-9]+-[1-9][0-9]*-[0-9a-f]{40}$ ]] || die 'unsupported or malformed local command'
    [[ "$5" == none || "$5" =~ ^r-[0-9]+-[1-9][0-9]*-[0-9a-f]{40}$ ]] || die 'unsupported or malformed local command'
    [[ "$7" =~ ^[0-9a-f]{64}$ ]] || die 'unsupported or malformed local command'
    exec "$ROOT_HELPER" apply \
      --release-id "$3" \
      --expected-current "$5" \
      --bundle-sha256 "$7"
    ;;
  rollback)
    die 'local rollback is not supported'
    ;;
  *)
    die 'unsupported or malformed local command'
    ;;
esac
