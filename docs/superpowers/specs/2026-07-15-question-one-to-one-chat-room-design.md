# 질문 컨텍스트 1:1 채팅방 설계

## 1. 문서 목적

GitHub `ieum_BE#89`의 질문 채팅을 기존 1:1 채팅 모델 위에 설계한다.

질문 채팅은 별도의 답변·채택 도메인이 아니다. 일반 1:1 채팅방에 다음 두 정보가 추가된 타입이다.

- `roomType=question`
- `questionId`

사용자가 제공한 화면 흐름은 다음과 같다.

`질문 내역 → 답변 목록 → 답변 채택 확인 → 채팅 시작 → 질문 제목 채팅방 → 더보기/채팅방 나가기`

이 흐름에서 채택은 프론트엔드가 `채팅 시작` 버튼을 노출하는 UX 조건이다. 백엔드 질문 채팅 API의 권한 조건으로 사용하지 않는다.

## 2. 핵심 결정

1. 질문 채팅은 `questionId`가 연결된 1:1 채팅방이다.
2. 질문방의 참여자 관계를 답변·채택 데이터로 다시 증명하지 않는다.
3. 요청자가 질문 작성자인지, 대상이 질문 답변자인지 검사하지 않는다.
4. 질문방에는 친구관계를 요구하지 않는다.
5. 인증, 서로 다른 두 사용자, 유효한 사용자, 차단 등 1:1 채팅의 공통 안전 규칙은 유지한다.
6. 같은 질문과 같은 두 사용자는 항상 같은 `roomId`를 사용한다.
7. 방의 표시 이름은 연결된 질문 제목이다.
8. 나가기는 방 삭제가 아니다. 같은 방과 멤버십 행은 남고, 나간 사용자에게 과거 메시지만 다시 보이지 않게 한다.
9. 공개 API와 제품 용어에 방을 다시 들어간다는 별도 개념을 만들지 않는다.

## 3. 현재 구현과 수정할 지점

현재 `develop`에는 이미 다음 기반이 있다.

- `POST /api/v1/chat/rooms/question`
- `RoomType.question`
- `chat_rooms.question_id`
- `q:{questionId}:{minUserId}:{maxUserId}` 형식의 멱등 키
- 친구관계 없이 질문방 생성
- 양방향 차단 검사
- 생성·목록·상세 응답의 `questionTitle`
- 반복·동시 요청을 같은 방으로 수렴시키는 unique key와 retry

수정이 필요한 부분은 두 가지다.

### 질문방에 과도하게 결합된 관계 검증

현재 `ChatService`는 다음을 검사한다.

- 요청자가 질문 작성자인가
- 대상 사용자가 해당 질문에 사람 답변을 작성했는가

이 검사는 질문방을 답변 관계의 파생 도메인으로 만든다. 질문방을 `questionId`가 붙은 1:1 채팅 타입으로 유지하기 위해 두 검사를 제거한다. `AnswerRepository`도 질문방 생성 흐름에서 제거한다.

### 나가기 이후 과거 메시지 노출

현재는 나간 멤버를 다시 활성 상태로 바꿀 때 `left_at`만 비운다. 메시지 조회, 안 읽은 수, 마지막 메시지 조회에는 사용자별 이력 경계가 없으므로 같은 방이 다시 활성화되면 과거 대화가 모두 노출된다.

이 문제는 질문방 전용 예외가 아니라 `direct`와 `question`에 공통으로 적용할 1:1 채팅 가시성 규칙으로 수정한다.

## 4. 목표와 비목표

### 목표

- 질문방을 기존 1:1 채팅 생명주기와 메시지 모델로 처리한다.
- 질문방 생성 시 답변·채택·질문 작성자 관계를 조회하지 않는다.
- 친구가 아닌 사용자 사이에도 질문방을 만들 수 있다.
- 질문방의 식별자와 제목 컨텍스트를 안정적으로 제공한다.
- 반복·동시 요청은 하나의 방으로 수렴한다.
- 한 사용자가 나간 뒤 같은 방이 다시 활성화돼도 그 사용자에게 이전 메시지를 노출하지 않는다.
- 과거 메시지 비노출을 메시지 본문 삭제 없이 사용자별로 처리한다.

