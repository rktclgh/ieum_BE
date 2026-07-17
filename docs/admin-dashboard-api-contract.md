# 관리자 대시보드 API 계약

이 문서는 백엔드 저장소가 소유하는 관리자 대시보드 AS-BUILT 계약이다. 공통 워크스페이스 문서인 `code/api/API-SPEC.md`와 Notion `API 명세서 (1)`에는 같은 내용을 동기화한다.

## 공통 규칙

- API base path는 `/api/v1`이다.
- 별도 `/admin/login` API는 없다. `POST /api/v1/auth/login`을 사용하고 응답 `role`을 확인한다.
- 인증은 `access_token`, `refresh_token`, `csrf_token` 쿠키를 사용한다. 상태 변경 요청은 `csrf_token`과 동일한 `X-CSRF-Token` 헤더가 필요하다.
- `/api/v1/admin/**`는 DB canonical role까지 `admin`인 활성 세션만 허용한다. 익명은 `401 AUTHENTICATION_REQUIRED`, 일반 사용자는 `403 ACCESS_DENIED`다.
- `GET /api/v1/users/me`는 `role: "user"|"admin"`을 반환한다.
- Redis 세션의 `authVersion`이 없거나 DB `users.auth_version`과 다르면 fail-closed한다. 배포 이전 legacy 세션은 한 번 재로그인해야 한다.
- 성공한 관리자 mutation은 도메인 변경과 같은 PostgreSQL 트랜잭션에서 `admin_audit_logs` 한 건을 기록한다. no-op 또는 실패한 mutation은 기록하지 않는다.

## AS-BUILT 엔드포인트

| Method | Path | 성공 |
| --- | --- | --- |
| `POST` | `/auth/login` | `200 {userId, role, passwordResetRequired}` + 인증 쿠키 |
| `GET` | `/users/me` | `200 UserMeResponse` (`role` 포함) |
| `GET` | `/admin/stats/users?from=&to=` | `200 UserStatsResponse` |
| `GET` | `/admin/stats/content?from=&to=` | `200 ContentStatsResponse` |
| `GET` | `/admin/stats/reports?from=&to=` | `200 ReportStatsResponse` |
| `GET` | `/admin/stats/overview?from=&to=&bucket=day` | `200 AdminStatsOverviewResponse` |
| `GET` | `/admin/users?status=&q=&cursor=&size=` | `200 CursorPage<AdminUserItem>` |
| `GET` | `/admin/users/{userId}` | `200 AdminUserDetailResponse` |
| `POST` | `/admin/users/{userId}/sanctions` | `201 CreateSanctionResponse` |
| `POST` | `/admin/users/{userId}/activate` | `204` |
| `PATCH` | `/admin/users/{userId}/role` | `204` |
| `GET` | `/admin/reports?status=&aiReviewState=&decision=&cursor=&size=` | `200 CursorPage<AdminReportItem>` |
| `GET` | `/admin/reports/{reportId}` | `200 AdminReportDetailResponse` |
| `POST` | `/admin/reports/{reportId}/confirm` | `204` |
| `POST` | `/admin/reports/{reportId}/dismiss` | `204` |
| `GET` | `/admin/inquiries?status=&cursor=&size=` | `200 CursorPage<AdminInquiryItem>` |
| `GET` | `/admin/inquiries/{inquiryId}` | `200 AdminInquiryItem` |
| `POST` | `/admin/inquiries/{inquiryId}/answer` | `204` |
| `GET` | `/admin/knowledge/relation-candidates?status=&cursor=&size=` | `200 AdminKnowledgeCandidateListResponse` |
| `GET` | `/admin/knowledge/relation-candidates/{candidateId}` | `200 AdminKnowledgeCandidateDetailResponse` |
| `POST` | `/admin/knowledge/relation-candidates/{candidateId}/approve` | `200 AdminKnowledgeCandidateDecisionResponse` |
| `POST` | `/admin/knowledge/relation-candidates/{candidateId}/reject` | `200 AdminKnowledgeCandidateDecisionResponse` |

## KPI overview

- `from`, `to`는 KST 날짜(`YYYY-MM-DD`)이며 양 끝을 포함한다. 생략하면 KST 오늘까지 최근 30일을 사용한다.
- 허용 기간은 1~366일이고 현재 `bucket`은 `day`만 허용한다.
- 응답은 적용된 `from`, `to`, `bucket`, 기간 합계 `summary`, 날짜별 0-채움 `series`, 기간과 무관한 현재 운영 대기열 `queues`를 반환한다.
- 채택률은 사람 답변(`ai_generated=false`)만 분모·분자로 계산하며, 분모가 0이면 `0`이다.
- `queues.pendingReportCount`는 `status=pending`, `queues.retryReportCount`와 `queues.deadReportCount`는 미해결 신고(`status in pending, ai_reviewed`) 중 해당 AI work 상태만 집계한다. 확정/기각된 신고는 retry/dead queue에서 제외한다.

