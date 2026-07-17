# 모임 채팅 일정 관리 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 모임 채팅방 참가자가 one-time 일정의 제목·시간·위치를 CRUD하고, 서버가 계산한 권한으로 타인 일정을 신고하거나 방장이 삭제할 수 있게 한다.

**Architecture:** 기존 `meeting_schedules`와 `reports`를 additive하게 확장한다. 일정 CRUD는 `MeetingService`가 소유하고, 신고는 `MeetingScheduleReportService`가 기존 Report 저장소와 관리자 신고함을 재사용한다. 새 화면 권한은 서버가 `MeetingScheduleItem`에 capability로 내려준다.

**Tech Stack:** Spring Boot, JPA/Hibernate, PostgreSQL, JUnit/Mockito, 기존 migration/deploy helper.

## Guardrails

- 이 워크트리 밖의 파일과 운영 DB에는 손대지 않는다.
- 사용자 작성 일정 생성·수정은 one-time 모임만 지원한다. recurring 회차 생성기는 변경하지 않는다.
- 기존 모임 생성에 쓰는 `CreateMeetingScheduleRequest`를 새 제목/위치 필수 contract로 바꾸지 않는다.
- migration은 v29→v31 순서를 지키고 production에는 적용하지 않는다.
- 전체 suite 대신 변경 모듈의 focused test와 compile만 실행한다.
- PATCH의 과거·완료·취소 일정은 기존 `409 SCHEDULE_NOT_CANCELLABLE` envelope를 유지하고, report의 비신고 가능 일정은 `404 SCHEDULE_NOT_FOUND`로 문서화한다. DELETE의 공개 409 계약은 바꾸지 않는다.

## File map

| Area | Primary files |
| --- | --- |
| Schedule domain/API | `app-main/src/main/java/shinhan/fibri/ieum/main/meeting/{domain,repository,dto,service,controller}/*` |
| Report/admin integration | `app-main/src/main/java/shinhan/fibri/ieum/main/{report,admin/report}/*` |
| Schema delivery | `db/schema.sql`, `db/migrations/*`, `deploy/scripts/apply-admin-dashboard-migrations.sh`, deploy workflow/tests |
| Focused tests | `app-main/src/test/java/shinhan/fibri/ieum/main/{meeting,report,admin/report}/*` |

## Implementation tasks

- [ ] **Task 1 — Lock the schedule wire/domain contract with focused tests.**
  - Add/extend `MeetingScheduleTest` and `MeetingServiceTest` cases for title/location mutation, legacy null schedule fields falling back through pin label then address, multiple future one-time schedules, own edit/delete, host deletion of another member's schedule, and recurring create/update rejection.
  - Extend `MeetingSchedule` with nullable legacy-safe `title` and `locationName`, plus a domain `update(...)` transition that does not alter ownership or recurrence sequence.
  - Add dedicated managed create/update request DTOs with `@NotBlank` and length validation; keep the meeting-creation request unchanged.
  - Extend `MeetingScheduleItem` and `MeetingSchedulesResponse` to carry title, locationName, `canEdit`, `canDelete`, and `canReport`; define legacy title/location fallbacks in the service projection.

- [ ] **Task 2 — Implement one-time schedule CRUD and server capabilities.**
  - Add PATCH routing alongside the existing GET/POST/DELETE schedule routes in `MeetingController`.
  - In `MeetingService`, serialize the new managed mutation route on the meeting row, preserve joined/kicked validation, allow multiple future one-time managed schedules without changing the legacy `addSchedule` compatibility path, reject manual creation/update for recurring meetings, and re-compute `meetings.meeting_at` after each mutation.
  - Resolve a schedule through its meeting and reject absent/soft-deleted/foreign IDs as the existing meeting validation envelope requires.
  - Enforce: creator may edit/delete only future scheduled entries; host/admin may delete other future scheduled entries; no one may report their own schedule; only joined members can report another active schedule.
  - Build `canEdit/canDelete/canReport` at read time from the same predicates used by mutations. Record AS-BUILT state errors: PATCH/DELETE non-mutable schedules use `409 SCHEDULE_NOT_CANCELLABLE`; non-reportable schedules use `404 SCHEDULE_NOT_FOUND`.

- [ ] **Task 3 — Add schedule report persistence and manual review flow.**
  - First add focused `MeetingScheduleReportServiceTest`/`ReportContextSnapshotFactoryTest` cases for a joined user reporting another member's schedule, rejected self/non-member/kicked cases, immutable snapshot content, and `aiReviewState=cancelled`.
  - Add `schedule` to `ReportTargetType`, nullable `Report.schedule` association, schedule-report factory/service path, controller endpoint `POST /meetings/{meetingId}/schedules/{scheduleId}/report`, and its validated request/response DTOs.
  - Let the existing AI worker ignore manual schedule reports through the cancelled state; do not add a second review pipeline.

- [ ] **Task 4 — Make schedule reports visible and safe in the administrator workflow.**
  - Extend `AdminReportRepository` list/detail projections with an explicit schedule branch and target-deleted handling.
  - Extend `AdminReportJsonSanitizer` allowlisting for the schedule snapshot rather than treating every non-message target as an answer.
  - Add focused sanitizer/repository tests for live and physically deleted schedule targets.

- [ ] **Task 5 — Deliver the additive database contract.**
  - Add `v29_meeting_schedule_details.sql` for nullable title/location columns and the corresponding canonical `schema.sql` shape.
  - Add `v30_report_schedule_target_enum.sql` containing only the PostgreSQL enum value addition; commit visibility before v31 uses it.
  - Add `v31_report_schedule_target.sql` for `reports.schedule_id`, indexes, XOR/manual-review constraints, and the target-integrity trigger. Preserve existing message/answer behavior.
  - Update migration application helper/workflows and their focused shell/integration coverage so new deployments and upgrades receive v29–v31 exactly once.

- [ ] **Task 6 — Verify the module and record the as-built contract.**
  - Run only the affected meeting/report/admin focused Gradle tests and `./gradlew :app-main:compileJava`.
  - Compare controller DTOs and migration names with this document. Mark task checkboxes only after command output is green.
  - Leave API SSOT/Notion edits to the integration owner after the implementation is verified and merged with dependent frontend work.

## Acceptance checklist

- [ ] A one-time meeting can hold multiple future managed schedules.
- [ ] Creator edit/delete and host deletion use service-side authorization, not UI-only checks.
- [ ] List items contain truthful capability flags and legacy-safe display values.
- [ ] Schedule reports appear in the existing admin queue as manual-review records.
- [ ] v29–v31 are valid ordered PostgreSQL migrations and no production migration was run.
