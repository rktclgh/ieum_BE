# 채택답변 KG 관계 후보 및 운영자 승격 설계

## 상태

- 결정일: 2026-07-17
- 결정: 채택답변은 자동으로 후보만 만들고, 운영자 승인 뒤에만 KG 관계가 된다.
- 작업 브랜치: `feat/kg-promotion-approval`

## 목표

기존 채택답변 Vector RAG ingestion을 유지하면서, 채택된 사람 답변에서 구조화된 관계 후보를 비동기로 추출한다. 후보는 어떤 검색에도 사용하지 않으며, 운영자가 근거를 확인하고 승인한 경우에만 기존 `knowledge_relations`에 삽입한다.

## 범위

### 포함

- `accepted_human_answer` source가 `ready`가 된 뒤의 관계 후보 추출 작업
- 지속 가능한 retry/lease를 가진 extraction task
- `pending`, `approved`, `rejected`, `invalidated` 후보 lifecycle
- 관리자 후보 목록·상세·승인·반려 API와 `/admin/knowledge/` UI
- 승인 시 `knowledge_relations` 삽입과 관리자 감사 로그를 같은 DB transaction으로 처리
- 채택 취소·비활성화·삭제된 source의 승격 차단과 후보 무효화

### 제외

- 채택답변의 자동 KG 승격
- 이미 존재하는 채택답변의 일괄 backfill
- 새로운 graph database, 다중 hop 탐색, 엔티티 alias/동의어 테이블
- 자유 형식 predicate, 웹 grounding, 외부 웹 source의 자동 관계 추출
- 승인된 relation의 별도 `active` 컬럼. 기존 source eligibility가 relation 노출을 결정한다.

## 핵심 결정

`knowledge_relations`에는 승인된 relation만 존재한다. 따라서 질문 RAG의 기존 KG query는 후보 상태를 알 필요가 없고, 승인되지 않은 모델 출력이 검색 근거가 되는 경로도 없다.

자동 작업은 `app-ai`가 소유한다. 운영자라는 인증·감사·HTTP 경계와 승인 transaction은 `app-main`이 소유한다. 두 모듈은 같은 PostgreSQL을 사용하므로, `app-main`은 후보의 상태 전이, relation 삽입, `admin_audit_logs` append를 하나의 local DB transaction으로 실행한다. `app-main`은 source/chunk 생성이나 AI extraction task를 생성하지 않는다.

이 경계는 별도 내부 HTTP proxy를 추가하지 않으면서도, public admin API·권한·감사 동작을 기존 관리자 도메인과 동일하게 유지한다.

predicate allowlist는 `common` 모듈의 `KnowledgeRelationPredicate` enum 하나를 SSOT로 둔다. `app-ai` extractor/retrieval와 `app-main` 승인 validation이 같은 enum을 사용해 문자열 allowlist가 분기되지 않게 한다.

## 후보 생성 흐름

```text
사람 답변 채택
  → 기존 accepted-answer ingestion
  → knowledge_sources / knowledge_chunks가 ready로 확정
  → relation extraction task를 같은 transaction에서 pending으로 생성
  → app-ai scheduler/lane이 lease claim
  → 정제된 질문·답변 문서에서 relation 후보 추출·검증
  → knowledge_relation_candidates(status=pending)
  → 운영자 승인 또는 반려
  → 승인 시에만 knowledge_relations 삽입
```

기존 embedding 실패는 기존 ingestion retry 정책으로만 처리한다. embedding이 성공해 source가 `ready`가 된 뒤의 relation extraction 실패는 Vector RAG availability를 되돌리거나 source를 failed로 만들지 않는다. 관계 추출 task만 retry/dead 처리한다. worker는 `@Scheduled` redispatch와 bounded in-memory lane을 함께 사용해 process restart 뒤에도 pending/retry task를 회수한다.

## 데이터 모델

현재 `develop`의 마지막 migration은 v34이므로 이 브랜치에서는 `v35_knowledge_relation_candidates.sql`을 추가한다. merge 직전 `develop`의 migration 번호를 다시 확인하고 충돌이 있으면 아직 미배포인 이 파일만 다음 빈 번호로 rename한다.

