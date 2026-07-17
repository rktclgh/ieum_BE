# 강퇴 후 모임 재입장 영구 차단 (#155) — 백엔드 설계

## 문제

`MeetingService.join`과 `leave`는 모임 participant를 잠금 없이 읽던 반면 `kick`은 participant 행을 변경한다. 상태 변경이 겹치면 마지막 flush가 `kicked`를 `joined` 또는 `left`로 덮어써 강퇴 이력이 사라질 수 있다.

## 결정

- 기존 participant가 있는 join은 `MeetingParticipantRepository.findByIdMeetingIdAndIdUserIdForUpdate`로 같은 participant 행을 잠근다.
- 자진 leave도 같은 participant 행 잠금을 사용한다. 따라서 kick과 leave가 겹치면 먼저 행 잠금을 잡은 요청의 상태 전이가 확정되고, kick이 먼저면 이후 leave는 `joined` 조건에서 거부된다.
- participant를 잠근 뒤 `kicked`면 어떤 join 부수효과도 만들지 않고 `403 KICKED_MEMBER`를 반환한다.
- join·leave·kick은 같은 participant 행 잠금으로 직렬화한다. join이 먼저 끝나면 kick이 최종 `kicked`로 만들고, kick이 먼저 끝나면 join·leave는 `kicked`를 읽어 거부한다.
- 신규 participant는 기존 행이 없으므로 기존 meeting-row 정원 잠금과 PK 보호를 유지한다.

## 범위와 검증

- 유일한 모임 진입점 `POST /meetings/{id}/join`만 대상이다. 초대·승인·별도 group-chat join API는 현재 없다.
- 단위 테스트는 join이 잠금 조회를 사용하고 `kicked`일 때 chat member 복구 없이 거부함을 고정한다.
- Web MVC의 `403 KICKED_MEMBER` 매핑은 기존 controller test로 유지하고, service regression은 join·leave·kick이 같은 participant 행 잠금 조회를 사용하며 kicked join에서 chat member를 복구하지 않음을 검증한다.