### 비목표

- 일반 `direct` 채팅의 친구관계 정책 변경
- 답변 및 채택 도메인의 수정
- 차단 후 기존 방의 송수신 정책 변경
- 그룹 채팅의 나가기·과거 이력 정책 변경
- 질문 삭제 이후 방 보존 정책 변경
- 채팅방별 별도 제목 컬럼 추가

## 5. 도메인 모델

### `ChatRoom`

질문방은 기존 `ChatRoom`의 한 타입이다.

```text
ChatRoom
├─ roomType: question
├─ questionId: required
├─ roomKey: q:{questionId}:{minUserId}:{maxUserId}
└─ members: exactly two users
```

- 방의 정체성은 `질문 + 순서 없는 두 사용자`다.
- 같은 두 사용자라도 질문이 다르면 다른 방이다.
- 같은 질문과 같은 두 사용자는 요청 순서와 관계없이 같은 방이다.
- `questionId`는 방의 컨텍스트이지 참여 권한 관계가 아니다.

### 표시 이름

- API 필드: `questionTitle`
- 화면 표시: `questionTitle`을 채팅방 제목으로 사용
- `roomType=question`은 채팅방 분류와 UI 형태를 결정
- `questionId`는 질문 상세 이동과 컨텍스트 식별에 사용

별도 `label` 또는 `title` 컬럼은 추가하지 않는다. 질문 제목이 질문방의 label이다.

### `ChatMember`와 사용자별 메시지 가시성

나가기 후에도 영속 채팅방 목록인 `chat_rooms`와 `chat_members` 행은 그대로 남는다. `chat_members`에 nullable `visible_after_message_id`를 추가하고, 해당 사용자가 볼 수 없는 마지막 메시지 ID를 exclusive cutoff로 저장한다.

사용자에게 메시지를 보여주는 조건은 다음과 같다.

```text
message.room_id = member.room_id
AND message.deleted_at IS NULL
AND (
  member.visible_after_message_id IS NULL
  OR message.message_id > member.visible_after_message_id
)
```

- 최초 참여 시 cutoff는 `NULL`이며 방 생성 이후의 모든 메시지를 볼 수 있다.
- 나가기는 현재 멤버십을 비활성화하지만 방과 메시지를 삭제하지 않는다.
- 기존 방을 다시 여는 요청은 **인증된 요청자 멤버만** 활성화한다.
- 요청자 멤버가 비활성 상태라면 현재 방의 최대 `message_id`를 cutoff로 저장하고 `left_at`을 비운다.
- 상대 멤버는 방 조회만으로 활성화하지 않는다. 실제 새 메시지를 받기 직전에만 같은 방식으로 활성화한다.
- 이미 활성 상태인 멤버에게 같은 방 API를 반복 호출해도 cutoff는 바뀌지 않는다.
- 한 사용자의 cutoff 변경은 상대방의 이력에 영향을 주지 않는다.
- `joined_at`은 최초 멤버십 시작 시각이라는 기존 의미를 유지한다.

이 동작은 같은 방의 내부 멤버 상태 변경일 뿐 별도의 사용자 행위·API·도메인으로 표현하지 않는다.

## 6. 1:1 채팅 공통 규칙과 타입 차이

### 공통 규칙

`direct`와 `question`은 다음 규칙을 공유한다.

1. 요청자는 인증된 사용자다.
2. 요청자와 대상 사용자는 서로 달라야 한다.
3. 두 사용자는 존재하고 soft-delete되지 않아야 한다.
4. 두 사용자 사이에 차단 관계가 없어야 한다.
5. 방은 순서 없는 두 사용자 조합으로 멱등 생성한다.
6. 정확히 두 개의 멤버십을 유지한다.
7. 나가기 이후 메시지 가시성은 사용자별 `visible_after_message_id`로 제한한다.