### `knowledge_relation_extraction_tasks`

- `source_id` PK/FK `knowledge_sources(source_id)`; 하나의 source는 하나의 extraction lifecycle만 가진다.
- `status`: `pending | processing | retry | completed | dead | invalidated`
- `lease_token`, `lease_until`, `attempts`, `next_attempt_at`, `last_error_code`, `last_error_message`, timestamps
- `status = 'processing'`일 때만 lease 값이 존재하는 check constraint
- pending/retry claim index와 expired processing lease index

### `knowledge_relation_candidates`

- `candidate_id` PK
- `source_id`, `evidence_chunk_id`, `source_content_hash`, `candidate_fingerprint`
- `subject`, `predicate`, `object`, `confidence`
- `evidence_excerpt`: source chunk에 실제로 존재하는 정제된 근거 문장
- `extractor_model`, `status`, `version`
- `reviewed_by`, `reviewed_at`, `rejection_reason`, `promoted_relation_id`, timestamps
- 같은 source에서 같은 후보를 중복 생성하지 않도록 `(source_id, candidate_fingerprint)` unique index
- `predicate`는 공용 `KnowledgeRelationPredicate`의 KG v1 allowlist만 허용한다.
- 후보 source 또는 evidence chunk가 삭제되면 후보도 삭제된다. 관리자 결정 감사는 별도 `admin_audit_logs`에 남는다.

후보는 source가 아직 존재하지만 채택이 취소되거나 비활성화되면 `invalidated`가 된다. source가 완전히 삭제되면 candidate/relation은 FK cascade로 삭제되며, 기존 source eligibility 계약도 해당 relation을 검색에서 제외한다.

## 추출·검증 규칙

- 입력은 `AcceptedAnswerKnowledgeDocumentFactory`가 이미 개인정보를 제거한 문서만 사용한다.
- extractor는 구조화된 JSON만 반환하며 source당 최대 5개 relation만 허용한다.
- `KnowledgeRelationCandidateExtractor`는 기존 Bedrock `ChatModel`과 `temperature=0`을 사용하고, candidate feature flag가 켜진 경우에만 bean을 만든다. Bedrock client 조건에도 이 feature flag를 포함한다.
- 허용 predicate는 현재 KG v1 allowlist와 동일하다: `requires`, `applies_to`, `located_in`, `exception_of`, `prevents`, `supports`, `has_deadline`, `depends_on`, `reported_to`, `used_for`.
- subject/object는 trim 후 1~200자, predicate는 allowlist와 120자 제한을 통과해야 한다.
- evidence excerpt는 비어 있지 않고 정제된 chunk의 실제 substring이어야 한다.
- schema·길이·predicate·evidence 검증을 통과하지 못한 LLM output은 후보로 저장하지 않고 extraction 결과에 기록한다.
- extractor confidence는 운영자 우선순위를 위한 보조값일 뿐 자동 승인 기준이 아니다.

provider/transport 오류는 task retry backoff와 최대 시도 횟수를 적용한다. JSON schema·allowlist·evidence 검증 실패는 같은 source에 retry storm을 만들지 않도록 task를 `completed`로 끝내고 `last_error_code=invalid_extraction_output`만 남긴다.

`app-ai`는 `app.ai.features.accepted-answer-relation-candidates-enabled`(환경 변수 `APP_AI_FEATURES_ACCEPTED_ANSWER_RELATION_CANDIDATES_ENABLED`)라는 default false flag로 candidate extraction을 제어한다. 기존 `accepted-answer-ingestion-enabled`가 true여도 새 flag가 false이면 Vector RAG 적재만 계속 수행한다. production enable은 migration·worker·관리자 화면이 함께 배포된 뒤에 수행한다.

## 관리자 계약

모든 public endpoint는 기존 `/api/v1/admin/**` security boundary 아래에 둔다.

```text
GET  /api/v1/admin/knowledge/relation-candidates?status=&cursor=&size=
GET  /api/v1/admin/knowledge/relation-candidates/{candidateId}
POST /api/v1/admin/knowledge/relation-candidates/{candidateId}/approve
POST /api/v1/admin/knowledge/relation-candidates/{candidateId}/reject
```

