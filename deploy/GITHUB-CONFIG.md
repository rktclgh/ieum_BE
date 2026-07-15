# GitHub Actions deployment configuration

## Frontend repository

The frontend repository must be readable without credentials by the isolated
`frontend-build` job. That job intentionally has no production Environment and
no repository or Environment secrets. If `rktclgh/ieum_FE` becomes private,
replace this boundary with a reviewed artifact-provenance flow; do not expose a
production token to frontend package scripts.

The following non-secret, browser-visible build values must be configured as
repository-level variables in the backend repository (not only as production
Environment variables), so the isolated build job can read them:

- `NEXT_PUBLIC_GOOGLE_CLIENT_ID`
- `NEXT_PUBLIC_KAKAO_REST_API_KEY`

Repository secrets:

- `CI_GITHUB_TOKEN`: fine-grained PAT targeting `rktclgh/ieum_BE` with
  repository Contents write permission, used only for `repository_dispatch`.

The `frontend-updated` dispatch must include the commit that produced the
frontend export in `client_payload.frontend_sha`. The value must be the full
40-character lowercase Git commit SHA. The isolated `frontend-build` job checks
out exactly that SHA and verifies it again after checkout. Backend push and
manual runs use the frontend `main` branch as their fallback source.

The build job runs `pnpm verify` without production credentials and uploads the
static export with its exact `frontend-sha` metadata through
`actions/upload-artifact@v4`. The immutable artifact ID, rather than a mutable
name or workspace path, is passed to the `deploy` job. The deploy job starts on
a clean runner, checks out only backend source, and downloads that exact
artifact ID. Before copying any bytes into Spring resources, backend-owned
checks reject hidden entries, symlinks, and non-file/non-directory entries,
compare the embedded SHA with the build job output, and run the static package
verifier. Frontend source, package scripts, Node, and pnpm never run inside the
production Environment.

The app-main deployment performs one final comparison with the current
frontend `main` SHA immediately before SSH deployment. A newer frontend commit
therefore makes an older run fail closed instead of publishing stale assets.
Production deployment concurrency uses `cancel-in-progress: false`, so an
in-flight migration or deployment is never interrupted by a newer run.

## Backend repository

Repository secrets:

- `CI_GITHUB_TOKEN`: fine-grained token with read access to
  `rktclgh/ieum_FE`, used only by the production `deploy` job for the final
  frontend `main` SHA freshness gate. It is never exposed to frontend checkout,
  install, build, or verification steps.
- `DOCKERHUB_USERNAME`: Docker Hub account or organization name.
- `DOCKERHUB_TOKEN`: Docker Hub access token with read/write permission.

Create private Docker Hub repositories named `ieum-app-main` and `ieum-app-ai`.

Create both GitHub Environments before enabling the workflows.

### `app-main-production`

Variables:

- `SSH_HOST=54.116.123.11`
- `SSH_USER=ubuntu`
- `SSH_PORT=22`
- `DEPLOY_PATH=/home/ubuntu/ieum/app-main`
- `APP_MAIN_PRIVATE_BIND_ADDRESS=172.31.38.97`
- `LETSENCRYPT_EMAIL`: optional when the origin certificate already exists;
  required only for initial Let's Encrypt issuance

Secrets:

- `SSH_PRIVATE_KEY`: complete PEM contents
- `SSH_KNOWN_HOSTS`: verified known_hosts line for `54.116.123.11`
- `APP_MAIN_ENV_FILE`: completed `deploy/env/app-main.env.example`; its
  `SPRING_DATASOURCE_URL` must use the production database host, never
  `localhost`, `127.0.0.1`, or the example placeholder, and it must include
  `INQUIRY_ADMIN_EMAIL`. Each deployment atomically updates the server's
  non-database runtime settings from this secret before running migrations.

### `app-ai-production`

Variables:

- `SSH_HOST=54.116.69.21`
- `SSH_USER=ubuntu`
- `SSH_PORT=22`
- `DEPLOY_PATH=/home/ubuntu/ieum/app-ai`
- `APP_AI_BIND_ADDRESS=172.31.33.42`

Secrets:

- `SSH_PRIVATE_KEY`: complete PEM contents
- `SSH_KNOWN_HOSTS`: verified known_hosts line for `54.116.69.21`
- `APP_AI_ENV_FILE`: completed `deploy/env/app-ai.env.example`. Each deployment
  atomically updates the server's non-database runtime settings from this
  secret before running migrations.

### Remote database migration gate

The production database is a private RDS instance whose port 5432 accepts
traffic only from the production EC2 security groups. GitHub-hosted runners
must not receive database credentials and cannot connect to that private RDS
endpoint. Each binary workflow therefore copies only the migration helper and
the explicitly ordered `v24_seed_report_policy_rules.sql`,
`v25_user_auth_version.sql`, `v26_admin_audit_logs.sql`, and
`v27_report_policy_sanction_durations.sql` files to its production EC2 host and
runs the helper there after the app-main JAR is built but before its image is deployed.
The app-ai workflow continues to run migrations before its binary is built. The report
policy files run only when the canonical `ai_report_policy_rules` table exists.

The database connection settings are retained from the current valid runtime
configuration. A `localhost` or `127.0.0.1` JDBC URL is rejected; when a stale
runtime file contains one, the workflow recovers the production datasource
settings from the running application container before updating the remaining
runtime keys and feature flags.

Before enabling either workflow, install the PostgreSQL client on both EC2
hosts. The migration helper reads the existing `$DEPLOY_PATH/.env.runtime`
file, which is also the runtime configuration consumed by the application. It
uses `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and
`SPRING_DATASOURCE_PASSWORD` to connect to the private RDS instance; no second
database-credential file is required. Keep `.env.runtime` owned by the SSH
deployment user and mode 600.

The workflow validates the runtime configuration and remote `psql` command
before running the helper. Credentials stay on EC2: they are never
interpolated as GitHub secrets and never appear in SSH arguments or workflow
logs. An unavailable `psql`, invalid datasource URL, schema mismatch, or
migration error stops the workflow before Gradle, image build, or SSH application
deployment.

The deployment validator also runs the helper against an ephemeral PostgreSQL
16 instance. It verifies that a hostile role `search_path` cannot redirect DDL,
an exact rerun preserves the existing users constraint OID, and incompatible
audit sequence properties fail closed before any repair is attempted.

Generate candidate host-key lines with `ssh-keyscan -H <host>`, but verify the
fingerprint through AWS or another trusted channel before saving the result as
`SSH_KNOWN_HOSTS`. The workflow never disables host-key verification.

The app-main workflow installs an Nginx reverse proxy for
`https://ieum.rktclgh.site` and keeps app-main reachable only through loopback
and its private address. Allow inbound ports 80 and 443 publicly. Allow port
8080 only from the app-ai security group or `172.31.33.42`; do not expose it to
the internet. On app-ai, allow port 8081 only from the app-main security group
or `172.31.38.97`. Restrict SSH port 22 to an administrator CIDR after initial
setup.

Before the first deployment, prepare the RDS schema/extensions, S3 and Bedrock
permissions, app-ai private-port security-group rule, and TLS. Deploy app-ai
first, then deploy app-main after the frontend static export and Spring static
serving changes are ready.
