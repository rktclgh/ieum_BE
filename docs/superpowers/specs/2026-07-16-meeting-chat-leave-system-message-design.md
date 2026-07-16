# 모임 채팅방 나가기와 이탈 시스템 메시지 (#145) — 백엔드 설계

## 1. 배경과 확인된 사실

일반 참여자가 모임(group) 채팅방에서 나가기를 누르면 `POST /api/v1/chat/rooms/{roomId}/leave`가 호출되어 `409 GROUP_LEAVE_VIA_MEETING`이 반환된다. 이는 백엔드 오류가 아니라, 채팅 멤버십만 제거해 `meeting_participants`가 `joined`로 남는 불일치를 막는 보호 계약이다.

- 배포 Nginx access log는 2026-07-16 14:40, 14:43 KST에 room 11의 동일 요청이 `409`으로 끝난 것을 확인했다.
- 같은 시간대에는 `POST /api/v1/meetings/{meetingId}/leave` 요청이 없었다.
- 정본 API는 이미 `POST /api/v1/meetings/{meetingId}/leave`이며, `MeetingService.leave`가 참가 상태와 그룹 채팅 멤버십을 함께 종료한다.
- 현재 정본 leave와 기존 방장 강퇴 경로 모두 `meeting_participants`와 `chat_members`만 바꾸며, 이탈 메시지를 저장하거나 실시간 발행하지 않는다.

따라서 이 이슈는 **프론트의 정본 API 선택 오류를 수정**하고, 자진 나가기와 방장 강퇴라는 두 회원 이탈 트랜잭션에 **같은 영속 시스템 메시지**를 추가하는 작업이다.

## 2. 목표와 범위

### 목표

1. 일반 참여자가 모임 채팅방에서 나가면 모임과 그룹 채팅방을 한 번의 정본 이탈로 함께 떠난다.
2. 일반 참여자의 자진 나가기와 방장의 강퇴 모두 이탈 시각에 `닉네임님이 모임을 떠났습니다`를 채팅 이력에 저장한다.
3. 남아 있는 참여자는 같은 메시지를 `/topic/rooms/{roomId}`에서 즉시 받는다.
4. REST 이력, 채팅 목록의 마지막 메시지, STOMP 페이로드가 같은 메시지 타입 계약을 사용한다.
5. 기존 일반 메시지·이미지 메시지·1:1/question 방 동작을 바꾸지 않는다.

### 범위 제외

- 재초대, 재참여 정책 변경, 방장 위임
- 모바일/웹 푸시 발송
- 기존 그룹 채팅 나가기 API의 `409 GROUP_LEAVE_VIA_MEETING` 계약 폐기
- 운영 DB에 이 작업 중 직접 DDL을 실행하는 것(배포 파이프라인이 승인된 migration을 적용한다)

## 3. 결정

### 3.1 `sender_id`는 nullable로 바꾸지 않는다

`messages.sender_id`는 NOT NULL이고, 메시지 이력·안 읽음 수·방 요약·신고 문맥 쿼리가 sender를 join/fetch한다. 시스템 메시지 때문에 sender를 `NULL`로 만들면 이 기존 계약을 광범위하게 바꾸게 된다.

시스템 메시지는 떠난 사용자를 sender로 보존한다. 다만 UI는 `messageType=system`일 때 sender 정보, 아바타, 일반 메시지 액션을 표시하지 않는다. sender는 FK 무결성·감사 정보·기존 조회 호환성에만 사용한다.

### 3.2 `messageType`을 추가하고 메시지는 DB에 저장한다

일회성 WebSocket 이벤트나 프론트의 임시 메시지 합성은 새로고침·재접속·나중에 입장한 이력 조회에 남지 않는다. 따라서 `messages.message_type`과 API의 `messageType`을 추가한다.

```text
MessageType = user | system
```

- 기존 행과 일반 text/image 메시지는 `user`다.
- 모임 자발 이탈과 방장 강퇴는 모두 `system`이다.
- system 행은 이미지가 없고, 이탈 당시의 닉네임이 포함된 완성 문장을 `content`에 저장한다. 닉네임이 나중에 바뀌어도 과거 이탈 이력은 변하지 않는다.
- 두 사유는 사용자에게 같은 중립 문구인 `닉네임님이 모임을 떠났습니다`로 보인다. 별도 `departureReason`·강퇴 사유 공개 필드는 이번 계약에 추가하지 않는다.

