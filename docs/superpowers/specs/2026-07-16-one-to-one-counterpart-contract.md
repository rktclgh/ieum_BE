# 1:1 채팅 상대 메타데이터 계약 설계

## 문제와 범위

`ChatRoomDetailResponse.members`는 활성 멤버만 반환한다. 한 사용자가 direct 또는 question 방을 나가면 그 사용자는 자신의 목록에서 제거되는 것이 맞다. 그러나 상대 사용자는 방에 계속 남아 있을 수 있고, 이때 활성 멤버 목록에는 본인만 남아 대표 프로필 이미지를 결정할 수 없다.

이 설계는 입·퇴장 정책을 바꾸지 않고, 남아 있는 사용자가 1:1 방의 상대 프로필을 렌더링할 수 있게 하는 응답 계약만 추가한다.

## 계약

`ChatRoomDetailResponse`에 다음 nullable 필드를 추가한다.

```java
ChatRoomMemberResponse counterpart
```

- `roomType`이 `direct` 또는 `question`이면 현재 요청 사용자가 아닌 방의 다른 멤버를 반환한다.
- 상대가 `left_at`을 가진 경우에도 반환한다.
- `group`이면 `null`이다.
- `members`는 기존처럼 활성 멤버만 반환한다.

`ChatRoomMemberResponse`를 재사용하므로 `userId`, `nickname`, `profileImageUrl`, `nationality`의 직렬화 형식은 기존 멤버 목록과 동일하다.

## 선택 근거

- `left_at` 사용자 목록을 새로 노출하지 않고 1:1 상대 하나만 제공한다.
- 새 테이블·마이그레이션·조회 쿼리가 필요 없다. `getRoom`이 이미 가져온 해당 방의 전체 `ChatMember`에서 선택한다.
- direct와 question은 모두 1:1 방이라는 기존 모델을 유지한다. 질문 전용 멤버십 제약이나 재입장 규칙을 만들지 않는다.
- 남아 있는 사용자의 목록/더보기 대표 이미지에만 쓰이며, 나간 사용자의 목록 복원이나 과거 메시지 가시성 변경을 유발하지 않는다.

## 경계 조건

- 현재 요청자가 활성 멤버가 아니면 기존처럼 `NotRoomMemberException`이다.
- 방에 다른 멤버가 없거나 데이터가 비정상이면 `counterpart`는 `null`이다.
- 상대에게 프로필 사진이 없으면 `profileImageUrl`은 `null`이며 프론트는 기존 빈 아바타 폴백을 사용한다.
- 메시지 발신자 아바타는 별도 `senderProfileImageUrl` 계약을 계속 사용한다. 이 필드는 메시지 렌더링을 대체하지 않는다.

## 검증 기준

1. active direct/question 방은 `members`와 `counterpart` 모두 현재 사용자가 아닌 상대를 올바르게 가리킨다.
2. 상대가 나간 direct/question 방에서 `members`에는 상대가 없지만 `counterpart`에는 상대 프로필이 남는다.
3. group 방의 `counterpart`는 `null`이다.
4. API 변경은 additive이며 DB 마이그레이션이 없다.
