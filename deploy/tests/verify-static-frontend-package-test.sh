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
  admin
  admin/login
  admin/users
  admin/users/detail
  admin/reports
  admin/reports/detail
  admin/inquiries
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

rm "$static_dir/admin/users/index.html"
if "$verifier" "$static_dir" >/dev/null 2>&1; then
  echo "verifier accepted a missing admin HTML file" >&2
  exit 1
fi
write_fixture admin/users/index.html

rm "$static_dir/admin/reports/detail/index.txt"
if "$verifier" "$static_dir" >/dev/null 2>&1; then
  echo "verifier accepted a missing admin RSC file" >&2
  exit 1
fi
write_fixture admin/reports/detail/index.txt

write_fixture my/settings/index.html
write_fixture my/settings/index.txt
if "$verifier" "$static_dir" >/dev/null 2>&1; then
  echo "verifier accepted the removed my/settings route" >&2
  exit 1
fi
rm -rf "$static_dir/my/settings"

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
rmdir "$static_dir/.next"

write_fixture error/404.html
jar_root="$work_dir/jar-root"
mkdir -p "$jar_root/BOOT-INF/classes"
cp -R "$static_dir" "$jar_root/BOOT-INF/classes/static"
jar_file="$work_dir/app-main.jar"
jar --create --file "$jar_file" -C "$jar_root" BOOT-INF

"$verifier" "$static_dir" "$jar_file" >/dev/null

printf 'different fixture\n' > "$static_dir/admin/index.html"
if "$verifier" "$static_dir" "$jar_file" >/dev/null 2>&1; then
  echo "verifier accepted a JAR with different static bytes" >&2
  exit 1
fi

echo "Static frontend package verifier tests passed."
