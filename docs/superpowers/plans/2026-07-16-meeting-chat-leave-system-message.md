# 모임 채팅방 나가기와 이탈 시스템 메시지 (#145) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 모임 일반 참여자의 자진 나가기와 방장 강퇴의 정본 이탈 트랜잭션에 영속 system 메시지와 after-commit room event를 추가해, 프론트가 두 이탈 이력과 실시간 알림을 같은 계약으로 표시할 수 있게 한다.

**Architecture:** `MessageType.user|system`을 additive DB column으로 추가하고 system 행도 떠난 사용자를 sender로 보존한다. `MeetingService.leave`와 `MeetingService.kick`은 대상 participant 비관 잠금 뒤 같은 group lifecycle 메서드를 호출하며, chat 경계가 chat member 이탈·system row 저장·목록 delta·after-commit STOMP 발행을 책임진다. 일반 채팅의 push 흐름과 group chat leave의 409 보호 계약은 그대로 둔다.

**Tech Stack:** Java 21, Spring Boot 4, Spring MVC, Spring Data JPA, PostgreSQL, STOMP, JUnit 5, Mockito, Testcontainers, Gradle, Bash.

## Global Constraints

- Base branch is `develop`; this worktree branch is `145-fix-meeting-chat-leave-message`.
- Do not modify unrelated untracked changes in the primary worktree.
- Keep `POST /api/v1/chat/rooms/{roomId}/leave` returning `409 GROUP_LEAVE_VIA_MEETING` for group rooms.
- Use only additive DB DDL; do not delete, rewrite, or recreate existing messages.
- System rows retain a non-null sender, have `messageType=system`, nonblank Korean content, and no image.
- Do not emit web push or create a system message for rejoin/reinvite flows.
- Both successful voluntary leave and successful host kick persist the same neutral content: `{nickname}님이 모임을 떠났습니다`.
- Every behavior change follows RED → minimal implementation → focused GREEN → Korean commit.
- Do not run the production migration manually; verify scripts locally and leave production application to CI/CD.
- Local API SSOT is updated before the corresponding Notion API pages; Notion becomes `구현완료` only after code and verification agree.

---

## File Map

- `common/src/main/java/shinhan/fibri/ieum/common/chat/domain/MessageType.java` — new `user|system` enum.
- `common/src/main/java/shinhan/fibri/ieum/common/chat/domain/Message.java` — persistent type and `system` factory.
- `db/schema.sql` — canonical messages schema.
- `db/migrations/v28_chat_system_messages.sql` — production additive migration.
- `app-main/src/test/java/shinhan/fibri/ieum/main/chat/db/V28ChatSystemMessagesMigrationIntegrationTest.java` — canonical/migration compatibility.
- `deploy/scripts/apply-admin-dashboard-migrations.sh` — v28 preflight, apply, exact verification.
- `deploy/tests/apply-admin-dashboard-migrations-test.sh` — helper contract assertions.
- `deploy/tests/apply-admin-dashboard-migrations-postgres-test.sh` — v28 idempotence in PostgreSQL.
- `.github/workflows/deploy-app-main.yml`, `.github/workflows/deploy-app-ai.yml` — transfer/chmod v28 migration file.
- `app-main/src/main/java/shinhan/fibri/ieum/main/chat/dto/ChatMessageResponse.java` — API type field.
- `app-main/src/main/java/shinhan/fibri/ieum/main/chat/service/WsMessageEvent.java` — STOMP type field and common factory.
- `app-main/src/main/java/shinhan/fibri/ieum/main/chat/service/ChatSystemMessageService.java` — system row + list delta + after-commit room event.
- `app-main/src/main/java/shinhan/fibri/ieum/main/chat/service/ChatRoomLifecycle.java` — leave/kick common group-departure seam.
- `app-main/src/main/java/shinhan/fibri/ieum/main/chat/service/ChatRoomLifecycleService.java` — group member transition orchestration.
- `app-main/src/main/java/shinhan/fibri/ieum/main/meeting/repository/MeetingParticipantRepository.java` — participant `PESSIMISTIC_WRITE` lookup for leave/kick.
- `app-main/src/main/java/shinhan/fibri/ieum/main/meeting/service/MeetingService.java` — call the new lifecycle seam from voluntary leave and kick.
- `app-main/src/test/java/shinhan/fibri/ieum/main/chat/service/ChatSystemMessageServiceTest.java` — persistence and after-commit event tests.
- `app-main/src/test/java/shinhan/fibri/ieum/main/chat/service/ChatRoomLifecycleServiceTest.java` — lifecycle delegation and failure coverage.
- `app-main/src/test/java/shinhan/fibri/ieum/main/meeting/service/MeetingServiceTest.java` — canonical leave/kick path regression.
- `app-main/src/test/java/shinhan/fibri/ieum/main/chat/websocket/ChatWebSocketIntegrationTest.java` — actual STOMP payload contract.
- workspace `/Users/songchiho/Desktop/Hackerthon/code/api/API-SPEC.md` — public API SSOT; it is outside this BE worktree and is not staged by the BE PR.