계정 정지, 차단 이후 기존 방 송수신 등 전역 정책은 질문 타입에서 별도 강화하지 않는다. 공통 채팅 정책이 변경될 때 두 타입에 함께 적용한다.

### 타입별 컨텍스트

| 항목 | `direct` | `question` |
|---|---|---|
| room key | `d:{minUserId}:{maxUserId}` | `q:{questionId}:{minUserId}:{maxUserId}` |
| 추가 컨텍스트 | 없음 | 활성 `questionId` |
| 친구관계 | 필요 | 불필요 |
| 표시 이름 | 상대 사용자 이름 | 질문 제목 |
| 답변·채택 관계 | 조회하지 않음 | 조회하지 않음 |

질문 타입의 추가 검증은 `questionId`가 유효한 활성 질문인지 확인하는 것뿐이다. 질문 작성자, 답변 작성자, 채택 여부는 채팅방 권한과 무관하다.

## 7. API 계약

### 요청

```http
POST /api/v1/chat/rooms/question
Content-Type: application/json
Cookie: access_token=...; sid=...; XSRF-TOKEN=...
X-CSRF-Token: ...
```

```json
{
  "questionId": 5,
  "targetUserId": 2
}
```

- 두 필드는 필수 양의 정수다.
- `targetUserId`는 질문·답변 관계와 무관하게 대화할 상대 사용자 ID다.
- FE는 제공된 워크플로우에 따라 채택된 답변의 작성자 ID를 전달할 수 있지만, 이는 화면 흐름의 선택이지 API 권한 계약이 아니다.

### 성공 응답

```json
{
  "roomId": 7,
  "roomType": "question",
  "meetingId": null,
  "questionId": 5,
  "questionTitle": "명동에서 길 잃었어요"
}
```

- 새 방과 기존 방 모두 `200 OK`를 반환한다.
- 질문이 활성 상태이므로 생성 응답의 `questionTitle`은 non-null이다.
- 기존 방 목록·상세의 레거시 또는 삭제 상태를 고려해 FE 타입은 nullable fallback을 유지할 수 있다.

### 실패 응답

| HTTP | code | 조건 |
|---:|---|---|
| 400 | `VALIDATION_FAILED` | ID 누락 또는 양수가 아님 |
| 400 | `SELF_CHAT_ROOM` | 요청자와 대상 사용자가 동일 |
| 401 | `AUTHENTICATION_REQUIRED` | 세션 없음 또는 만료 |
| 403 | `CSRF_FAILED` | CSRF 검증 실패 |
| 403 | `BLOCKED` | 두 사용자 사이에 차단 관계가 있음 |
| 404 | `QUESTION_NOT_FOUND` | 질문이 없거나 soft-delete됨 |
| 404 | `USER_NOT_FOUND` | 요청자 또는 대상 사용자가 없거나 탈퇴함 |

질문 작성자·답변자·채택 관계 불일치에 대한 `FORBIDDEN`은 정의하지 않는다. 질문방 생성 경로에서 `NOT_FRIENDS`도 사용하지 않는다.

## 8. 처리 흐름

### 질문방 얻기 또는 생성

```text
인증 사용자 확인
→ 활성 질문 조회
→ 본인 대상 여부 확인
→ 대상 사용자 조회
→ 양방향 차단 확인
→ q:{questionId}:{min}:{max} 조회 또는 생성
→ 새 방이면 두 멤버십 생성
→ 기존 방이면 요청자 멤버만 필요 시 활성화
→ roomId, questionId, questionTitle 반환
```

- 질문 조회는 FK 무결성, soft-delete 확인, 제목 응답을 위한 컨텍스트 조회다.
- `Question.authorId`와 `AnswerRepository`는 사용하지 않는다.
- 기존 질문 lock과 room-key 충돌 retry는 질문 삭제 경쟁 및 동시 생성 안정성을 위해 유지할 수 있다.
- 새 방은 요청자와 대상 멤버를 모두 활성 상태로 생성한다.
- 기존 방의 요청자 멤버가 비활성 상태일 때만 현재 최대 메시지 ID를 cutoff로 기록하고 활성화한다.
- 기존 방의 대상 멤버 상태는 이 API가 변경하지 않는다.
- API를 반복 호출해도 활성 요청자의 cutoff는 변경하지 않는다.

