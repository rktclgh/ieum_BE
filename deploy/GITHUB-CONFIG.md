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
name or workspace path, is passed to the GitHub-hosted `build-sign` job. That
job starts on a clean runner, checks out only backend source, and downloads
that exact artifact ID. Before copying any bytes into Spring resources, backend-owned
checks reject hidden entries, symlinks, and non-file/non-directory entries,
compare the embedded SHA with the build job output, and run the static package
verifier. Frontend source, package scripts, Node, and pnpm never run inside the
production Environment.

The app-main release performs one final comparison with the current frontend
`main` SHA immediately before signing. A newer frontend commit therefore makes
an older run fail closed instead of publishing stale assets.
Production deployment concurrency uses `cancel-in-progress: false`, so an
in-flight migration or deployment is never interrupted by a newer run.

## Backend repository

Repository secrets:

- `DOCKERHUB_USERNAME`: Docker Hub account or organization name.
- `DOCKERHUB_TOKEN`: Docker Hub access token with read/write permission.

Create private Docker Hub repositories named `ieum-app-main` and `ieum-app-ai`.

## On-premises production target

The legacy AWS EC2 configuration is retired. Do not put an EC2 public address,
an EC2 private address, an RDS hostname, or any runner-local address in this
repository, a workflow, or a committed environment file.

The `ieum-production` GitHub Environment is restricted by a custom deployment
branch policy to `main` and has one deployment secret:

- `RELEASE_SIGNING_PRIVATE_KEY`: protected SSH signing key used only to create
  a detached signature for the exact release tarball. Its public verifier is
  root-owned on the target. It is not an SSH transport key.

There are no `SSH_HOST`, `SSH_USER`, `SSH_PORT`,
`ONPREM_RELEASE_SSH_PRIVATE_KEY`, or `ONPREM_RELEASE_SSH_KNOWN_HOSTS` variables
or secrets. The on-premises host cannot accept an Internet SSH connection, and
the release workflow intentionally has no inbound SSH, SCP, NAT, or
port-forwarding transport.

Application runtime secrets never belong to GitHub. Keep them only in the
root-owned, mode-0600 server files `/etc/ieum/app-main.env` and
`/etc/ieum/app-ai.env`. In particular, do not configure `APP_MAIN_ENV_FILE`,
`APP_AI_ENV_FILE`, application database credentials, MinIO credentials, or
Bedrock credentials as GitHub Environment secrets. `DEPLOY_PATH` is also not a
GitHub variable: the root-owned dispatcher fixes the staging, release, and
state paths on the target.

### Runner model

The production host runs one dedicated **repository-scoped** self-hosted
runner. GitHub-hosted jobs build, test, publish images, and sign the envelope;
the local runner performs only `local-plan` and `local-apply`. This works with
outbound HTTPS from the host to GitHub and requires no externally reachable SSH
service.

- Unix user: `ieum-runner`
- runner name: `song-server-ieum-prod-01`
- required labels: `self-hosted`, `linux`, `x64`, `ieum-prod-deploy`
- repository URL: `https://github.com/rktclgh/ieum_BE`

Never reuse the existing FairPlay or Vlainter runners: they run as `song`,
which has Docker and sudo privileges. `ieum-runner` must not be in the Docker
or sudo groups, must not read `/etc/ieum/*.env`, and must not check out this
repository. Its only elevated path is the root-owned dispatcher:

```text
sudo -n /usr/local/sbin/ieum-release-dispatch --local current --json
sudo -n /usr/local/sbin/ieum-release-dispatch --local apply ...
```

The dispatcher accepts only strict `current` and signed-envelope `apply`
arguments; it does not grant direct access to `ieum-deploy-release`, Docker,
or a shell. Root operators retain rollback outside GitHub Actions.

Bootstrap the account and dispatcher first, then register the runner with a
short-lived token from **Repository Settings → Actions → Runners**. Put the
token in a root-owned mode-0600 file on the server and pass that file only to
`install-self-hosted-runner.sh`; do not paste it into repository secrets,
workflow YAML, or a shell history. The installer configures the runner as
`ieum-runner`, starts its own systemd service, and removes the token file only
after successful registration. GitHub's unattended runner client requires its
temporary registration token while it configures; it is never printed by this
installer.

This is deliberately not a generic runner for other repositories. Under the
current personal-account ownership it is repository-scoped. Sharing a runner
requires moving the repository to an organization and designing a separate,
workflow-restricted runner-group policy.

The workflow uses one literal production concurrency group across app-main and
app-ai, while the server-side wrapper owns the final host-wide lock.

### Local database migration gate

The production database is the on-premises PostgreSQL instance on the same
host as the applications. The application runtime uses the Docker gateway
hostname, not a literal host address. The deployment wrapper reads the
root-owned runtime environment files without printing them, runs the reviewed
migration bundle once against production database `ieum`, and aborts before
starting app-main if migration or postcondition checks fail.

The final host has one Ieum application database named `ieum`. A disposable
`ieum_rehearsal` database may exist only during the isolated restore/application
rehearsal and must be dropped before the production write cutover.

The workflow must not receive database credentials. A PostgreSQL client,
extension preflight, migration advisory lock, exact rerun check, and schema
postcondition checks run on the target host. Credentials must never appear in
runner command arguments, workflow logs, image labels, or release artifacts.

### Network and TLS gate

Nginx serves `https://ieum.rktclgh.site` and the separate public file hostname
`https://files.rktclgh.site`. It proxies app-main only to its loopback host
port and MinIO only to its loopback API port. PostgreSQL, Redis, app-main,
app-ai, the MinIO API, and the MinIO console are never public.

Before the first production release, complete the PostgreSQL extension/data
restore rehearsal, MinIO bucket/policy/CORS rehearsal, Bedrock preflight, and
TLS validation. Start app-ai before app-main; fail the release if either the
internal health checks or the public anonymous API check fails.
