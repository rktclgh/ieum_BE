# 채팅 메시지 발신자 프로필 URL 계약 설계

## 목적

채팅방 상세 API는 활성 멤버만 반환한다. 따라서 이탈한 사용자의 과거 메시지를 렌더링할 때 멤버 목록으로 프로필 사진을 찾으면 아바타가 비게 된다. 메시지 자체가 발신자 프로필 URL을 제공해 이 문제를 해결한다.

## 계약

`ChatMessageResponse`와 `WsMessageEvent`에 다음 nullable 필드를 `senderNickname` 다음에 추가한다.

```java
String senderProfileImageUrl
```

값은 `ProfileImageUrls.of(message.getSender())`로 생성한다.

- 프로필 파일이 있으면 `/api/v1/files/{fileId}`
- 프로필 파일이 없으면 `null`
- 메시지 REST 조회, 방 목록의 `lastMessage`, STOMP `/topic/rooms/{roomId}` 이벤트가 모두 같은 필드를 사용한다.

## 선택 근거

- 메시지 테이블에 스냅샷 컬럼을 추가하지 않아 DB 마이그레이션이 필요 없다.
- 기존 `Message.sender`와 파일 URL 공통 헬퍼를 재사용하므로 새 조회나 N+1을 만들지 않는다.
- 프로필 변경 후 과거 메시지도 현재 대화 상태 프로필을 반영한다.
- 방 상세의 활성 멤버만 노출하는 현재 입·퇴장 정책을 변경하지 않는다.

## 비목표

- 파일 스트림 권한, 방 멤버십, 메시지 가시성, Web Push payload는 변경하지 않는다.
- 모임 사진은 채팅 메시지 계약이 아니라 프론트엔드가 기존 모임 상세 API에서 소비한다.

## 검증 기준

1. `ChatMessageService.send`의 REST 반환값과 발행 이벤트가 프로필 URL을 모두 가진다.
2. REST `GET /api/v1/chat/rooms/{roomId}/messages` JSON에 `senderProfileImageUrl`이 직렬화된다.
3. STOMP 구독자가 같은 필드를 역직렬화한다.
4. 프로필이 없는 발신자는 `null`을 유지한다.
