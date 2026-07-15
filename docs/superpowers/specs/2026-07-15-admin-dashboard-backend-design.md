# Admin Dashboard Backend Design

## 1. Outcome

The existing administrator APIs remain the MVP contract. This change supplies the missing backend integration and security foundation for a fixed-route desktop admin frontend:

- expose the canonical `role` from `GET /api/v1/users/me`;
- make database state, not Redis cleanup success, authoritative for every authenticated request and refresh;
- prevent self-demotion and concurrent removal of the final administrator;
- append an audit row in the same transaction as every MVP administrator mutation;
- serve the fixed admin frontend routes from the Spring Boot static package;
- reconcile the shared API specification during final integration.

Content deletion, permanent user deletion, pending AI-sanction review, AI policy/knowledge administration, audit-log browsing, MFA, HTTP request-correlation infrastructure, and scheduler restructuring are outside this slice.

## 2. Existing Contracts Preserved

The following paths and status semantics do not change:

- `GET /api/v1/admin/users`, `GET /api/v1/admin/users/{userId}`
- `POST /api/v1/admin/users/{userId}/sanctions` (`201`)
- `POST /api/v1/admin/users/{userId}/activate` (`204`)
- `PATCH /api/v1/admin/users/{userId}/role` (`204`)
- `GET /api/v1/admin/reports`, `GET /api/v1/admin/reports/{reportId}`
- `POST /api/v1/admin/reports/{reportId}/confirm` (`204`)
- `POST /api/v1/admin/reports/{reportId}/dismiss` (`204`)
- `GET /api/v1/admin/inquiries`, `POST /api/v1/admin/inquiries/{inquiryId}/answer`
- the three `GET /api/v1/admin/stats/*` endpoints.

`POST /api/v1/auth/login` remains the only login endpoint. There is no separate `/api/v1/admin/login`; the frontend logs in normally and then requires `role=admin`.

## 3. Durable Session Generation

### Problem

Sanction and role-change transactions currently commit to PostgreSQL and then try to delete Redis sessions. Cleanup failures are deliberately swallowed. `SessionTokenValidator` and `RefreshService` currently trust the stale role/status snapshot in Redis, so a sanctioned user or demoted administrator can remain authorized and can extend the stale session.

### Decision

Add `users.auth_version BIGINT NOT NULL DEFAULT 0`. `User.suspend()`, `activate()`, `changeRole()`, and `markDeleted()` increment it only when canonical authorization state changes. The native report-dismiss activation SQL increments the same column atomically. `User` uses Hibernate `@DynamicUpdate` so a non-authorization write from a stale entity cannot overwrite newer role, status, or generation fields; a canonical PostgreSQL two-transaction test locks this behavior. New `AuthSession` values copy that version at login.

Every access-token validation and refresh performs both checks:

1. JWT and Redis session identity fields match.
2. A PostgreSQL `UserAuthState(email, role, status, authVersion)` row exists for the non-deleted user and exactly matches the Redis session, with `status=active`.

Redis revocation remains best-effort cleanup. Once PostgreSQL commits a new version, an old session cannot authenticate or refresh even when Redis is unavailable during cleanup. A legacy Redis session with absent, blank, or malformed `authVersion` fails closed and requires login. The access JWT does not need a version claim because possession of the JWT is already coupled to a Redis session, and the Redis generation is compared with PostgreSQL on every use.

### Migration and operational effect

- `db/migrations/v25_user_auth_version.sql` adds and backfills version `0` without rewriting application identities.
- `db/schema.sql` is updated for clean databases and canonical integration tests.
- Deployment invalidates all pre-deployment Redis sessions because they lack `authVersion`; this is an intentional one-time logout.
- Authenticated HTTP and WebSocket-handshake validation gain one indexed primary-key lookup. Established chat connections repeat the same canonical check on every inbound `SUBSCRIBE` and `SEND`, and the sharded SSE heartbeat closes a connection whose Redis generation no longer matches PostgreSQL.
- A Redis or PostgreSQL exception while checking an established SSE connection closes that connection and does not stop validation of the remaining shard. WebSocket validation failures reject the current frame before membership or rate-limit work.
- There is no outbound STOMP revalidation or WebSocket-session registry in this slice. A previously subscribed socket that sends no new frame may continue passively receiving broker messages until disconnect; this limitation is documented and must not be described as full real-time revocation.

## 4. Canonical Role Response

