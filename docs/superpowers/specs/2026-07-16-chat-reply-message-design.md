# 채팅 답장 메시지 (#161) — 백엔드 설계

## 1. 목표

사용자가 같은 채팅방의 다른 사용자 메시지에 답장할 수 있게 한다. 답장 메시지는 원문 전체를 복사하지 않고, 안정적인 한 단계 preview만 저장·전달한다. REST history, 채팅 목록 마지막 메시지, STOMP user-message event는 같은 flat reply shape를 사용하되 각 수신자의 이력 경계보다 이전 parent preview는 `null`로 redaction한다.

## 2. 범위와 비범위

### 범위

- `messages.reply_to_message_id` nullable self FK
- STOMP send payload의 optional `replyToMessageId`
- history/summary/user-message WebSocket의 flat `replyTo` preview와 수신자별 redaction
- 같은 room·현재 가시 이력·user message만 답장 대상으로 허용하는 서버 검증

### 비범위

- 스레드, 답장 수, 원문으로 jump하는 API, 답장 편집/삭제
- system message·삭제된 메시지에 답장, 또는 self-target을 별도 거부하는 서버 정책
- 프론트의 답장 선택 UI 범위 확장(프론트는 다른 사용자 메시지만 선택지로 노출)
- 기존 메시지 body, image upload, push notification 또는 notice/report 정책 재설계

## 3. 데이터 모델 결정

```sql
ALTER TABLE messages
  ADD COLUMN reply_to_message_id BIGINT
  REFERENCES messages(message_id) ON DELETE SET NULL;
```

`Message.replyTo`는 nullable `@ManyToOne(fetch = LAZY)`다. Cascade는 사용하지 않는다. 원문 삭제가 발생하면 DB가 FK만 NULL로 바꾸고 답장 본문은 남는다.

응답의 reply는 재귀 relation이 아니라 전송 시점의 parent를 읽은 flat preview다.

```json
"replyTo": {
  "messageId": 123,
  "senderId": 45,
  "senderNickname": "김연두",
  "content": "떡볶이 먹을까?",
  "imageUrl": null
}
```

`replyTo` 안에는 다시 `replyTo`가 없다. 이미지 원문은 `content=null`, `imageUrl`만 제공한다. parent가 physical delete로 FK NULL이 되면 replyTo도 null이며 과거 답장 본문은 그대로 조회된다.

## 4. STOMP와 REST 계약

클라이언트는 기존 `/app/rooms/{roomId}/send` destination에 optional id만 추가한다.

```json
{
  "content": "전 다 좋아요",
  "imageFileId": null,
  "replyToMessageId": 123
}
```

`content`/`imageFileId` 상호배타·길이 제약은 그대로 유지한다. reply가 없는 기존 send body도 완전히 호환된다.

`ChatMessageResponse`, `ChatRoomSummaryResponse.lastMessage`, `WsMessageEvent`에는 optional nullable `replyTo`를 같은 의미로 넣는다. `replyTo` 필드가 없는 구 서버 이벤트는 프론트가 null로 정규화할 수 있게 한다.

### AS-BUILT WebSocket delivery

- 일반 user message는 commit 뒤 각 active member에게 `/user/queue/rooms/{roomId}`로 개별 fan-out한다. shared `/topic/rooms/{roomId}`에는 user message를 보내지 않는다.
- 모임 이탈 같은 system message는 기존 `/topic/rooms/{roomId}` broadcast를 유지한다.
- 개인 room queue 구독은 authenticated active member만 허용한다. `/user/queue/rooms`는 room-list 전용으로 유지하고, wildcard와 다른 user queue destination은 default-deny한다.
- 두 채널 모두 `WsMessageEvent` shape를 유지한다. 단, reply parent id가 수신자의 `visibleAfterMessageId` 이하이면 현재 reply message는 보이더라도 `replyTo=null`으로 보낸다.

## 5. 검증과 가시성 경계

`ChatMessageService.send`은 active ChatMember를 먼저 확인한 뒤 reply target을 조회한다. target이 다음을 모두 만족해야 한다.

1. 동일 `roomId`
2. `deletedAt == null`
3. `messageType == user`
4. `target.messageId > member.visibleAfterMessageId`