승인 요청은 `{ version, subject, predicate, object }`를 받는다. 운영자는 추출된 entity 표기를 바로잡을 수 있지만 predicate는 allowlist select 중 하나만 선택한다. 반려 요청은 `{ version, reason? }`다.

상세 응답은 후보 triple, confidence, sanitized evidence excerpt, 원 질문 제목, 채택답변 excerpt, source 상태, 동일 source의 기존 relation, version을 포함한다. 원문 전체를 새로 복제하지 않는다.

### 상태 전이와 동시성

```text
pending --approve--> approved
pending --reject--> rejected
pending --source ineligible--> invalidated
```

- `approved`, `rejected`, `invalidated`는 terminal 상태다.
- `app-main` service는 candidate row를 `FOR UPDATE`로 잠그고 request version과 `pending` 상태를 확인한다.
- 이미 결정됐거나 version이 다르면 `409 KNOWLEDGE_CANDIDATE_CONCURRENTLY_CHANGED`를 반환한다. 프론트는 detail/list를 재조회한다.
- source eligibility(`ai_lock_eligible_accepted_answer`)를 같은 transaction에서 다시 확인한다. false면 candidate를 `invalidated`로 바꾸고 `409 KNOWLEDGE_CANDIDATE_SOURCE_INELIGIBLE`을 반환한다.
- 승인 시 edited triple을 검증한 뒤 `knowledge_relations`를 `(source_id, subject, predicate, object)` 기준으로 insert한다. 이미 존재하면 기존 relation을 재사용하고 candidate만 그 relation에 연결한다.
- candidate status update, relation insert, `AdminAuditAction.KNOWLEDGE_RELATION_APPROVED` 또는 `KNOWLEDGE_RELATION_REJECTED` append는 한 transaction이다.

기존 KG retrieval은 relation의 source가 ready/active이고 채택답변 eligibility를 만족하는지 이미 재검증한다. 승인 뒤 source가 채택 취소되면 relation을 별도로 재작성하지 않아도 retrieval에서 제외된다.

`app-main`의 작은 invalidation scheduler는 5분마다 아직 `pending`인 후보 중 source eligibility를 잃은 행을 bulk update해 운영 큐에서 제거한다. 이 scheduler가 지연되더라도 승인 transaction의 재검증이 최종 방어선이다.

## 프론트 UX

- 고정 정적 route `/admin/knowledge/`와 query 기반 detail URL을 사용한다.
- 목록은 상태, triple, source, confidence, evidence excerpt, 생성 시각을 보여 주고 `pending`을 기본 filter로 한다.
- 상세는 원 질문/답변 맥락, evidence excerpt, source eligibility, 동일 source relation, editable subject/object, predicate select, 승인/반려 control을 제공한다.
- mutation 중 해당 action만 비활성화한다. 409이면 canonical detail을 재조회하고 결정됨/무효화 안내를 보여 준다.
- loading, empty, error, invalid link는 기존 admin feature 패턴을 재사용한다.
- sidebar의 `지식` 항목은 이 기능의 실제 route/API와 같은 PR에서만 추가한다.

## 검증

- app-ai: extraction task claim/lease/retry/dead, source당 중복 후보 차단, malformed output·불허 predicate·근거 불일치 거부를 테스트한다.
- app-main: candidate list/detail, 승인/반려 validation, stale version, source ineligible, duplicate relation, 감사 log를 transaction test로 검증한다.
- FE: list/detail query, status filter, allowlist select, 409 convergence, 승인/반려 버튼 상태를 테스트한다.
- migration은 schema, deploy migration helper, workflow copy/permission list에 포함하고 실제 대상 DB에 명시적으로 적용·검증한다.
- smoke test는 disposable accepted human answer로 후보 생성→관리자 승인→relation retrieval 가능→채택 취소 후 retrieval 제외를 검증하고 생성 데이터를 정리한다.

## 후속 범위

관계 alias/canonical entity registry, 여러 독립 source의 자동 신뢰도 승격, historical backfill, relation edit/revoke UI는 후보 승인 운영량과 품질 데이터를 확인한 뒤 별도 기능으로 판단한다.
