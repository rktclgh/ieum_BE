# 강퇴 후 모임 재입장 영구 차단 — 실행 계획

> **Issue:** #155
> **Branch:** `155-test-kicked-rejoin-ban`
> **Base:** `develop`

1. `join`의 기존 participant 조회가 잠금 없이 이뤄지는 경합을 잠금 조회 호출·repository `PESSIMISTIC_WRITE` 계약 테스트로 고정한다.
2. join·leave·kick의 기존 participant 조회를 같은 `FOR UPDATE` repository method로 통일하고, `kicked`에 대한 `KICKED_MEMBER` 거부와 chat member 무복구를 유지한다.
3. 기존 Web MVC `403 KICKED_MEMBER` mapping과 새 service 잠금 회귀를 함께 검증한다.
4. 대상 회귀 테스트, 변경 범위 diff check를 실행하고 PR을 생성한다.