### 화면 흐름

| 화면 | 사용자 행동 | 서버 동작 |
|---|---|---|
| 질문 내역 | 질문 선택 | 질문 상세와 답변 목록 조회 |
| 답변 목록 | `답변 채택` 선택 | 채택 확인 다이얼로그 표시 |
| 채택 확인 | 확인 | 채택 API 호출 후 질문 상세 갱신 |
| 답변 목록 | `채팅 시작` 선택 | `POST /api/v1/chat/rooms/question` 호출 |
| 질문 채팅 | 대화 | `roomType=question`, `questionTitle` 사용 |
| 더보기 | 나가기 | 기존 1:1 나가기 API 사용 |

채팅방 생성 API는 사용자가 `채팅 시작`을 선택한 시점에 명시적으로 호출한다. 채팅방 생성은 이 API 안에서만 처리하고 답변·채택 서비스와 결합하지 않는다.

### 나가기 이후 같은 방의 동작

1. 나가면 해당 `ChatMember.leftAt`을 기록한다.
2. `ChatRoom`, 두 `ChatMember`, 기존 `Message`는 삭제하지 않는다.
3. 기존 방 API를 호출한 요청자가 비활성 상태면 요청자 멤버 행을 잠근다.
4. 현재 방의 최대 `messageId`를 요청자의 `visibleAfterMessageId`로 기록하고 `leftAt`을 비운다.
5. 대상 멤버가 나간 상태여도 방 조회만으로 대상 상태를 바꾸지 않는다.
6. 활성 사용자가 메시지를 보낼 때 비활성 수신자 멤버 행을 먼저 잠그고, 저장 전 최대 `messageId`를 cutoff로 기록한 뒤 수신자를 활성화한다.
7. 그다음 생성되는 메시지는 cutoff보다 큰 ID를 가지므로 보이고, 이전 메시지는 보이지 않는다.
8. 메시지 목록, cursor pagination, 안 읽은 수, 방 목록의 마지막 메시지는 모두 같은 사용자별 cutoff를 사용한다.
9. 신고·관리자 증거 조회는 사용자 화면 가시성 필터를 적용하지 않고 보존된 전체 이력을 사용한다.

공개 계약에는 별도 재참여 요청을 추가하지 않는다. 같은 room key와 roomId를 계속 사용하는 1:1 채팅 생명주기다.

## 9. 메시지 가시성 구현 선택

### 선택: `visible_after_message_id` exclusive cutoff

- 장점: timestamp 정밀도와 애플리케이션 clock 순서에 의존하지 않는다.
- 장점: `message_id > cutoff`라는 단일 규칙을 모든 사용자 화면 query에 적용할 수 있다.
- 장점: 메시지를 보존하면서 사용자별 이력만 숨길 수 있다.
- 장점: `joined_at`의 최초 참여 시각 의미를 바꾸지 않는다.
- 비용: `chat_members.visible_after_message_id BIGINT NULL` migration이 필요하다.
- 주의: 활성 멤버에 대한 반복 호출은 반드시 no-op이어야 한다.

### 선택하지 않음: 과거 메시지 물리 삭제

한 사용자의 나가기가 상대방 이력과 신고 증거까지 삭제하므로 1:1 채팅의 사용자별 가시성 요구와 맞지 않는다.

### 선택하지 않음: `joined_at` timestamp를 가시성 경계로 재사용

timestamp가 같은 메시지와 경계를 엄밀히 분리하지 못하고, 다중 인스턴스 clock 순서에도 영향을 받는다. 또한 최초 참여 시각이라는 기존 의미를 잃어 유지보수 시 혼동을 만든다.

## 10. 컴포넌트별 변경

### `ChatService`

