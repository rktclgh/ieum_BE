#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

required_files=(
  deploy/app-main/Dockerfile
  deploy/app-main/compose.yml
  deploy/app-ai/Dockerfile
  deploy/app-ai/compose.yml
  deploy/scripts/apply-admin-dashboard-migrations.sh
  deploy/scripts/bootstrap-docker.sh
  deploy/scripts/configure-host-redis.sh
  deploy/scripts/configure-nginx.sh
  deploy/scripts/deploy-compose.sh
  deploy/tests/apply-admin-dashboard-migrations-test.sh
  deploy/tests/apply-admin-dashboard-migrations-postgres-test.sh
  deploy/tests/verify-static-frontend-package.sh
  deploy/tests/verify-static-frontend-package-test.sh
  deploy/tests/host-redis-deployment-config-test.sh
)

for file in "${required_files[@]}"; do
  test -s "$file" || { echo "missing: $file" >&2; exit 1; }
done

bash -n \
  deploy/scripts/apply-admin-dashboard-migrations.sh \
  deploy/scripts/bootstrap-docker.sh \
  deploy/scripts/configure-host-redis.sh \
  deploy/scripts/configure-nginx.sh \
  deploy/scripts/deploy-compose.sh \
  deploy/tests/apply-admin-dashboard-migrations-test.sh \
  deploy/tests/apply-admin-dashboard-migrations-postgres-test.sh \
  deploy/tests/verify-static-frontend-package.sh \
  deploy/tests/verify-static-frontend-package-test.sh \
  deploy/tests/host-redis-deployment-config-test.sh

# These tests execute the deployment-critical paths; do not assert workflow
# implementation details such as step names or Gradle command strings here.
bash deploy/tests/apply-admin-dashboard-migrations-test.sh
bash deploy/tests/apply-admin-dashboard-migrations-postgres-test.sh
bash deploy/tests/verify-static-frontend-package-test.sh
bash deploy/tests/host-redis-deployment-config-test.sh

echo "Deployment config validation passed."