네 번째 조건은 1:1 방을 나갔다 재입장한 사용자가 이전 이력을 id로 추측해 답장하면서 원문/닉네임을 재노출하는 것을 막는다. 불일치·system·삭제·숨김 과거 target은 `400 INVALID_CHAT_MESSAGE`로 처리한다.

조회와 fan-out도 같은 경계를 따른다. outer reply message가 현재 member에게 보이더라도 parent id가 `visibleAfterMessageId` 이하이면 REST history, room summary, 개인 WebSocket event 모두 parent preview만 `null`로 만든다. shared room topic은 member별 payload를 만들 수 없으므로 user message transport로 사용하지 않는다.

프론트는 다른 사용자 메시지에만 답장 메뉴를 노출한다. 다만 서버는 generic same-room/current-visible user-message validator를 사용하므로, self reply를 별도 금지하지 않는다. 이는 향후 프론트 UX 변화와 서버 계약을 불필요하게 결합하지 않기 위함이다.

## 6. 조회 성능과 이벤트 순서

이력과 room summary query는 message sender뿐 아니라 `replyTo`와 reply sender도 `LEFT JOIN FETCH` 한다. parent가 없는 메시지는 left join으로 유지한다. 페이지별 N+1을 만들지 않는다.

```text
active member 확인
  -> reply target visibility 검증
  -> user Message(text/image, replyTo) 저장
  -> active recipient별 cutoff 확보 + room summary upsert 예약
  -> commit 후 개인 WS message event + 기존 push
```

저장 실패 시 event/push는 발행하지 않는다. 답장은 일반 user message이므로 기존 push/room-list 순서는 유지한다.

## 7. migration과 통합 순서

일정 관리 #160이 v29~v31 migration을 예약했다. 이 이슈는 `v32_chat_message_reply.sql`을 추가하고, 최종 PR 생성 전에 #160 branch/develop을 rebase해 migration helper·workflow·schema 목록을 충돌 없이 통합한다.

`db/schema.sql`, `deploy/scripts/apply-admin-dashboard-migrations.sh`, deploy workflows와 deploy shell tests 모두 v32를 반영한다. migration은 additive이며 production DB에 이 이슈 과정에서 직접 적용하지 않는다.

## 8. 구현 경계

| 영역 | 책임 |
| --- | --- |
| `common/chat/domain/Message` | nullable replyTo association, text/image factory 확장 |
| `common/chat/repository/MessageRepository` | visibility-aware reply target lookup, reply/sender fetch joins |
| `main/chat/dto/SendChatMessageRequest` | optional `replyToMessageId` |
| `main/chat/dto/ChatMessageResponse`, `ChatReplyPreview` | REST/summary reply wire model, member cutoff 기반 preview redaction |
| `main/chat/service/ChatMessageService` | target validation, message creation, active recipient별 after-commit fanout |
| `main/chat/service/WsMessageEvent`, `SimpRoomEventPublisher` | 개인 queue로 flat preview fanout, recipient별 redaction |
| `db/schema.sql`, `db/migrations/v32_*`, deploy files | schema/운영 migration delivery |

## 9. 최소 검증

1. ChatMessageService/SimpRoomEventPublisher: text/image reply 저장, 재입장 수신자의 WS parent preview redaction, 정상 수신자의 preview 유지, 개인 queue fanout.
2. ChatService/ChatRoomSummaryQueryService: history와 last-message summary가 reply/sender fetch join을 이용하되 재입장 cutoff 이전 parent preview는 null로 만든다.
3. v32 PostgreSQL migration과 schema contract: nullable FK 및 `ON DELETE SET NULL`.
4. existing WebSocket fixture/DTO test: replyTo가 없는 기존 message도 정상 직렬화.

변경 모듈 focused Gradle tests와 compile만 수행한다. 전체 test suite는 범위 밖이다.

## 10. 문서 완료 조건

구현과 최소 검증 후에만 `code/api/API-SPEC.md`의 chat history, STOMP send/event, room summary lastMessage 계약을 AS-BUILT 값으로 변경한다. 특히 user message 구독은 `/user/queue/rooms/{roomId}`, system message는 `/topic/rooms/{roomId}`, cutoff 이전 reply parent는 `replyTo=null`이라는 값을 그대로 동기화한다. 같은 내용을 Notion에 동기화하고 세 정본이 일치한 뒤 구현완료로 표시한다.