- 질문 작성자 검증 제거
- 사람 답변자 검증 제거
- `AnswerRepository` 의존성 제거
- 질문방 생성에서만 쓰이던 `QuestionForbiddenException`과 handler mapping은 소비처가 없어지면 제거
- 활성 질문 존재와 제목 조회 유지
- self, 사용자 존재, 차단 검증 유지
- 기존 방이면 인증된 요청자 멤버만 활성화하고 대상 멤버는 변경하지 않음
- direct/question의 공통 참여자 검증은 작은 private helper로 공유

전략 객체나 질문방 전용 권한 계층은 추가하지 않는다. `direct`는 친구 검증, `question`은 질문 컨텍스트 조회만 각 생성 메서드에 남긴다.

### `ChatMember`

`direct/question`의 비활성 멤버에는 exclusive cutoff를 저장하는 메서드를 사용한다.

```java
void activateAfter(Long latestMessageId) {
    if (leftAt == null) {
        return;
    }
    visibleAfterMessageId = latestMessageId;
    leftAt = null;
}
```

- 제품·API에는 방을 다시 들어간다는 별도 상태나 동작을 노출하지 않는다.
- pin과 알림 설정은 기존 사용자 설정이므로 유지한다.
- `joinedAt`과 `lastReadAt`의 기존 의미는 바꾸지 않는다. unread query는 `lastReadAt`과 cutoff를 모두 적용한다.
- 그룹 멤버 복구 경로는 이 메서드를 호출하지 않는다. 그룹 이력 정책을 바꾸지 않도록 기존 상태 복구를 별도 helper로 유지한다.

### `ChatMemberRepository`

기존 bulk update 대신 멤버 행을 명시적으로 잠그는 repository contract를 둔다.

- `findByRoomIdAndUserIdForUpdate(roomId, userId)`
- `findOneToOneMembersByRoomIdForUpdate(roomId)` — user ID 오름차순 잠금

질문방 API는 요청자 멤버만 잠근다. 1:1 메시지 전송은 송신자·수신자 멤버를 고정 순서로 잠근 뒤 송신자의 활성 상태를 확인하고 수신자를 처리한다. `leftAt != null`인 멤버에만 최대 메시지 ID를 읽고 `activateAfter(maxMessageId)`를 호출한다.

### `MessageRepository`

사용자 화면용 query는 raw cutoff를 service 인자로 받지 않는다. `roomId/userId` 또는 `roomIds/userId`를 받아 query 내부에서 `ChatMember`와 join하고 다음 조건을 항상 적용한다.

```text
member.visibleAfterMessageId IS NULL
OR message.id > member.visibleAfterMessageId
```

- 최신 메시지 페이지
- cursor 이전 페이지
- 안 읽은 메시지 수
- 방 목록의 마지막 메시지

`findMaxMessageIdByRoomId`는 활성화 cutoff를 잡는 데 사용하며 soft-delete 여부와 관계없이 방의 모든 메시지 ID를 대상으로 한다. 마지막 메시지는 방별 전역 결과가 아니라 `userId`를 받는 사용자별 결과여야 한다.

신고 문맥 조회처럼 전체 이력이 필요한 query는 `findUnfiltered...`처럼 목적을 이름에 드러내고 사용자 화면 경로에서는 호출하지 않는다.

### `ChatRoomLifecycleService`

- 질문방 생성과 기존 방 조회는 현재 room key를 재사용한다.
- `getOrCreateQuestionRoom(questionId, requesterId, targetUserId)`처럼 요청자 역할을 시그니처에 드러낸다.
- 새 방이면 두 멤버를 생성하고, 기존 방이면 요청자 멤버만 1:1 공통 `activateAfter(cutoff)` 규칙으로 처리한다.
- 대상 멤버는 방 조회로 활성화하지 않는다.
- 질문 작성자·답변자·채택 관계는 알지 못한다.
- 그룹방의 `addMember` 경로는 기존 동작을 유지한다.

### DB migration

`chat_members`에 다음 nullable 컬럼을 추가한다.

```sql
ALTER TABLE chat_members
    ADD COLUMN visible_after_message_id BIGINT;

CREATE INDEX idx_messages_room_message
    ON messages(room_id, message_id DESC);
```