이 방식은 이벤트 종류를 과도하게 일반화하지 않으면서도, 향후 다른 system 이벤트가 생길 때 같은 타입을 재사용할 수 있다.

## 4. 공개 계약

### 4.1 모임 나가기

`POST /api/v1/meetings/{meetingId}/leave`의 HTTP 응답은 그대로 유지한다.

| 항목 | 계약 |
|---|---|
| 성공 | `200 OK`, body 없음 |
| 방장 | `403 HOST_CANNOT_LEAVE` |
| 모임 없음 | `404 MEETING_NOT_FOUND` |
| joined 참여자 아님 | `404 PARTICIPANT_NOT_FOUND` |
| 새 side effect | group chat member 이탈 후 system 메시지를 저장하고 room event 발행 |

`POST /api/v1/chat/rooms/{roomId}/leave`는 direct/question 방 전용으로 남고 group 방에서는 계속 `409 GROUP_LEAVE_VIA_MEETING`을 반환한다.

### 4.2 방장 강퇴

`POST /api/v1/meetings/{meetingId}/kick`의 권한·HTTP 응답·영구 밴 계약은 그대로 유지한다.

| 항목 | 계약 |
|---|---|
| 성공 | `200 OK`, body 없음 |
| 권한 | 방장만 가능, 그 외 `403 NOT_HOST` |
| 대상 검증 | 방장 자신이면 `400 VALIDATION_FAILED`, joined 참여자가 아니면 `404 PARTICIPANT_NOT_FOUND` |
| 새 side effect | 대상의 kicked 전이와 group chat member 이탈 후, 동일한 system 메시지를 저장하고 room event 발행 |

프론트의 회원관리 화면은 이 기존 API를 그대로 사용한다. UI 권한 표시는 보조일 뿐이며 서버 권한 검증을 대체하지 않는다.

### 4.3 REST·목록·STOMP 메시지

`ChatMessageResponse`, `ChatRoomSummaryResponse.lastMessage`, `WsMessageEvent`에 다음 필드를 추가한다.

```json
{
  "messageId": 987,
  "roomId": 11,
  "senderId": 42,
  "senderNickname": "민지",
  "messageType": "system",
  "content": "민지님이 모임을 떠났습니다",
  "imageUrl": null,
  "createdAt": "2026-07-16T14:45:48+09:00"
}
```

`messageType`은 서버 배포 뒤 모든 메시지에 항상 내려간다. 프론트는 순차 배포 안전성을 위해 필드가 없는 구 서버 응답을 `user`로 취급한다.

## 5. 트랜잭션과 이벤트 순서

`MeetingService.leave`와 `MeetingService.kick`은 각자의 기존 권한·대상 검증을 통과한 뒤, 공통 group-member departure lifecycle을 호출한다. 모든 DB 상태 변경은 같은 트랜잭션에 참여한다.

```text
1. 대상 meeting participant를 left 또는 kicked로 전이
2. active chat member를 조회
3. 동일 시각을 leftAt 및 system message.createdAt으로 사용
4. chat member를 left로 전이
5. Message.system(room, departingUser, "{nickname}님이 모임을 떠났습니다", leftAt) 저장
6. 남아 있는 active member에게 room-list upsert 예약
7. 떠난 사용자에게 room-list remove 예약
8. 커밋 후 /topic/rooms/{roomId}에 system WsMessageEvent 발행
```

어느 DB 단계라도 실패하면 바깥 모임 이탈 트랜잭션도 rollback된다. WebSocket과 room-list 이벤트는 커밋 뒤에만 발행한다. 이미 채팅 멤버가 없는 레거시 상태는 기존처럼 `NotRoomMemberException`을 무시하며 system 메시지도 만들지 않는다. 같은 요청을 재시도해도 joined 참여자가 아니므로 두 번째 메시지가 생기지 않는다.

leave와 kick은 대상 participant를 비관 잠금으로 읽어 동일 대상의 동시 leave/kick을 직렬화한다. 첫 요청만 상태 전이·system row를 만들고, 뒤 요청은 잠금 해제 후 non-joined 상태를 보고 기존 `PARTICIPANT_NOT_FOUND`로 끝난다.

## 6. 데이터베이스 마이그레이션

`db/schema.sql`과 신규 `db/migrations/v28_chat_system_messages.sql`은 다음의 additive 계약을 가진다.