---

### Task 1: Add the additive `messages.message_type` database contract

**Files:**
- Create: `common/src/main/java/shinhan/fibri/ieum/common/chat/domain/MessageType.java`
- Modify: `common/src/main/java/shinhan/fibri/ieum/common/chat/domain/Message.java`
- Modify: `db/schema.sql`
- Create: `db/migrations/v28_chat_system_messages.sql`
- Create: `app-main/src/test/java/shinhan/fibri/ieum/main/chat/db/V28ChatSystemMessagesMigrationIntegrationTest.java`
- Modify: `deploy/scripts/apply-admin-dashboard-migrations.sh`
- Modify: `deploy/tests/apply-admin-dashboard-migrations-test.sh`
- Modify: `deploy/tests/apply-admin-dashboard-migrations-postgres-test.sh`
- Modify: `.github/workflows/deploy-app-main.yml`
- Modify: `.github/workflows/deploy-app-ai.yml`

**Interfaces:**
- Produces: `MessageType.user`, `MessageType.system` and a DB column `messages.message_type VARCHAR(16) NOT NULL DEFAULT 'user'`.
- Preserves: every existing message row and the existing non-null `sender_id` / content-or-image constraint.

- [ ] **Step 1: Write failing migration tests**

Create these tests in `V28ChatSystemMessagesMigrationIntegrationTest`:

```java
@Test
void v28PreservesExistingMessageRowsAsUserMessages() { }

@Test
void v28AllowsTextOnlySystemMessagesAndRejectsInvalidKinds() { }

@Test
void canonicalSchemaMatchesV28MessageTypeContract() { }
```

Load the existing pre-v28 baseline, insert one ordinary text message, apply only `db/migrations/v28_chat_system_messages.sql`, and assert `message_type='user'` for the existing row. Insert a `system` text row and assert it succeeds; assert `system` plus an image, an unknown type, and `NULL` type fail with a PostgreSQL constraint error. Verify that the canonical `db/schema.sql` has the same column/default/check shape.

- [ ] **Step 2: Run the focused migration test and confirm RED**

Run:

```bash
./gradlew :app-main:test --tests '*V28ChatSystemMessagesMigrationIntegrationTest'
```

Expected: FAIL because the v28 file, enum, and canonical column do not exist.

- [ ] **Step 3: Add the enum and entity factory**

Create:

```java
package shinhan.fibri.ieum.common.chat.domain;

public enum MessageType {
	user,
	system
}
```

In `Message`, add an `@Enumerated(EnumType.STRING)` `messageType` field, make `text` and `image` construct `MessageType.user`, and add:

```java
public static Message system(
	ChatRoom room,
	User sender,
	String content,
	OffsetDateTime createdAt
) {
	if (content == null || content.isBlank()) {
		throw new IllegalArgumentException("system content must not be blank");
	}
	return new Message(room, sender, content, null, MessageType.system, createdAt);
}
```

Expose `getMessageType()`. Keep sender mandatory for all constructors.

- [ ] **Step 4: Implement the canonical and v28 SQL contract**

In canonical `messages`, add `message_type VARCHAR(16) NOT NULL DEFAULT 'user'` and named checks equivalent to:

```sql
CHECK (message_type IN ('user', 'system')),
CHECK (
  message_type <> 'system'
  OR (content IS NOT NULL AND image_file_id IS NULL)
)
```

Create v28 with `ALTER TABLE ... ADD COLUMN` and the two named checks. Do not drop the pre-existing `content IS NOT NULL OR image_file_id IS NOT NULL` check. The migration must be transactional and contain no destructive data statement.

- [ ] **Step 5: Extend the deployment helper before adding the migration to workflows**

Add `pg_temp.message_type_contract_state()` to distinguish `absent`, `exact`, and `mismatch` for `messages.message_type` and both named checks. The helper must:

```sql
SELECT pg_temp.message_type_contract_state() = 'absent'
  AS apply_message_type_migration \gset

\if :apply_message_type_migration
\i db/migrations/v28_chat_system_messages.sql
\endif
```

Preflight and final verification must reject `mismatch`; only `absent` may execute v28. Extend the shell and PostgreSQL helper tests to check the new state variable, include line ordering, run twice, and assert the exact constraint OIDs do not change on the second run. Add v28 to each deployment workflow's `scp` and `chmod` lists.

- [ ] **Step 6: Run focused database and deployment tests and confirm GREEN**

Run:

```bash
./gradlew :app-main:test --tests '*V28ChatSystemMessagesMigrationIntegrationTest'
bash deploy/tests/apply-admin-dashboard-migrations-test.sh
bash deploy/tests/apply-admin-dashboard-migrations-postgres-test.sh
```

Expected: all tests PASS; Docker-backed helper test leaves no container behind through its existing trap.

- [ ] **Step 7: Commit the DB contract**

```bash
git add common/src/main/java/shinhan/fibri/ieum/common/chat/domain/MessageType.java common/src/main/java/shinhan/fibri/ieum/common/chat/domain/Message.java db/schema.sql db/migrations/v28_chat_system_messages.sql app-main/src/test/java/shinhan/fibri/ieum/main/chat/db/V28ChatSystemMessagesMigrationIntegrationTest.java deploy/scripts/apply-admin-dashboard-migrations.sh deploy/tests/apply-admin-dashboard-migrations-test.sh deploy/tests/apply-admin-dashboard-migrations-postgres-test.sh .github/workflows/deploy-app-main.yml .github/workflows/deploy-app-ai.yml
git commit -m "채팅 시스템 메시지 스키마 추가"
```

---

### Task 2: Expose `messageType` consistently through REST and STOMP

**Files:**
- Modify: `app-main/src/main/java/shinhan/fibri/ieum/main/chat/dto/ChatMessageResponse.java`
- Modify: `app-main/src/main/java/shinhan/fibri/ieum/main/chat/service/WsMessageEvent.java`
- Modify: `app-main/src/main/java/shinhan/fibri/ieum/main/chat/service/ChatMessageService.java`
- Modify: `app-main/src/test/java/shinhan/fibri/ieum/main/chat/service/ChatMessageServiceTest.java`
- Modify: `app-main/src/test/java/shinhan/fibri/ieum/main/chat/websocket/ChatWebSocketIntegrationTest.java`

**Interfaces:**
- Produces: REST and STOMP fields `messageType: "user" | "system"`.
- Preserves: existing message IDs, sender fields, image URL calculation, normal message push delivery.

- [ ] **Step 1: Write failing response/event tests**

Add tests that build `Message.text(...)` and `Message.system(...)`, then assert:

```java
assertThat(ChatMessageResponse.from(userMessage).messageType()).isEqualTo(MessageType.user);
assertThat(ChatMessageResponse.from(systemMessage).messageType()).isEqualTo(MessageType.system);
assertThat(WsMessageEvent.from(systemMessage).messageType()).isEqualTo(MessageType.system);
```

Extend the STOMP integration assertion with JSON path `$.messageType` for an ordinary message (`user`). Task 3 will add the system path integration assertion once its publisher exists.

- [ ] **Step 2: Run focused tests and confirm RED**

Run:

```bash
./gradlew :app-main:test --tests '*ChatMessageServiceTest' --tests '*ChatWebSocketIntegrationTest'
```