- FK는 걸지 않는다. 메시지 soft-delete나 보존 정책과 무관한 숫자 watermark다.
- 기존 행은 `NULL`이므로 배포 전과 동일하게 현재 이력을 모두 볼 수 있다.
- 별도 backfill은 필요 없다.
- 복합 인덱스는 방별 최대 ID 조회와 cutoff 이후 필터를 지원한다.

### 공개 문서와 FE

- 로컬 API SSOT에서 질문 작성자·사람 답변자 제약 제거
- `questionTitle`을 질문방 제목으로 사용
- 성공 시 canonical chat route로 이동
- mutation pending 동안 `채팅 시작` 중복 호출 방지

## 11. 트랜잭션과 동시성

- 방 생성은 기존 `REQUIRES_NEW` 전체 작업 retry를 유지한다.
- `chat_rooms.room_key` unique 제약이 같은 요청을 한 방으로 수렴시킨다.
- 질문 조회는 soft-delete와 FK 컨텍스트의 일관성을 보장한다.
- 답변 또는 채택 트랜잭션과 lock 순서를 공유하지 않는다.
- 기존 방 API와 나가기는 요청자 멤버 행을, 1:1 메시지 전송은 두 멤버 행을 user ID 오름차순으로 `PESSIMISTIC_WRITE` 잠가 상태 전이를 직렬화한다.
- 메시지로 비활성 상대를 활성화할 때 현재 최대 메시지 ID를 cutoff로 저장한 뒤 같은 트랜잭션에서 새 메시지를 저장한다.
- 동시 메시지는 첫 트랜잭션만 cutoff를 갱신하고, 대기한 트랜잭션은 이미 활성화된 멤버를 no-op 처리한다.
- 나가기와 메시지 전송이 경쟁해도 멤버 행 잠금 순서에 따라 `나가기 후 새 메시지 노출` 또는 `새 메시지까지 숨긴 뒤 나가기` 중 하나로 일관되게 끝난다.

## 12. 테스트 전략

### 질문방 생성

- 질문 작성자가 아닌 사용자도 유효한 질문과 대상 사용자로 질문방 생성 성공
- 대상 사용자가 해당 질문에 답변하지 않았어도 성공
- 채택 상태와 무관하게 동일 요청 계약으로 성공
- 친구가 아니어도 성공
- 본인 대상 거부
- 삭제된 질문·사용자 거부
- 양방향 차단 각각 거부
- 같은 질문·같은 두 사용자의 순서가 바뀌어도 같은 방 반환
- 같은 두 사용자라도 질문이 다르면 다른 방 반환
- 동시 요청은 방 하나와 멤버 두 명으로 수렴

### 나가기와 이력 가시성

- 나가도 방, 멤버십, 메시지는 물리 삭제되지 않음
- 비활성 멤버가 활성화될 때만 `visibleAfterMessageId`가 현재 방 최대 메시지 ID로 갱신됨
- `joinedAt`은 활성화 전후 동일함
- 활성 멤버에 대한 반복 생성 호출은 cutoff no-op
- 새 방은 두 멤버가 활성 상태이고 cutoff가 `NULL`
- 기존 방 POST는 비활성 요청자만 활성화하고 비활성 대상은 그대로 유지
- 양쪽이 나간 방에서 한 사용자가 POST해도 그 사용자만 활성화
- 메시지 전송 직전에만 비활성 수신자가 활성화됨
- 활성화 이후 이전 메시지는 목록과 cursor로 조회되지 않음
- 활성화 후 첫 새 메시지는 정상 노출됨
- 상대방이 나가지 않았다면 상대방에게는 기존 전체 이력이 유지됨
- 안 읽은 수에 이전 메시지가 포함되지 않음
- 방 목록의 마지막 메시지에 이전 메시지가 노출되지 않음
- 새 메시지 없는 활성화 직후 `lastMessage=null`, `unreadCount=0`
- 신고·관리자 문맥 조회는 보존된 이전 메시지를 계속 조회할 수 있음
- 그룹 채팅 이력 정책은 변경되지 않음

