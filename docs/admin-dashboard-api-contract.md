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
- AI 정책 CRUD 및 지식 탐색/승격 API
- 감사 로그 조회 UI/API, 보존 기간 정책

이 항목들은 관리자 대시보드 MVP의 AS-BUILT API로 취급하지 않는다.

## 문서 동기화

- 워크스페이스 집계 문서: `/Users/songchiho/Desktop/Hackerthon/code/api/API-SPEC.md`
- Notion API 명세: `Admin / 회원 역할 변경`, `Users / 내 정보 조회`, `Users / 회원 탈퇴`, `Auth / 이메일 로그인`
