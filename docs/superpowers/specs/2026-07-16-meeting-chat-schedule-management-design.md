# 모임 채팅 일정 관리 (#160) — 백엔드 설계

## 1. 목표

모임 채팅방의 joined 참여자가 일정별 제목·시간·위치를 등록하고, 작성자는 자신의 미래 일정을 수정·삭제하며, 방장은 다른 참여자의 일정을 삭제할 수 있게 한다. 타인의 일정은 신고할 수 있으며 신고는 기존 관리자 신고함에서 수동으로 검토한다.

## 2. 범위와 비범위

### 범위

- `meeting_schedules`에 일정별 `title`, `location_name`을 additive하게 저장한다.
- one-time 모임에서 여러 개의 미래 일정을 생성·조회·수정·취소한다.
- 조회 응답이 서버 권한 계산값 `canEdit`, `canDelete`, `canReport`를 제공한다.
- 일정 신고를 기존 `reports`/관리자 신고함에 수동 검토 대상으로 추가한다.
- DB 정본, 배포 migration helper, 로컬 API SSOT와 Notion을 구현 결과에 맞춰 동기화한다.

### 비범위

- 새 pin, 좌표, 장소 검색 데이터 모델 또는 일정별 지도 마커
- 반복 모임 규칙의 생성·수정·삭제 재설계
- 전체 회귀 테스트 또는 새 테스트 프레임워크

반복 모임의 회차는 현재 `MeetingScheduleMaintenanceService`가 `meeting_recurrence_rules`와 `sequence_no`를 사용해 확장한다. 사용자 작성 일정을 섞으면 회차 수·다음 시각 계산이 깨지므로, 이번 이슈에서 **새 일정 등록과 수정은 one-time 모임에만 허용**한다. 기존 recurring 회차 조회/취소 동작은 변경하지 않는다.

## 3. 데이터 모델 결정

`meeting_schedules`를 새 테이블로 분리하지 않고 확장한다.

```text
meeting_schedules
├─ title VARCHAR(100) NULL
└─ location_name VARCHAR(200) NULL
```

- 새 채팅 일정은 title/locationName을 반드시 저장한다.
- 기존 모임 생성·반복 회차와 이미 존재하는 행은 nullable 상태로 보존한다. 조회 시 `title`은 meeting title, `locationName`은 meeting pin label 또는 address로 fallback한다. 과거 데이터를 재작성하지 않는다.
- 위치 좌표는 일정에 복제하지 않는다. 이 기능이 요구하는 위치는 카드에 표시할 문자열이다.
- 한 one-time 모임에는 여러 `scheduled` 행이 공존할 수 있다. `MeetingService.addSchedule`의 단일 active-schedule 409 제한을 제거하되, meeting row pessimistic lock과 `sequence_no` 증가로 동시 등록을 직렬화한다.
- `meetings.meeting_at`은 계속 가장 이른 active schedule의 legacy cache다. 생성·수정·취소 후 `findNextActiveStartsAt`으로 다시 계산한다.

## 4. 공개 API 계약

### 4.1 조회

`GET /api/v1/meetings/{meetingId}/schedules?from=&to=`의 각 item은 다음을 반환한다.

```json
{
  "scheduleId": 31,
  "title": "용산 와인바에서 봅시다",
  "locationName": "용산역 1번 출구",
  "startsAt": "2026-07-20T19:00:00+09:00",
  "endsAt": "2026-07-20T21:00:00+09:00",
  "status": "scheduled",
  "createdByUserId": 7,
  "canEdit": true,
  "canDelete": true,
  "canReport": false
}
```

기존 `from`/`to` 범위·KST response 규칙을 유지한다. capability는 화면 추측용 값이 아니라 서버가 현재 사용자·방장·일정 상태를 기준으로 계산한 값이다.

### 4.2 생성·수정·삭제

```http
POST  /api/v1/meetings/{meetingId}/schedules
PATCH /api/v1/meetings/{meetingId}/schedules/{scheduleId}
DELETE /api/v1/meetings/{meetingId}/schedules/{scheduleId}
```

POST/PATCH body는 같다.

```json
{
  "title": "용산 와인바에서 봅시다",
  "locationName": "용산역 1번 출구",
  "startsAt": "2026-07-20T19:00:00+09:00",
  "endsAt": "2026-07-20T21:00:00+09:00"
}
```

- `title`: non-blank, 최대 100자
- `locationName`: non-blank, 최대 200자
- `startsAt`: 미래 시각
- `endsAt`: optional이지만 제공 시 startsAt보다 뒤
- POST 성공은 `201 {"scheduleId":31}`, PATCH 성공은 수정된 item, DELETE 성공은 `204`이다.

### 4.3 일정 신고

```http
POST /api/v1/meetings/{meetingId}/schedules/{scheduleId}/report
```

```json
{ "reason": "spam", "detail": "광고성 일정입니다" }
```

성공은 `201 {"reportId":91}`이다. 신고 행은 일정 snapshot과 작성자를 보존하고 `aiReviewState=cancelled`로 생성된다. 따라서 기존 AI message-review queue를 건드리지 않으며 관리자 수동 검토만 사용한다.

