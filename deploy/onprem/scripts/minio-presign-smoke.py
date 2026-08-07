#!/usr/bin/env python3
"""Root-only, secret-safe MinIO browser presign/CORS smoke test."""
import datetime as dt
import hashlib
import http.client
import hmac
import os
import secrets
import ssl
import stat
import sys
import urllib.parse

USER_AGENT = "Mozilla/5.0 IeumOnpremPresignSmoke/1.0"


def fail(message):
    print(f"ieum minio presign smoke: {message}", file=sys.stderr)
    raise SystemExit(1)


def private_file(path):
    try:
        info = os.lstat(path)
    except OSError:
        return False
    return stat.S_ISREG(info.st_mode) and not stat.S_ISLNK(info.st_mode) and info.st_uid == os.geteuid() and stat.S_IMODE(info.st_mode) == 0o600


def read_env(path):
    if not private_file(path):
        fail("app-main runtime env is unsafe")
    values = {}
    try:
        lines = open(path, encoding="utf-8").read().splitlines()
    except (OSError, UnicodeError):
        fail("unable to read app-main runtime env")
    for line in lines:
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail("app-main runtime env has an invalid line")
        key, value = line.split("=", 1)
        if not key or not key.replace("_", "a").isalnum() or not key[0].isalpha() or key in values:
            fail("app-main runtime env has an invalid key set")
        values[key] = value
    required = ("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_S3_BUCKET", "AWS_S3_PRESIGN_ENDPOINT", "AWS_S3_REGION")
    if any(not values.get(key) for key in required):
        fail("required MinIO settings are missing")
    if values["AWS_S3_BUCKET"] != "ieum-files" or values["AWS_S3_REGION"] != "us-east-1":
        fail("MinIO bucket or signing region is invalid")
    parsed = urllib.parse.urlsplit(values["AWS_S3_PRESIGN_ENDPOINT"])
    if parsed.scheme != "https" or parsed.netloc != "files.rktclgh.site" or parsed.path not in ("", "/") or parsed.query or parsed.fragment or parsed.username:
        fail("public MinIO endpoint is invalid")
    if "\n" in values["AWS_SECRET_ACCESS_KEY"] or "\r" in values["AWS_SECRET_ACCESS_KEY"]:
        fail("MinIO secret contains an invalid character")
    return values, parsed._replace(path="").geturl().rstrip("/")


def signing_key(secret, date, region, service):
    def sign(key, value):
        return hmac.new(key, value.encode(), hashlib.sha256).digest()
    return sign(sign(sign(sign(("AWS4" + secret).encode(), date), region), service), "aws4_request")