Expected: compilation failures because response/event records do not have `messageType` or `from(Message)`.

- [ ] **Step 3: Add the shared event factory**

Change the records to include `MessageType messageType` after `senderNickname`. Add this exact factory to `WsMessageEvent`:

```java
public static WsMessageEvent from(Message message) {
	ChatMessageResponse response = ChatMessageResponse.from(message);
	return new WsMessageEvent(
		response.messageId(), response.roomId(), response.senderId(),
		response.senderNickname(), response.messageType(), response.content(),
		response.imageUrl(), response.createdAt()
	);
}
```

Make `ChatMessageService` call `WsMessageEvent.from(message)` instead of maintaining a private duplicate mapper. Keep its existing after-commit room event plus push behavior unchanged.

- [ ] **Step 4: Run the focused tests and confirm GREEN**

Run:

```bash
./gradlew :app-main:test --tests '*ChatMessageServiceTest' --tests '*ChatWebSocketIntegrationTest'
```

Expected: PASS; ordinary websocket payload contains `messageType=user` and normal push interaction remains covered.

- [ ] **Step 5: Commit the wire contract**

```bash
git add app-main/src/main/java/shinhan/fibri/ieum/main/chat/dto/ChatMessageResponse.java app-main/src/main/java/shinhan/fibri/ieum/main/chat/service/WsMessageEvent.java app-main/src/main/java/shinhan/fibri/ieum/main/chat/service/ChatMessageService.java app-main/src/test/java/shinhan/fibri/ieum/main/chat/service/ChatMessageServiceTest.java app-main/src/test/java/shinhan/fibri/ieum/main/chat/websocket/ChatWebSocketIntegrationTest.java
git commit -m "채팅 메시지 타입 응답 계약 추가"
```

---

### Task 3: Make voluntary leave and host kick persist and publish the same system message

**Files:**
- Create: `app-main/src/main/java/shinhan/fibri/ieum/main/chat/service/ChatSystemMessageService.java`
- Modify: `app-main/src/main/java/shinhan/fibri/ieum/main/chat/service/ChatRoomLifecycle.java`
- Modify: `app-main/src/main/java/shinhan/fibri/ieum/main/chat/service/ChatRoomLifecycleService.java`
- Modify: `app-main/src/main/java/shinhan/fibri/ieum/main/meeting/repository/MeetingParticipantRepository.java`
- Modify: `app-main/src/main/java/shinhan/fibri/ieum/main/meeting/service/MeetingService.java`
- Create: `app-main/src/test/java/shinhan/fibri/ieum/main/chat/service/ChatSystemMessageServiceTest.java`
- Modify: `app-main/src/test/java/shinhan/fibri/ieum/main/chat/service/ChatRoomLifecycleServiceTest.java`
- Modify: `app-main/src/test/java/shinhan/fibri/ieum/main/meeting/service/MeetingServiceTest.java`
- Modify: `app-main/src/test/java/shinhan/fibri/ieum/main/chat/websocket/ChatWebSocketIntegrationTest.java`

**Interfaces:**
- Produces: `void ChatRoomLifecycle.removeGroupMemberWithDepartureMessage(Long roomId, Long userId)`.
- Consumes: `Message.system`, `WsMessageEvent.from`, active chat member and active user ID repositories.
- Preserves: leave/kick authorization semantics and legacy `NotRoomMemberException` tolerance in both meeting service paths.

- [ ] **Step 1: Write failing unit tests for the lifecycle seam**

Add matching assertions to the ordinary participant leave and host kick tests:

```java
verify(chatRoomLifecycle).removeGroupMemberWithDepartureMessage(9L, 42L);
```

Keep host/non-host/invalid-target/inactive participant tests asserting the lifecycle is never called. Add a `@Lock(PESSIMISTIC_WRITE)` repository method used by both paths; unit tests must verify both service methods use the locked lookup. A transaction integration test must prove leave-vs-leave and leave-vs-kick produce exactly one system row.

