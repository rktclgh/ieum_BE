# GitHub Actions deployment configuration

## Frontend repository

Repository variables:

- `NEXT_PUBLIC_GOOGLE_CLIENT_ID`
- `NEXT_PUBLIC_KAKAO_REST_API_KEY`

Repository secrets:

- `CI_GITHUB_TOKEN`: fine-grained PAT targeting `rktclgh/ieum_BE` with
  repository Contents write permission, used only for `repository_dispatch`.

## Backend repository

Repository secrets:

- `CI_GITHUB_TOKEN`: token that can read `rktclgh/ieum_FE` when the
  frontend repository is private.
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
- `LETSENCRYPT_EMAIL`: certificate expiry and recovery notices
- `NEXT_PUBLIC_GOOGLE_CLIENT_ID`
- `NEXT_PUBLIC_KAKAO_REST_API_KEY`

Secrets:

- `SSH_PRIVATE_KEY`: complete PEM contents
- `SSH_KNOWN_HOSTS`: verified known_hosts line for `54.116.123.11`
- `APP_MAIN_ENV_FILE`: completed `deploy/env/app-main.env.example`

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
- `APP_AI_ENV_FILE`: completed `deploy/env/app-ai.env.example`

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
