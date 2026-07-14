# CI/CD Pipeline Design

## Problem statement

Issue `rktclgh/ieum_BE#72` needs repeatable deployment from GitHub Actions to two EC2 instances:

- `app-main`: public IP `54.116.123.11`, private IP `172.31.38.97`, Spring port `8080`
- `app-ai`: public IP `54.116.69.21`, private IP `172.31.33.42`, Spring port `8081`
- frontend repository: `rktclgh/Vivisa_Plus_FE`
- backend repository: `rktclgh/ieum_BE`

Both repositories release from `main`. The frontend must eventually be exported as static files and packaged inside the `app-main` boot jar. Deployment uses GitHub-hosted runners, immutable container images, Docker Compose on each EC2 instance, and PEM-based SSH.

## Scope

- Add a frontend workflow that validates the frontend and dispatches the exact frontend commit SHA to the backend repository.
- Add independent backend workflows for `app-main` and `app-ai`.
- Build and publish immutable images to GHCR.
- Deploy each service over SSH with Docker Compose, health checks, and rollback to the previously running image.
- Bootstrap Docker Engine and the Compose plugin idempotently because neither EC2 currently has Docker installed.
- Add production environment-file templates containing every runtime key required by the current Spring configuration.
- Document GitHub Environment variables and secrets.

## Non-scope

- Converting the current Next.js application to static export. The present application uses runtime rewrites, proxy middleware, server-side cookies, and unbounded dynamic routes, so `out/` cannot be produced without a separate frontend routing/authentication migration.
- Provisioning DNS, TLS, RDS, S3, IAM roles, or EC2 security groups.
- Running database migrations. The repository has SQL files but no established Flyway/Liquibase release owner.

The `app-main` workflow therefore treats `frontend/out/index.html` as a mandatory release artifact and stops before image publication when the frontend has not yet completed its static-export migration. It never copies `.next` into Spring resources.

## Approaches considered

### Service-specific workflows (selected)

`deploy-app-main.yml` and `deploy-app-ai.yml` own separate build, deploy, concurrency, environment, and rollback histories. Frontend dispatch triggers only `app-main`; changes to `common` trigger both services. This matches the backend module boundary and isolates failures.

### One release orchestrator

A single workflow can enforce a strict `app-ai -> app-main` order, but one service failure blocks the other and independent reruns are less clear. This is only preferable if future schema changes make mixed service versions unsafe.

### CI/CD split with promotion manifests

Building images once and promoting digests gives the strongest audit trail, but requires a release manifest and retention policy. It is unnecessary for the initial hackathon deployment.

## Selected architecture

### Frontend repository

On `main` push:

1. Install the pinned pnpm version and Node.js.
2. Run lint and build.
3. Call the backend `repository_dispatch` endpoint with event type `frontend-updated` and payload fields `fe_sha`, `fe_ref`, and `fe_repo`.

The cross-repository token is a fine-grained PAT stored as `CI_GITHUB_TOKEN`. The backend checks out `fe_sha`, not `fe_ref`, to eliminate a race with later frontend pushes.

### Backend app-main workflow

Triggers:

- backend `main` push affecting `app-main`, `common`, root Gradle files, or deployment files
- `repository_dispatch` event `frontend-updated`
- manual dispatch with an optional frontend ref

Flow:

1. Check out backend and the selected frontend commit.
2. Build frontend with pnpm and require `out/index.html`.
3. Replace `app-main/src/main/resources/static` with `frontend/out`.
4. Run `:app-main:test` and `:app-main:bootJar`.
5. Build and push `ghcr.io/rktclgh/ieum-app-main:<backend-sha>-fe-<frontend-sha>`.
6. Copy Compose and deployment scripts to `54.116.123.11` over SSH.
7. Atomically write the `APP_MAIN_ENV_FILE` GitHub Environment secret to `.env.runtime` with mode `0600`.
8. Pull and start the image, poll `http://127.0.0.1:8080/actuator/health`, and restore the previous image on failure.

The app-main Compose project also runs a private Redis container with a persistent named volume.

### Backend app-ai workflow

Triggers:

- backend `main` push affecting `app-ai`, `common`, root Gradle files, or deployment files
- manual dispatch

Flow mirrors app-main but builds `:app-ai:test` and `:app-ai:bootJar`, publishes `ghcr.io/rktclgh/ieum-app-ai:<backend-sha>`, writes `APP_AI_ENV_FILE`, deploys to `54.116.69.21`, and checks `http://127.0.0.1:8081/actuator/health`.

### Runtime networking

- app-main calls app-ai at `http://172.31.33.42:8081`.
- app-ai callbacks call app-main at `http://172.31.38.97:8080`.
- Both services use the same `APP_AI_INTERNAL_CALLBACK_TOKEN` value.
- Port `8081` must accept traffic only from the app-main security group/private address. It must not be internet-accessible.
- Public SSH `0.0.0.0/0` is temporary. The workflow pins each EC2 host key and never uses `StrictHostKeyChecking=no`.

## Secrets and configuration

GitHub Environments separate deployment authority:

- `app-main-production`
- `app-ai-production`

Each environment owns `SSH_PRIVATE_KEY`, `SSH_KNOWN_HOSTS`, `GHCR_USERNAME`, `GHCR_TOKEN`, and its service-specific multiline runtime file (`APP_MAIN_ENV_FILE` or `APP_AI_ENV_FILE`). Non-sensitive host, user, port, and deployment path values are Environment variables.

The frontend repository owns `CI_GITHUB_TOKEN`. The backend repository owns the same token for private frontend checkout plus frontend public client identifiers used at build time.

Runtime secrets are never added to the image, artifact, Compose file, or Git history. The complete secret and variable lineup is maintained in `deploy/GITHUB-CONFIG.md`.

## Error handling and rollback

- Build/test/static-export failures stop before image publication.
- Server deployments use separate concurrency groups with `cancel-in-progress: false`, so an active Compose replacement is not cancelled mid-flight.
- The deploy script records the currently running image before replacement.
- Health failure restores the prior immutable image. A failed first deployment is stopped and returns a non-zero status.
- Docker bootstrap is idempotent and runs only when Docker/Compose is missing.

## Verification

- Parse all workflow and Compose YAML files.
- Run `bash -n` on deployment scripts.
- Run a repository-local deployment contract test that verifies triggers, immutable tags, `out/index.html` gating, SSH host-key checking, environment-file permissions, Compose health checks, and rollback wiring.
- Run frontend lint/build as a baseline check; the current build may succeed as a Next runtime build but is not considered static-release ready until `out/index.html` exists.
- Run targeted backend tests and boot-jar builds for both modules.

## Acceptance criteria

- Frontend `main` dispatches its exact SHA to backend issue #72's app-main pipeline.
- app-main and app-ai can be triggered, queued, deployed, and rolled back independently.
- `common` changes schedule both deployments.
- app-main image creation is impossible without a real Next static export.
- Each EC2 receives only its own runtime environment file and image.
- No workflow disables SSH host-key verification or logs a secret.
- All added YAML and shell files pass local syntax/contract validation.

