# 채팅 답장 메시지 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 같은 채팅방에서 보이는 user message를 대상으로 한 단계 답장을 저장하고, REST history·room summary·수신자별 STOMP event가 같은 flat preview shape를 돌려주되 재입장 이력 이전 parent는 redaction한다.

**Architecture:** `messages.reply_to_message_id` nullable self-FK가 원문을 가리킨다. 응답은 재귀 엔티티를 노출하지 않고 `ChatReplyPreview`로 평탄화한다. history/summary는 현재 member cutoff로 preview를 redaction하고, user message는 개인 `/user/queue/rooms/{roomId}` fanout으로 같은 경계를 유지한다. system message topic은 유지한다.

**Tech Stack:** Spring Boot, STOMP/WebSocket, JPA/Hibernate, PostgreSQL, JUnit/Mockito.

## Guardrails

- 이 워크트리 밖의 파일과 운영 DB는 변경하지 않는다.
- reply는 thread·jump·reply edit/delete가 아니다. preview 안에 nested reply를 만들지 않는다.
- 검증은 server-side에서 수행한다. 프론트의 menu visibility는 보안 경계가 아니다.
- v32 migration은 #160의 v29–v31 뒤에 오도록 최종 integration 단계에서 rebase/순서를 확인한다.
- 전체 suite 대신 chat DTO/service/repository/migration focused tests와 compile만 실행한다.

## File map

| Area | Primary files |
| --- | --- |
| Message persistence | `common/src/main/java/shinhan/fibri/ieum/common/chat/domain/Message.java`, `common/.../repository/MessageRepository.java` |
| Wire/service | `app-main/src/main/java/shinhan/fibri/ieum/main/chat/{dto,service}/*` |
| Schema delivery | `db/schema.sql`, `db/migrations/*`, deploy migration helper/workflows |
| Focused tests | `app-main/src/test/java/shinhan/fibri/ieum/main/chat/{service,db}/*` |

## Implementation tasks

- [x] **Task 1 — Establish reply data and DTO behavior with tests first.**
  - Extend focused service/DTO tests for text and image reply previews, an absent parent, and backward-compatible sends without `replyToMessageId`.
  - Add nullable lazy `Message.replyTo` with no cascade and extend text/image factories without changing ordinary system message construction.
  - Add `ChatReplyPreview`, optional request `replyToMessageId`, and optional nullable `replyTo` on history, room-summary lastMessage, and `WsMessageEvent` response models.

- [x] **Task 2 — Enforce the visible-message security boundary.**
  - Add a repository lookup or service query that loads an eligible target in the same room and fetches the target sender needed by the preview.
  - In `ChatMessageService.send`, resolve the active `ChatMember` first, then allow only non-deleted `MessageType.user` targets whose id is after `visibleAfterMessageId`.
  - Map wrong room, deleted, system, missing, and hidden pre-rejoin targets to the existing invalid-message envelope. Preserve ordinary text/image send behavior.

- [x] **Task 3 — Eliminate reply N+1 and unify event output.**
  - Update history and latest-summary queries to `LEFT JOIN FETCH` both `replyTo` and its sender while retaining parentless messages.
  - Centralize preview conversion so `ChatMessageResponse.from(...)`, summary mapping, and `WsMessageEvent` have identical nullable flat data.
  - Keep after-commit WS and push ordering; user messages fan out on the member-scoped queue while system messages retain the room topic, and failures before persistence must not emit an event.

- [x] **Task 4 — Add the v32 migration and deploy contract.**
  - Add `v32_chat_message_reply.sql` with nullable `reply_to_message_id` and `ON DELETE SET NULL`, then align `schema.sql`.
  - Update migration deployment helper/workflows and a focused migration integration test.
  - Before commit/PR, compare migration ordering with the #160 branch; rebase only when integration owner instructs so no v29–v32 collision is introduced.

- [x] **Task 5 — Verify focused behavior.**
  - Run focused `ChatMessageServiceTest`, `ChatRoomSummaryQueryServiceTest`, WebSocket/DTO tests, and v32 migration tests.
  - Run the affected Gradle compile task, inspect output, and retain only green evidence in the PR description.

## Acceptance checklist

- [x] A valid same-room visible user message can be referenced by a text or image reply.
- [x] Hidden pre-rejoin history cannot be surfaced through a reply ID.
- [x] REST history, chat-list lastMessage, and WS event have the same non-recursive preview shape.
- [x] Deleting a parent preserves the child and clears only its reply link.
- [x] v32 is additive and has not been applied to production during this feature work.
- [x] 재입장 사용자는 visible reply를 받아도 cutoff 이전 parent의 REST·summary·WS preview를 받지 않으며, 다른 active member는 preview를 유지한다.