```json
{
  "from": "2026-07-01",
  "to": "2026-07-31",
  "bucket": "day",
  "summary": {
    "signupCount": 0,
    "activeUserCount": 0,
    "suspensionCount": 0,
    "questionCount": 0,
    "humanAnswerCount": 0,
    "acceptedHumanAnswerCount": 0,
    "acceptedRate": 0.0,
    "reportCount": 0,
    "aiReviewedCount": 0,
    "confirmedCount": 0,
    "dismissedCount": 0,
    "sanctionCount": 0
  },
  "series": [
    {
      "date": "2026-07-01",
      "signupCount": 0,
      "activeUserCount": 0,
      "questionCount": 0,
      "humanAnswerCount": 0,
      "acceptedHumanAnswerCount": 0,
      "reportCount": 0,
      "aiReviewedCount": 0,
      "confirmedCount": 0,
      "dismissedCount": 0,
      "sanctionCount": 0
    }
  ],
  "queues": {
    "pendingReportCount": 0,
    "retryReportCount": 0,
    "deadReportCount": 0,
    "pendingInquiryCount": 0
  }
}
```

- 실패 코드는 공통 인증/인가 규칙 외에 `400 VALIDATION_FAILED`(날짜 형식 등 바인딩 실패), `400 INVALID_STATS_RANGE`(from > to 또는 366일 초과), `400 INVALID_STATS_BUCKET`(`bucket`이 `day`가 아님)를 반환한다.

## KG 관계 후보 검토

- 채택된 사람 답변이 Vector source/chunk로 `ready`가 된 뒤에만 비동기 관계 후보를 만들며, 후보는 운영자 승인 전에는 검색에 사용되지 않는다.
- 후보 상태는 `pending`, `approved`, `rejected`, `invalidated`다. 목록의 기본 filter는 `pending`이다.
- 상세는 후보 triple·근거 excerpt·source 적격성·질문/답변 맥락·동일 source의 기존 relation·검토 메타데이터를 반환한다.
- 승인 요청은 `{version, subject, predicate, object}`, 반려 요청은 `{version, reason?}`다. predicate는 KG v1 allowlist만 허용한다.
- 승인은 source/relation 연결과 `KNOWLEDGE_RELATION_APPROVED` 감사 로그를 한 transaction으로 기록한다. 반려는 `KNOWLEDGE_RELATION_REJECTED` 감사 로그를 기록한다.
- stale version 또는 이미 종결된 후보는 `409 KNOWLEDGE_CANDIDATE_CONCURRENTLY_CHANGED`다. 승인 시 source가 더 이상 적격하지 않으면 후보를 `invalidated`로 보존하고 `409 KNOWLEDGE_CANDIDATE_SOURCE_INELIGIBLE`을 반환한다.

## 역할 변경

```http
PATCH /api/v1/admin/users/42/role
X-CSRF-Token: <csrf_token cookie value>
Content-Type: application/json

{"role":"admin"}
```

- 허용 role은 `user`, `admin`이다.
- 동일 role은 `204` no-op이다.
- 자기 강등은 `409 CANNOT_CHANGE_OWN_ROLE`이다.
- 마지막 관리자 강등은 `409 LAST_ADMIN_REQUIRED`다.
- 세션의 admin role이 DB와 달라졌으면 `409 ADMIN_ROLE_REQUIRED`다.
- 실제 변경 시 `auth_version`을 증가시키고 commit 이후 대상 사용자의 Redis 세션을 파기한다.
- 관리자의 `DELETE /users/me`는 `409 ADMIN_WITHDRAWAL_FORBIDDEN`이며 쿠키를 만료하지 않는다.

## mutation 수렴 규칙

- 신고 확정/기각의 반대 결정 충돌은 `409 REPORT_ALREADY_RESOLVED`다.
- 신고가 lock 사이 변경되면 `409 REPORT_CONCURRENTLY_CHANGED`다.
- 문의 단건 조회에서 대상이 없으면 `404 INQUIRY_NOT_FOUND`다.
- 이미 답변된 문의는 `409 INQUIRY_ALREADY_ANSWERED`다.
- 프론트는 mutation 결과를 로컬에서 확정하지 않고 canonical 조회를 한 번 수행한다. 네트워크 결과가 불확실해도 재조회 전까지 mutation lock을 유지한다.

## TARGET — 현재 미구현

- 콘텐츠 삭제/비노출 API
- 회원 영구 삭제 API
- AI 제재 pending-review API
- AI 정책 CRUD 및 후보 검토 외 지식 탐색/시각화 API
- 감사 로그 조회 UI/API, 보존 기간 정책

이 항목들은 관리자 대시보드 MVP의 AS-BUILT API로 취급하지 않는다.

## 문서 동기화

- 워크스페이스 집계 문서: `/Users/songchiho/Desktop/Hackerthon/code/api/API-SPEC.md`
- Notion API 명세: `Admin / 회원 역할 변경`, `Admin / 문의 목록`, `Admin / 문의 상세`, `Admin / 문의 답변`, `Users / 내 정보 조회`, `Users / 회원 탈퇴`, `Auth / 이메일 로그인`