### 계약

- 요청 필드 validation
- 성공 응답의 `roomType=question`, `questionId`, `questionTitle`
- 인증·CSRF·차단 오류 매핑
- 질문 작성자·답변자 관계 불일치 `FORBIDDEN`이 더 이상 발생하지 않음
- 질문방에 `NOT_FRIENDS`가 발생하지 않음

## 13. 수용 기준

- [ ] 질문방 생성 코드가 `AnswerRepository`를 사용하지 않는다.
- [ ] 질문 작성자·답변자·채택 여부를 질문방 권한으로 검사하지 않는다.
- [ ] 친구가 아닌 두 사용자도 질문방을 만들 수 있다.
- [ ] self, 사용자 존재, 차단 등 공통 1:1 규칙은 유지한다.
- [ ] 같은 질문과 같은 두 사용자는 하나의 roomId를 사용한다.
- [ ] 질문이 다르면 같은 두 사용자라도 별도 방이다.
- [ ] 응답과 목록·상세가 `roomType=question`, `questionId`, `questionTitle`을 제공한다.
- [ ] 질문 제목이 화면의 채팅방 label로 사용된다.
- [ ] 나가도 동일 방과 메시지는 보존된다.
- [ ] 같은 방이 다시 활성화돼도 나간 사용자에게 이전 메시지가 보이지 않는다.
- [ ] 기존 방 POST는 요청자만 활성화하며 상대 멤버 상태를 바꾸지 않는다.
- [ ] 메시지 목록, cursor, unread, lastMessage가 같은 message-ID cutoff를 적용한다.
- [ ] 별도의 재참여 API나 질문방 전용 생명주기 제약을 추가하지 않는다.
- [ ] 관련 단위·repository·통합·controller 테스트가 통과한다.

## 14. 구현 작업 묶음

### Packet A — 질문방을 1:1 타입으로 단순화

- `ChatService`에서 질문 작성자·답변자 검사와 `AnswerRepository` 제거
- 기존 API, room type, questionId, room key, questionTitle 유지
- 관련 단위·동시성·controller 계약 테스트 수정

### Packet B — 1:1 메시지 가시성 보정

- `visible_after_message_id` migration과 `ChatMember.activateAfter(cutoff)` 추가
- 요청자·수신자 멤버의 명시적 row lock과 역할별 활성화 적용
- history, cursor, unread, lastMessage의 사용자별 cutoff 적용
- direct/question 공통 회귀 테스트 추가

### Packet C — 계약 동기화

- 로컬 API SSOT와 기능 문서에서 관계 제약 제거
- FE가 `questionTitle`을 label로 사용하도록 타입과 화면 계약 확인

## 15. 결정 기록과 잔여 위험

### 확정 결정

- 질문 채팅은 `questionId`가 붙은 일반 1:1 채팅 타입이다.
- 질문방 생성 권한에 답변·채택 관계를 사용하지 않는다.
- 친구관계는 요구하지 않고 공통 차단 정책은 유지한다.
- 기존 `RoomType.question`, question-scoped room key, `questionTitle`을 사용한다.
- 나가기는 방 삭제가 아니며 과거 이력 비노출은 사용자별 가시성 기준으로 처리한다.
- 별도 재참여 도메인이나 API를 만들지 않는다.

### 잔여 위험·후속 정책

- 사용자가 방 생성 후 상대를 차단했을 때 기존 방의 송수신을 막을지는 전역 채팅 정책으로 별도 결정한다.
- soft-delete된 질문방의 제목 보존과 방 유지 여부는 질문 삭제 정책 범위다.
- 현재 단일 PostgreSQL의 증가하는 `message_id`를 cutoff 순서로 사용한다. 메시지 ID 발급 전략이나 DB가 분산되면 이 가정도 함께 재검토한다.
- 현재 FE가 `questionTitle`을 직접 소비하지 않는다면 추가 질문 조회와 fallback이 남을 수 있다.
