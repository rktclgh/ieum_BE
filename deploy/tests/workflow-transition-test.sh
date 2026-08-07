#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$root"
fail() { echo "workflow transition test failed: $*" >&2; exit 1; }
for retired in .github/workflows/deploy-app-main.yml .github/workflows/deploy-app-ai.yml; do
  test ! -e "$retired" || fail "retired AWS workflow remains executable: $retired"
done
release=.github/workflows/release-onprem.yml; test -s "$release" || fail "workflow missing"
grep -Fq 'name: Ieum on-prem release' "$release" || fail "workflow name changed"
grep -Fq 'group: ieum-onprem-production' "$release" || fail "concurrency guard missing"
grep -Fq 'environment: ieum-production' "$release" || fail "production environment missing"
grep -Fq 'DOCKER_MAIN_REPOSITORY: docker.io/songchih/ieum-app-main' "$release" || fail "app-main image repository"
grep -Fq 'DOCKER_AI_REPOSITORY: docker.io/songchih/ieum-app-ai' "$release" || fail "app-ai image repository"
if grep -Fq 'docker.io/rktclgh/ieum-app-' "$release"; then fail "stale Docker image repository assertion"; fi
job() { awk -v wanted="$1" '$0 == "  " wanted ":" { in_job=1; next } in_job && $0 ~ /^  [A-Za-z0-9_-]+:$/ { exit } in_job { print }' "$release"; }
frontend="$(job frontend-build)"; plan="$(job local-plan)"; sign="$(job build-sign)"; apply="$(job local-apply)"; verify="$(job public-verify)"
grep -Fq 'runs-on: ubuntu-latest' <<<"$frontend" || fail "frontend must be hosted"
grep -Fq 'actions: read' <<<"$frontend" || fail "frontend actions permission must be read-only"
if grep -Fq 'actions: write' <<<"$frontend"; then fail "frontend must not manage workflow runs"; fi
grep -Fq 'runs-on: [self-hosted, linux, x64, ieum-prod-deploy]' <<<"$plan" || fail "plan labels"
grep -Fq 'runs-on: ubuntu-latest' <<<"$sign" || fail "sign must be hosted"
grep -Fq 'runs-on: [self-hosted, linux, x64, ieum-prod-deploy]' <<<"$apply" || fail "apply labels"
grep -Fq 'runs-on: ubuntu-latest' <<<"$verify" || fail "verify must be hosted"
grep -Fq 'needs: [frontend-build, local-plan]' <<<"$sign" || fail "sign needs"
grep -Fq 'needs: build-sign' <<<"$apply" || fail "apply needs"
grep -Fq 'needs: [build-sign, local-apply]' <<<"$verify" || fail "verify needs"
grep -Fq 'sudo -n /usr/local/sbin/ieum-release-dispatch --local current --json' <<<"$plan" || fail "local current dispatcher"
grep -Fq 'jq -e' <<<"$plan" || fail "plan JSON validation"
grep -Fq 'actions/checkout' <<<"$frontend" || fail "frontend checkout"
for local_job in "$plan" "$apply"; do
  if grep -Eq 'actions/checkout|secrets\.|vars\.' <<<"$local_job"; then fail "local job has checkout or credentials"; fi
done
if grep -Eiq 'ONPREM_RELEASE_SSH|known_hosts|scp|(^|[^A-Za-z])ssh([^A-Za-z-]|$)' "$release"; then fail "obsolete SSH transport remains"; fi
grep -Fq 'actions/download-artifact@v4' <<<"$apply" || fail "artifact download"
grep -Fq 'artifact-ids: ${{ needs.build-sign.outputs.artifact_id }}' <<<"$apply" || fail "artifact id download"
grep -Fq 'find "$root" -mindepth 1 -maxdepth 1' <<<"$apply" || fail "envelope discovery"
grep -Fq "'f:release-envelope.tar'" <<<"$apply" || fail "exact envelope shape"
grep -Fq 'test ! -L "$envelope"' <<<"$apply" || fail "symlink rejection"
grep -Fq 'actual_envelope_sha=' <<<"$apply" || fail "envelope hash check"
grep -Fq 'ieum-release-dispatch --local apply' <<<"$apply" || fail "local apply dispatcher"
for output in artifact_id release_id current bundle_sha envelope_sha; do
  grep -Fq "${output}: \${{ steps." <<<"$sign" || fail "missing output: $output"
done
grep -Fq 'id: signed-artifact' <<<"$sign" || fail "signed artifact step"
grep -Fq 'path: ${{ runner.temp }}/ieum-release/release-envelope.tar' <<<"$sign" || fail "exact envelope artifact"
grep -Fq 'X-Ieum-Release-ID' "$release" || fail "release header verification"
grep -Fq '[[ "$status" == 200 ]]' "$release" || fail "HTTP 200 verification"
grep -Fq ' -X POST ' "$release" || fail "POST probe"
grep -Fq '[[ "$post_status" == 405 ]]' "$release" || fail "POST 405 verification"
grep -Fq './gradlew :app-main:test' <<<"$sign" || fail "focused tests"
grep -Fq -- '--tests' <<<"$sign" || fail "test selectors"
grep -Fq 'FileConfigTest' <<<"$sign" || fail "FileConfigTest"
grep -Fq 'RedisRuntimePropertiesTest' <<<"$sign" || fail "RedisRuntimePropertiesTest"
grep -Fq 'ssh-keygen -Y sign -f "$root/signing_key" -n ieum-release < "$inner" > "$root/release.tar.sig"' <<<"$sign" \
  || fail "release signing must stream the payload on stdin"
if grep -Fq 'ieum-release "$inner" > "$root/release.tar.sig"' <<<"$sign"; then
  fail "file-argument signing creates the signature beside the input instead of on stdout"
fi

sshsig_tmp="$(mktemp -d "${TMPDIR:-/tmp}/ieum-workflow-sshsig.XXXXXX")"
trap 'rm -rf "$sshsig_tmp"' EXIT
ssh-keygen -q -t ed25519 -N '' -f "$sshsig_tmp/signing-key"
printf 'signed release fixture\n' >"$sshsig_tmp/release.tar"
printf 'ieum-release %s\n' "$(awk '{print $1 " " $2}' "$sshsig_tmp/signing-key.pub")" >"$sshsig_tmp/allowed-signers"
ssh-keygen -Y sign -f "$sshsig_tmp/signing-key" -n ieum-release \
  <"$sshsig_tmp/release.tar" >"$sshsig_tmp/release.tar.sig"
test -s "$sshsig_tmp/release.tar.sig" || fail "streamed SSH signature is empty"
ssh-keygen -Y verify -n ieum-release -I ieum-release -f "$sshsig_tmp/allowed-signers" \
  -s "$sshsig_tmp/release.tar.sig" <"$sshsig_tmp/release.tar" >/dev/null 2>&1 \
  || fail "streamed SSH signature cannot be verified"
echo "Workflow transition contract passed."