In `ChatRoomLifecycleServiceTest`, assert a group member is left at the supplied `OffsetDateTime`, then `ChatSystemMessageService.recordMeetingDeparture(room, user, leftAt)` is called once, room-list remove is sent to the leaver, and no generic `removeMember` behavior is substituted.

For `ChatSystemMessageServiceTest`, use mocks to assert all of:

```java
verify(messageRepository).save(argThat(message ->
	message.getMessageType() == MessageType.system
		&& message.getSender().getId().equals(42L)
		&& message.getContent().equals("민지님이 모임을 떠났습니다")
));
verify(chatRoomListChangeEmitter).upsert(9L, List.of(77L));
verify(roomEventPublisher).publish(argThat(event ->
	event.messageType() == MessageType.system && event.roomId().equals(9L)
));
verifyNoInteractions(chatNotificationPublisher);
```

Use `TransactionSynchronizationManager.initSynchronization()` in one test, call the registered `afterCommit`, and assert no room event occurs before it. In the no-synchronization test, assert immediate safe room event publication. The system service must not depend on a push publisher at all.

- [ ] **Step 2: Run the focused tests and confirm RED**

Run:

```bash
./gradlew :app-main:test --tests '*MeetingServiceTest' --tests '*ChatRoomLifecycleServiceTest' --tests '*ChatSystemMessageServiceTest'
```

Expected: FAIL because `removeGroupMemberWithDepartureMessage`, the locked participant lookup, and `ChatSystemMessageService` do not exist.

- [ ] **Step 3: Implement the system message service**

Create `ChatSystemMessageService` with repositories/emitter/room event publisher only. Its public method is:

```java
void recordMeetingDeparture(ChatRoom room, User departingUser, OffsetDateTime leftAt)
```

It must save:

```java
Message message = messageRepository.save(Message.system(
	room,
	departingUser,
	"%s님이 모임을 떠났습니다".formatted(departingUser.getNickname()),
	leftAt
));
```

Then look up active user IDs after the member has left and emit one room-list upsert for non-empty remaining IDs. Register a `TransactionSynchronization.afterCommit` callback that safely calls `roomEventPublisher.publish(WsMessageEvent.from(message))`; log and swallow only room fanout failures, matching existing chat fanout behavior. Do not send a push notification.

- [ ] **Step 4: Implement the explicit common group-departure lifecycle operation**

Add to `ChatRoomLifecycle`:

```java
void removeGroupMemberWithDepartureMessage(Long roomId, Long userId);
```

In `ChatRoomLifecycleService.removeGroupMemberWithDepartureMessage`, find the active member, capture `OffsetDateTime.now()`, call `member.leave(leftAt)`, call `chatSystemMessageService.recordMeetingDeparture(member.getRoom(), member.getUser(), leftAt)`, and emit `chatRoomListChangeEmitter.remove(roomId, List.of(userId))`. Keep this new behavior scoped to this explicit group path rather than changing generic direct/question member removal semantics.

Add a `@Lock(PESSIMISTIC_WRITE)` participant lookup in `MeetingParticipantRepository` and use it before the status transition in both `MeetingService.leave` and `MeetingService.kick`. Replace both `removeMember` calls with `chatRoomLifecycle.removeGroupMemberWithDepartureMessage(roomId, targetUserId)`. Retain each existing `catch (NotRoomMemberException ignored)` so old chat-only state remains tolerable and produces no duplicate system row.

- [ ] **Step 5: Add an integration-level STOMP system-message assertion**

Extend a real lifecycle/meeting integration fixture to create a group room with two active users, invoke the leave path and the kick path separately, commit each transaction, and assert `/topic/rooms/{roomId}` receives:

```json
{
  "messageType": "system",
  "content": "민지님이 모임을 떠났습니다",
  "imageUrl": null
}
```

Also fetch the messages REST history as the remaining user and assert the same stored message appears. The departed/kicked user must no longer have room access. Keep `ChatWebSocketIntegrationTest` focused on payload serialization if its message service remains mocked; do not use it as the only persistence proof.

- [ ] **Step 6: Run focused tests and confirm GREEN**

Run:

```bash
./gradlew :app-main:test --tests '*MeetingServiceTest' --tests '*ChatRoomLifecycleServiceTest' --tests '*ChatSystemMessageServiceTest' --tests '*ChatWebSocketIntegrationTest'
```