def presigned(base, bucket, key, access, secret, region, method):
    now = dt.datetime.now(dt.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    short_date = amz_date[:8]
    host = urllib.parse.urlsplit(base).netloc
    path = "/" + urllib.parse.quote(bucket + "/" + key, safe="/-_.~")
    credential = f"{access}/{short_date}/{region}/s3/aws4_request"
    query = {
        "X-Amz-Algorithm": "AWS4-HMAC-SHA256",
        "X-Amz-Credential": credential,
        "X-Amz-Date": amz_date,
        "X-Amz-Expires": "300",
        "X-Amz-SignedHeaders": "host",
    }
    canonical_query = urllib.parse.urlencode(sorted(query.items()), safe="-_.~")
    canonical_headers = f"host:{host}\n"
    payload_hash = "UNSIGNED-PAYLOAD"
    canonical_request = "\n".join((method, path, canonical_query, canonical_headers, "host", payload_hash))
    scope = f"{short_date}/{region}/s3/aws4_request"
    string_to_sign = "\n".join(("AWS4-HMAC-SHA256", amz_date, scope, hashlib.sha256(canonical_request.encode()).hexdigest()))
    signature = hmac.new(signing_key(secret, short_date, region, "s3"), string_to_sign.encode(), hashlib.sha256).hexdigest()
    query["X-Amz-Signature"] = signature
    return urllib.parse.urlunsplit(("https", host, path, urllib.parse.urlencode(sorted(query.items()), safe="-_.~"), ""))


def request(url, method, headers=None, body=None, expected_statuses=()):
    parsed = urllib.parse.urlsplit(url)
    try:
        connection = http.client.HTTPSConnection(parsed.netloc, timeout=15, context=ssl.create_default_context())
        request_headers = {"User-Agent": USER_AGENT, "Accept": "*/*"}
        request_headers.update(headers or {})
        connection.request(method, urllib.parse.urlunsplit(("", "", parsed.path, parsed.query, "")), body=body, headers=request_headers)
        response = connection.getresponse()
        status = response.status
        response_headers = {key.lower(): value for key, value in response.getheaders()}
        response_body = response.read()
        connection.close()
        if status >= 400 and status not in expected_statuses:
            safe_code = response_headers.get("x-minio-error-code", response_headers.get("x-amz-error-code", "unknown"))
            safe_server = response_headers.get("server", "unknown")
            raise RuntimeError(f"{method} returned HTTP {status} ({safe_code}, {safe_server})")
        return status, response_headers, response_body
    except RuntimeError:
        raise
    except OSError as exc:
        raise RuntimeError(f"{method} request failed") from exc


def main():
    if os.geteuid() != 0 and os.environ.get("IEUM_MINIO_PRESIGN_SMOKE_TEST_MODE") != "1":
        fail("must run as root")
    env_path = os.environ.get("IEUM_MINIO_PRESIGN_SMOKE_ENV_FILE", "/etc/ieum/app-main.env")
    if not os.path.isabs(env_path):
        fail("environment path must be absolute")
    values, base = read_env(env_path)
    key = f"tmp/presign-smoke-{secrets.token_hex(16)}.txt"
    body = b"ieum-minio-presign-smoke\n"
    urls = {method: presigned(base, values["AWS_S3_BUCKET"], key, values["AWS_ACCESS_KEY_ID"], values["AWS_SECRET_ACCESS_KEY"], values["AWS_S3_REGION"], method) for method in ("PUT", "GET", "HEAD", "DELETE")}
    try:
        for origin in ("https://ieum1.rktclgh.site", "https://ieum.rktclgh.site"):
            for method, requested_headers in (("PUT", "content-type"), ("DELETE", "")):
                preflight_headers = {
                    "Origin": origin,
                    "Access-Control-Request-Method": method,
                    "User-Agent": USER_AGENT,
                }
                if requested_headers:
                    preflight_headers["Access-Control-Request-Headers"] = requested_headers
                status, headers, _ = request(urls[method], "OPTIONS", preflight_headers)
                allow_origin = headers.get("access-control-allow-origin", "")
                allow_methods = {item.strip().upper() for item in headers.get("access-control-allow-methods", "").split(",")}
                allow_headers = {item.strip().lower() for item in headers.get("access-control-allow-headers", "").split(",")}
                if status < 200 or status >= 300 or allow_origin != origin or method not in allow_methods:
                    raise RuntimeError("CORS preflight response was invalid")
                if requested_headers and "*" not in allow_headers and requested_headers not in allow_headers:
                    raise RuntimeError("CORS preflight did not allow the requested headers")
        request(urls["PUT"], "PUT", {"Content-Type": "application/octet-stream", "Origin": "https://ieum1.rktclgh.site", "User-Agent": USER_AGENT}, body)
        _, _, get_body = request(urls["GET"], "GET", {"Origin": "https://ieum1.rktclgh.site", "User-Agent": USER_AGENT})
        if get_body != body:
            raise RuntimeError("GET body did not match the fixture")
        # Community MinIO applies CORS only to browser cross-origin operations;
        # validate the signed HEAD route without an Origin header. Browser CORS
        # itself is covered by the two explicit preflight checks above.
        head_status, head_headers, head_body = request(urls["HEAD"], "HEAD", {"User-Agent": USER_AGENT})
        if head_status < 200 or head_status >= 300 or head_body or head_headers.get("content-length") != str(len(body)):
            raise RuntimeError("HEAD response did not match the fixture")
        request(urls["DELETE"], "DELETE", {"Origin": "https://ieum1.rktclgh.site", "User-Agent": USER_AGENT})
        deleted_status, _, _ = request(urls["GET"], "GET", {"Origin": "https://ieum1.rktclgh.site", "User-Agent": USER_AGENT}, expected_statuses=(404,))
        if deleted_status != 404:
            raise RuntimeError("deleted fixture remained readable")
    except RuntimeError as exc:
        fail(str(exc))
    finally:
        try:
            request(urls["DELETE"], "DELETE", {"User-Agent": USER_AGENT})
        except RuntimeError:
            pass
    print("ieum minio presign smoke: CORS PUT GET HEAD DELETE passed")


if __name__ == "__main__":
    main()