## 5. 권한·오류 계약

| 사용자와 일정의 관계 | 생성 | 수정 | 삭제 | 신고 |
| --- | --- | --- | --- | --- |
| joined 일반 참여자, 본인 일정 | one-time만 가능 | 미래 scheduled만 | 미래 scheduled만 | 불가 |
| joined 일반 참여자, 타인 일정 | 가능 | 불가 | 불가 | 가능 |
| 방장, 본인 일정 | one-time만 가능 | 미래 scheduled만 | 미래 scheduled만 | 불가 |
| 방장, 타인 일정 | one-time만 가능 | 불가 | 미래 scheduled만 | 가능 |
| admin | API 권한은 host와 같음 | 본인 일정만 | 모든 미래 scheduled | 타인 일정만 |

- left/non-member는 `403 NOT_MEETING_MEMBER`, kicked는 `403 KICKED_MEMBER`다.
- meeting/schedule 조합이 없거나 soft-deleted면 `404 SCHEDULE_NOT_FOUND`다.
- 타인의 수정·삭제, 본인 일정 신고, 과거/완료/취소 일정 변경은 기존 meeting validation envelope의 `403 SCHEDULE_PERMISSION_DENIED` 또는 `400 VALIDATION_FAILED`로 정규화한다.
- UI capability는 보조 장치이며 위 검증은 service에서 다시 수행한다.

## 6. 신고 데이터와 migration

기존 `reports` 정본을 확장한다. snapshot만 저장하는 별도 테이블을 만들지 않는다.

```text
ReportTargetType = message | answer | schedule
reports.schedule_id -> meeting_schedules.schedule_id (ON DELETE SET NULL)
```

- `Report.scheduleReport(...)`는 schedule FK, schedule 작성자 `reported_user_id`, immutable schedule snapshot을 저장한다.
- 기존 message/answer report는 데이터·AI 상태·조회 결과가 바뀌지 않는다.
- PostgreSQL enum 신규 값은 같은 transaction에서 CHECK/trigger에 쓰지 않는다. migration을 두 단계로 나눈다.
  1. `v29_meeting_schedule_details.sql`: 일정 title/locationName 확장
  2. `v30_report_schedule_target_enum.sql`: `ALTER TYPE report_target_type ADD VALUE IF NOT EXISTS 'schedule'`
  3. `v31_report_schedule_target.sql`: schedule FK/index, target XOR/수동검토 CHECK, report integrity trigger, admin query 지원
- v30이 commit된 뒤 v31이 실행되어 enum visibility 규칙을 지킨다.
- `AdminReportRepository`의 list/detail target CASE와 `AdminReportJsonSanitizer`는 schedule branch를 추가한다. schedule FK가 physical delete로 NULL이 되어도 snapshot으로 evidence와 target id를 보존한다.

채팅 답장 이슈 #161의 migration은 v32로 예약하며, 최종 통합 시 #160을 먼저 merge/rebase한 뒤 #161을 올린다. production DB에는 이 이슈 단계에서 직접 적용하지 않는다.

## 7. 구현 경계

| 영역 | 책임 |
| --- | --- |
| `main/meeting/domain/MeetingSchedule` | title/locationName, `update(...)` 도메인 전이 |
| `main/meeting/dto/*Schedule*` | 채팅 일정 create/update/list capability contract |
| `main/meeting/controller/MeetingController` | PATCH route와 existing schedule route 연결 |
| `main/meeting/service/MeetingService` | one-time 규칙, 권한, active cache 재계산, capability 산출 |
| `main/report/*` | schedule report 생성 및 snapshot |
| `main/admin/report/*` | schedule target을 관리자 목록/상세에 안전하게 노출 |
| `db/schema.sql`, `db/migrations/v29..v31`, deploy helper/workflows | 신규 설치와 운영 증분 schema를 일치 |

## 8. 최소 검증

새 기능별 모듈 테스트만 작성한다.

1. MeetingService: one-time 다중 생성, 본인 수정/삭제, host 타인 삭제, participant 타인 거절, recurring create/update 거절, next active cache 갱신.
2. ReportService/Report: joined 타인 schedule 신고의 manual state, self/non-member/kicked/not-found 거절.
3. PostgreSQL migration: legacy schedule fallback columns, v30→v31 enum/constraint/trigger, schedule target physical delete 보존.
4. Admin report repository/sanitizer: schedule target id·deleted 여부·snapshot allowlist.
5. deploy helper의 v29~v31 copy/permission/apply-contract test.

마지막에 변경 모듈의 focused Gradle tests와 compile을 실행한다. 전체 test suite는 이 이슈의 수용 조건이 아니다.

## 9. 문서 완료 조건

코드와 위 최소 검증이 끝난 뒤만 `code/api/API-SPEC.md`에 schedule GET/POST/PATCH/DELETE/report를 AS-BUILT 계약으로 갱신한다. 같은 본문을 Notion API 문서로 동기화하고, 코드·로컬 SSOT·Notion이 일치할 때만 구현완료로 표시한다.