`UserMeResponse` adds `role: UserRole`, populated from the freshly loaded `User`. This is an additive response change. The admin frontend uses it for routing and presentation, while `/api/v1/admin/**` Spring Security rules remain the authorization boundary.

## 5. Administrator Role Invariants

All role writes serialize through `UserRepository.findAllAdminsForUpdate()`, which locks non-deleted administrator rows in ascending `user_id` order before the target is resolved.

- An administrator cannot demote their own account: `409 CANNOT_CHANGE_OWN_ROLE`.
- The final non-deleted administrator cannot be demoted: `409 LAST_ADMIN_REQUIRED`.
- An administrator cannot bypass those rules through the ordinary self-withdrawal endpoint: `409 ADMIN_WITHDRAWAL_FORBIDDEN`. Another administrator must first demote the account after ensuring a different administrator remains.
- Promotions and demotions still revoke Redis sessions after commit, but correctness comes from `auth_version`.
- Lock ordering is shared by every role change, preventing two concurrent demotions from both observing two administrators and committing zero.

Initial administrator provisioning remains an operations responsibility; this slice does not add a public bootstrap endpoint.

The repository has no Flyway/Liquibase runtime. `deploy/scripts/apply-admin-dashboard-migrations.sh` therefore applies transactional `v25_user_auth_version.sql` and then transactional `v26_admin_audit_logs.sql` with `psql ON_ERROR_STOP`, verifies both schemas, and exits nonzero on any mismatch. Production runs this helper before draining old instances and deploying the new binaries. Code deployment before those DDL changes is forbidden.

## 6. Append-only Administrator Audit

`db/migrations/v26_admin_audit_logs.sql` and `db/schema.sql` define `admin_audit_logs` with:

- `audit_id`, nullable `actor_user_id` (`ON DELETE SET NULL`), `action`, `target_type`, `target_id`;
- object-valued `details JSONB` containing bounded before/after facts;
- `created_at` and indexes for actor, target, and reverse chronological inspection.

`AdminAuditLogWriter` exposes only `append(...)`; no application update/delete API exists. The writer runs in the caller transaction, so a business mutation cannot commit without its audit row. The MVP action set is:

- `USER_SANCTION_CREATED`, `USER_ACTIVATED`, `USER_ROLE_CHANGED`
- `REPORT_CONFIRMED`, `REPORT_DISMISSED`
- `INQUIRY_ANSWERED`

Audit details store sanction reason/type/end, changed role/status, report decision, and answer length. They do not duplicate inquiry answer text, report evidence, tokens, cookies, or profile PII. Idempotent replays that do not change domain state do not create duplicate audit rows.

## 7. Static Admin Routes

Spring forwards both slash variants of these fixed paths to their exported `index.html` files:

- `/admin`, `/admin/login`
- `/admin/users`, `/admin/users/detail`
- `/admin/reports`, `/admin/reports/detail`
- `/admin/inquiries`

Detail identifiers remain query parameters. The same route reconciliation replaces the stale `/my/settings` allowlist entry with the implemented `/my/inquiry`, `/my/notifications`, and `/my/permissions` pages. `StaticPageControllerTest`, `StaticFrontendHttpIntegrationTest`, and `deploy/tests/verify-static-frontend-package.sh` cover forwarding, live HTTP serving, HTML/RSC presence, and boot-JAR packaging.

## 8. Documentation Boundary

The shared `/Users/songchiho/Desktop/Hackerthon/code/api/API-SPEC.md` is outside this backend worktree and is not edited on this branch. Final integration must:

- add `role` to `GET /users/me`;
- identify `/auth/login` as the canonical admin login;
- mark the current admin user/report/inquiry/stats APIs AS-BUILT, including `PATCH .../role`;
- retain content deletion, permanent deletion, pending-review, and AI admin APIs as TARGET;
- document all three new `409` errors (`CANNOT_CHANGE_OWN_ROLE`, `LAST_ADMIN_REQUIRED`, `ADMIN_WITHDRAWAL_FORBIDDEN`) and the auth-version logout behavior.

## 9. Verification

Targeted tests prove response shape, legacy-session rejection, DB/Redis mismatch rejection, refresh/reuse ordering, concurrent last-admin safety, JPA-plus-JDBC transactional audit rollback, admin security/CSRF behavior, migration-helper ordering, and static route packaging. Final verification runs `./gradlew clean test`, builds both `app-main` and `app-ai` boot JARs, and runs the static package verifier against a real frontend export and `app-main` boot JAR. Smoke data is created only inside disposable test databases and temporary directories.