```sql
ALTER TABLE messages
  ADD COLUMN message_type VARCHAR(16) NOT NULL DEFAULT 'user';

ALTER TABLE messages
  ADD CONSTRAINT ck_messages_message_type
    CHECK (message_type IN ('user', 'system'));

ALTER TABLE messages
  ADD CONSTRAINT ck_messages_system_text_only
    CHECK (
      message_type <> 'system'
      OR (content IS NOT NULL AND image_file_id IS NULL)
    );
```

- 기존 `content OR image_file_id` check는 유지한다. system은 content가 있으므로 기존 행과 새 행 모두 유효하다.
- 상수 default `user`로 기존 행을 보존한다. 기존 행 재작성·삭제·테이블 재생성은 하지 않는다.
- 배포 helper는 schema 상태가 absent/exact/mismatch인지 확인하고, absent일 때만 v28을 실행한다. exact 재실행은 기존 constraint를 교체하지 않는다.
- `deploy/scripts/apply-admin-dashboard-migrations.sh`, `deploy-app-main.yml`, `deploy-app-ai.yml`의 migration 복사·권한 부여 목록도 v28을 포함한다. 어느 배포 경로든 DB 계약과 application code가 분리되지 않게 한다.

## 7. 책임 분리와 변경 파일

| 영역 | 책임 |
|---|---|
| `common/chat/domain/MessageType.java` | `user`, `system` enum |
| `common/chat/domain/Message.java` | type 필드와 `Message.system(...)` factory |
| `main/chat/service/ChatRoomLifecycle.java` | leave·kick 공용 group-member departure lifecycle interface |
| `main/chat/service/ChatRoomLifecycleService.java` | chat member 이탈과 system message service 호출 |
| `main/chat/service/ChatSystemMessageService.java` | system row 저장, 잔류자 목록 upsert, after-commit room event 발행. push는 발행하지 않음 |
| `main/meeting/service/MeetingService.java` | leave·kick에서 공용 lifecycle 호출 및 participant lock 사용 |
| `main/meeting/repository/MeetingParticipantRepository.java` | leave·kick용 `PESSIMISTIC_WRITE` participant lookup |
| `main/chat/dto/ChatMessageResponse.java` / `WsMessageEvent.java` | `messageType` 노출 |
| `db/schema.sql`, `db/migrations/v28_*.sql`, deploy helper/workflows | schema와 실제 배포 반영 |
| `code/api/API-SPEC.md` 및 Notion | 프론트가 바로 쓸 수 있는 계약 동기화 |

일반 사용자 메시지의 WebSocket+push 발행 경로는 바꾸지 않는다. system 메시지는 기존 room topic만 사용하고 web push는 보내지 않는다.

## 8. 검증 전략

1. `MeetingServiceTest`: 일반 참여자 leave와 방장 kick이 공용 lifecycle을 호출하고, host/inactive/legacy chat-member 없는 경우의 기존 규칙을 유지하는지 확인한다.
2. `ChatRoomLifecycleServiceTest`와 `ChatSystemMessageServiceTest`: system row의 sender/type/content/timestamp, member left 상태, 잔류자 upsert·떠난 사용자 remove, rollback 시 room event 미발행을 확인한다.
3. 동시성 통합 테스트: 같은 대상의 leave-vs-leave 및 leave-vs-kick 요청에서 system row가 정확히 하나만 저장되는지 확인한다.
4. DTO·WebSocket 테스트: REST history와 STOMP event가 모두 `messageType=system`을 담는지 확인한다.
5. PostgreSQL migration integration: 기존 messages 행이 `user`로 보존되고 system DB check가 적용되는지, helper 재실행이 안전한지 확인한다.
6. 최종 app-main focused tests, deploy helper tests, Gradle test 및 로컬 smoke를 실행한다. production DB migration은 PR 단계에서 실행하지 않는다.

## 9. 문서 동기화 완료 조건

코드·테스트가 통과한 뒤 다음을 같은 계약으로 갱신한다.

1. 로컬 `code/api/API-SPEC.md`: 모임 leave·kick side effect, ChatMessageResponse, STOMP, group chat leave canonical route.
2. 관련 로컬 spec/memory 문서: 작업 상태와 검증 결과.
3. Notion API 명세: `POST /meetings/{id}/leave`, `POST /meetings/{id}/kick`, chat message history 및 STOMP message schema, 성공/실패 응답과 프론트 유의사항.

Notion의 구현 상태는 코드·로컬 SSOT·Notion 본문이 일치하고 검증이 끝난 뒤에만 `구현완료`로 변경한다.
