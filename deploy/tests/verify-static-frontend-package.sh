#!/usr/bin/env bash
set -euo pipefail

if (( $# < 1 || $# > 2 )); then
  echo "usage: $0 <static-directory> [boot-jar]" >&2
  exit 2
fi

static_dir="${1%/}"
jar_file="${2:-}"

fail() {
  echo "static frontend verification failed: $*" >&2
  exit 1
}

require_static_file() {
  local relative_path="$1"
  test -s "$static_dir/$relative_path" || fail "missing static file: $relative_path"
}

test -d "$static_dir" || fail "static directory does not exist: $static_dir"
test ! -e "$static_dir/out" || fail "nested out directory is forbidden"

if find "$static_dir" -name .next -print -quit | grep -q .; then
  fail ".next entry is forbidden"
fi

empty_rsc="$(find "$static_dir" -type f -name '*.txt' -empty -print -quit)"
test -z "$empty_rsc" || fail "empty RSC file: ${empty_rsc#"$static_dir/"}"

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

require_static_file index.html
require_static_file index.txt
require_static_file 404.html

for route in "${routes[@]}"; do
  require_static_file "$route/index.html"
  require_static_file "$route/index.txt"
done

test -n "$(find "$static_dir/_next/static" -type f -print -quit 2>/dev/null)" \
  || fail "no hashed Next.js static asset found"

if [[ -z "$jar_file" ]]; then
  echo "Static frontend directory verification passed."
  exit 0
fi

test -s "$jar_file" || fail "boot JAR does not exist: $jar_file"
require_static_file error/404.html

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

jar_listing="$work_dir/jar-listing.txt"
expected_rsc="$work_dir/expected-rsc.txt"
packaged_rsc="$work_dir/packaged-rsc.txt"

jar tf "$jar_file" > "$jar_listing"

require_jar_entry() {
  local relative_path="$1"
  grep -Fxq "BOOT-INF/classes/static/$relative_path" "$jar_listing" \
    || fail "missing JAR entry: $relative_path"
}

require_jar_entry index.html
require_jar_entry index.txt
require_jar_entry 404.html
require_jar_entry error/404.html

for route in "${routes[@]}"; do
  require_jar_entry "$route/index.html"
  require_jar_entry "$route/index.txt"
done

grep -Eq '^BOOT-INF/classes/static/_next/static/.+' "$jar_listing" \
  || fail "no hashed Next.js static asset packaged in JAR"

(
  cd "$static_dir"
  find . -type f -name '*.txt' -print \
    | sed 's#^\./##' \
    | LC_ALL=C sort
) > "$expected_rsc"

sed -n 's#^BOOT-INF/classes/static/\(.*\.txt\)$#\1#p' "$jar_listing" \
  | LC_ALL=C sort > "$packaged_rsc"

if ! diff -u "$expected_rsc" "$packaged_rsc"; then
  fail "packaged RSC manifest differs from static directory"
fi

echo "Static frontend JAR verification passed."
