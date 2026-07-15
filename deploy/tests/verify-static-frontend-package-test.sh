#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
verifier="$root/deploy/tests/verify-static-frontend-package.sh"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

static_dir="$work_dir/static"
routes=(
  chats
  chats/notices
  chats/report
  chats/room
  chats/schedule
  friends
  join
  join/social
  login
  meetups/detail
  my
  my/edit
  my/inquiry
  my/notifications
  my/permissions
  oauth/kakao/callback
  questions
  questions/detail
)

write_fixture() {
  local relative_path="$1"
  mkdir -p "$(dirname "$static_dir/$relative_path")"
  printf 'fixture\n' > "$static_dir/$relative_path"
}

write_fixture index.html
write_fixture index.txt
write_fixture 404.html
write_fixture _next/static/chunks/test-hash.js

for route in "${routes[@]}"; do
  write_fixture "$route/index.html"
  write_fixture "$route/index.txt"
done

"$verifier" "$static_dir" >/dev/null

write_fixture chats/__next.optional.txt
: > "$static_dir/chats/__next.optional.txt"
if "$verifier" "$static_dir" >/dev/null 2>&1; then
  echo "verifier accepted an empty RSC file" >&2
  exit 1
fi

write_fixture chats/__next.optional.txt
mkdir "$static_dir/out"
if "$verifier" "$static_dir" >/dev/null 2>&1; then
  echo "verifier accepted a nested out directory" >&2
  exit 1
fi

rmdir "$static_dir/out"
mkdir "$static_dir/.next"
if "$verifier" "$static_dir" >/dev/null 2>&1; then
  echo "verifier accepted a .next directory" >&2
  exit 1
fi

echo "Static frontend package verifier tests passed."