Expected: PASS; no push mock appears in the system message service test.

- [ ] **Step 7: Commit the departure behavior**

```bash
git add app-main/src/main/java/shinhan/fibri/ieum/main/chat/service/ChatSystemMessageService.java app-main/src/main/java/shinhan/fibri/ieum/main/chat/service/ChatRoomLifecycle.java app-main/src/main/java/shinhan/fibri/ieum/main/chat/service/ChatRoomLifecycleService.java app-main/src/main/java/shinhan/fibri/ieum/main/meeting/service/MeetingService.java app-main/src/test/java/shinhan/fibri/ieum/main/chat/service/ChatSystemMessageServiceTest.java app-main/src/test/java/shinhan/fibri/ieum/main/chat/service/ChatRoomLifecycleServiceTest.java app-main/src/test/java/shinhan/fibri/ieum/main/meeting/service/MeetingServiceTest.java app-main/src/test/java/shinhan/fibri/ieum/main/chat/websocket/ChatWebSocketIntegrationTest.java
git commit -m "모임 이탈 시스템 메시지 발행"
```

---

### Task 4: Synchronize API documentation and perform final verification

**Files:**
- Modify: workspace `/Users/songchiho/Desktop/Hackerthon/code/api/API-SPEC.md` (cross-repo SSOT; not a BE PR file)
- Modify: `docs/superpowers/specs/2026-07-16-meeting-chat-leave-system-message-design.md`
- Modify: local ignored `spec.md`, `app-main/spec.md`, and corresponding `memory.md` files
- Modify: Notion API pages for meeting leave and chat message/event contracts

**Interfaces:**
- Documents: unchanged leave/kick HTTP contracts plus their shared persisted system-message side effect; additive `messageType` for REST/summary/STOMP.

- [ ] **Step 1: Update the local SSOT from verified code**

In workspace `/Users/songchiho/Desktop/Hackerthon/code/api/API-SPEC.md`, update both `POST /meetings/{id}/leave` and `POST /meetings/{id}/kick` to state that a successful departure persists and broadcasts the same neutral `system` chat message. Update the chat response and STOMP schemas with the exact `messageType` field and JSON example from Task 3. Keep the group room leave `409 GROUP_LEAVE_VIA_MEETING` wording; it is still canonical behavior. This shared SSOT is outside the BE worktree, so record its exact revision/result in local `memory.md` and the BE/FE PR bodies instead of attempting to stage it here.

- [ ] **Step 2: Reconcile Notion after local SSOT and tests agree**

Update the matching Notion API records with method/path/authentication/CSRF requirements, all existing success/failure responses, the system-message side effect, REST fields, STOMP fields, and frontend notes. Mark `변경여부=구현완료` only after the code, local SSOT, and Notion body match. If the connector is unavailable, record the exact blocker in local `memory.md` and PR body instead of claiming completion.

- [ ] **Step 3: Run the final backend gates**

Run:

```bash
./gradlew :app-main:test
bash deploy/tests/validate-deploy-config.sh
git diff --check
git status --short
```

Expected: all tests and deploy validation PASS; diff check is empty; only issue #145 backend files are staged for the final documentation commit. The workspace API SSOT change is intentionally absent from this worktree's `git status`.

- [ ] **Step 4: Run a local smoke where services are available**

With an isolated local/test database and two fixture users, call the meeting leave API as a non-host and the kick API as host, then query the remaining user's chat history. Confirm `200`, one stored `system` message for each successful departure, and no duplicated row after an overlapping/second leave. Delete fixture data or let the disposable test database terminate; do not target the deployed RDS.

- [ ] **Step 5: Commit documentation and record handoff evidence**

```bash
git add docs/superpowers/specs/2026-07-16-meeting-chat-leave-system-message-design.md
git add -f docs/superpowers/plans/2026-07-16-meeting-chat-leave-system-message.md
git commit -m "모임 이탈 채팅 계약 문서화"
```

Record command output summaries, smoke cleanup, Notion result, and any validation gap in ignored `memory.md` before opening the PR.
