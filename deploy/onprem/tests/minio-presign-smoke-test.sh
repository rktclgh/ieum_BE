#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
env_file="$tmp/app-main.env"
cat >"$env_file" <<'EOF'
AWS_ACCESS_KEY_ID=fixture-access
AWS_SECRET_ACCESS_KEY=fixture-secret
AWS_S3_BUCKET=ieum-files
AWS_S3_PRESIGN_ENDPOINT=https://files.rktclgh.site
AWS_S3_REGION=us-east-1
EOF
chmod 600 "$env_file"

PYTHONPATH="$root/deploy/onprem/scripts" IEUM_MINIO_PRESIGN_SMOKE_TEST_MODE=1 IEUM_MINIO_PRESIGN_SMOKE_ENV_FILE="$env_file" \
python3 - <<'PY'
import importlib.util
import contextlib
import hashlib
import hmac
import io
import os
import urllib.parse

spec = importlib.util.spec_from_file_location("smoke", os.path.join(os.environ["PYTHONPATH"], "minio-presign-smoke.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
fixture = b"ieum-minio-presign-smoke\n"
calls = []

RealDateTime = smoke.dt.datetime
class FixedDateTime:
    @classmethod
    def now(cls, tz=None):
        return RealDateTime(2026, 1, 2, 3, 4, 5, tzinfo=tz)

smoke.dt.datetime = FixedDateTime
smoke.secrets.token_hex = lambda count: "ab" * count
retired_origin_allowed = False

def fake_request(url, method, headers=None, body=None, **kwargs):
    calls.append((method, headers or {}, body, url))
    query = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)
    if method != "OPTIONS":
        assert query["X-Amz-Signature"][0]
        assert "fixture-secret" not in url
    if method == "OPTIONS":
        allow_origin = headers["Origin"] if headers["Origin"] == smoke.PUBLIC_ORIGIN or (retired_origin_allowed and headers["Origin"] == smoke.RETIRED_ORIGIN) else ""
        return 204, {
            "access-control-allow-origin": allow_origin,
            "access-control-allow-methods": "GET, PUT, HEAD, DELETE",
            "access-control-allow-headers": "content-type",
        }, b""
    if method == "GET":
        if sum(item[0] == "GET" for item in calls) > 1:
            return 404, {}, b""
        return 200, {"content-length": str(len(fixture))}, fixture
    if method == "HEAD":
        return 200, {"content-length": str(len(fixture))}, b""
    return 200, {}, b""

smoke.request = fake_request
smoke.main()
assert [item[0] for item in calls] == ["OPTIONS", "OPTIONS", "OPTIONS", "PUT", "GET", "HEAD", "DELETE", "GET", "DELETE"]
assert all(item[1].get("User-Agent") == smoke.USER_AGENT for item in calls)
assert [item[1]["Access-Control-Request-Method"] for item in calls[:2]] == ["PUT", "DELETE"]
assert calls[0][1]["Access-Control-Request-Headers"] == "content-type"
assert "Access-Control-Request-Headers" not in calls[1][1]
assert calls[2][1]["Origin"] == smoke.RETIRED_ORIGIN
assert calls[3][1]["Origin"] == smoke.PUBLIC_ORIGIN
query = urllib.parse.parse_qs(urllib.parse.urlsplit(calls[3][3]).query)
assert query["X-Amz-Signature"][0] == "d6d49173319106adfde66b6e7aa4b441248935e6895c10ae7107f3a18474dc2d"
calls.clear()
retired_origin_allowed = True
with contextlib.redirect_stderr(io.StringIO()):
    try:
        smoke.main()
    except SystemExit as exc:
        assert exc.code == 1
    else:
        raise AssertionError("retired CORS origin was accepted")
print("minio presign smoke test: PASS")
PY
