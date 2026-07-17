-- ============================================================
-- FiBri Schema v22
-- v21 대비 변경:
--   [23] 채택 답변 지식 적격성 잠금:
--        question → pin → answer 순서로 active/accepted human-answer 상태를 잠근다.
--        SECURITY DEFINER 함수는 pg_catalog search_path와 완전 수식 객체를 사용한다.
--        기존 DB 증분: db/migrations/v22_accepted_answer_eligibility_lock.sql
-- v20 대비 변경:
--   [22] 답변 신고 리뷰 후속:
--        reports.answer_id FK 이름과 신고 대상 무결성 트리거 진단명을 정본과 일치시킨다.
--        기존 DB 증분: db/migrations/v21_report_target_review_followup.sql
-- v19 대비 변경:
--   [21] 답변 신고 대상:
--        reports를 message/answer tagged union으로 확장하고 AI 답변의 nullable 신고 대상 사용자를 허용한다.
--        답변 신고는 ai_review_state=cancelled인 수동 검토 전용이며 기존 메시지 AI 작업목록은 유지한다.
--        기존 DB 증분: db/migrations/v20_answer_report_target.sql
-- v18 대비 변경:
--   [20] 지식 원천 import lifecycle:
--        retry 상태 컬럼, active logical external-ref unique, active location GiST index.
--        과거 revision은 inactive로 보존하고 같은 논리 원천의 active revision은 하나만 허용한다.
--        기존 DB 증분: db/migrations/v19_knowledge_import_lifecycle.sql
-- v17 대비 변경:
--   [19] 지식 원천 content hash 무결성:
--        knowledge_sources.content_hash를 lowercase SHA-256으로 DB에서도 강제한다.
--        기존 DB 증분: db/migrations/v18_knowledge_source_content_hash.sql
-- v16 대비 변경:
--   [18] 질문 AI checkpoint:
--        Query Analyzer 결과 버전을 ai_question_tasks에 저장해 retry 재사용 여부를 판정한다.
--        짧은 lease-fenced checkpoint TX는 분석·임베딩·stage 전이만 저장한다.
--        기존 DB 증분: db/migrations/v17_question_ai_checkpoints.sql
-- v15 대비 변경:
--   [17] 질문 AI finalization 잠금:
--        active question+pin 잠금 함수를 추가하고 AI answer 함수도 두 row를 함께 잠근다.
--        두 SECURITY DEFINER 함수는 pg_catalog search_path와 완전 수식 객체를 사용한다.
--        기존 DB 증분: db/migrations/v16_question_ai_finalization_lock.sql
-- v14 대비 변경:
--   [16] 질문 AI 공유 티켓과 답변 알림:
--        app-main이 질문 생성 TX에서 ai_question_tasks pending 티켓을 함께 생성하고,
--        app-ai가 처리 결과와 AI 답변을 기록하며, app-main이 완료 티켓을 알림으로 ACK한다.
--        질문 query checkpoint HNSW 제거, Gemini Embedding 2 모델 CHECK,
--        notifications 답변 출처·event key와 완료 티켓 ACK를 추가한다.
--        기존 DB 증분: db/migrations/v15_question_ai_ticket_notification.sql
-- v13 대비 변경:
--   [15] 신고 AI 검토 작업목록 확장:
--        reports에 스냅샷 해시, 작업 상태/시도/리스/오류, 구조화된 판단 결과를 추가한다.
--        기존 reports의 pending 행은 즉시 처리 가능한 작업으로, 그 외 legacy 행은 dead로 보존한다.
--        기존 DB 증분: db/migrations/v14_report_worklist_expand.sql
-- v12 대비 변경:
--   [14] app-ai v2 expand:
--        ai_question_tasks lease fencing/geo/answer outcome/generation provider,
--        knowledge_sources lifecycle/ingestion/metadata/geo columns,
--        same-source relation evidence FK, Gemini embedding model CHECK,
--        ai_report_policy_rules 정책 테이블과 LISTEN/NOTIFY invalidation.
--        기존 DB 증분: db/migrations/v13_app_ai_v2_expand.sql
-- v11 대비 변경:
--   [13] app-ai 테이블 11개를 4개로 통합:
--        ai_question_tasks, knowledge_sources, knowledge_chunks, knowledge_relations.
--        신고 판단 재호출 결과 캐시(ai_inference_runs)는 폐기하고 집행 멱등은 app-main reports가 담당.
--        기존 DB 증분: db/migrations/v12_ai_table_consolidation.sql
-- v10 대비 변경:
--   [12] 위치 스냅샷 정본을 pins로 통합:
--        pins.location + address + detail_address + label.
--        questions/meetings는 pin_id만 참조하며 meetings.place_name은 폐기.
--        운영 데이터가 없는 초기화 전제라 place_name 백필·호환 컬럼은 두지 않음.
--        기존 DB 증분: db/migrations/v8_pin_location_snapshot.sql
-- v9 대비 변경:
--   [11] app-ai 책임 분리: 질문 불변·soft-delete, AI 작업/임베딩/KG 테이블 분리,
--        질문 생성 Redis 이벤트 폐기(공유 DB 티켓 co-commit), 신고 AI 권고와 app-main 집행 분리.
-- v8 대비 변경:
--   [10] 모임 타입·일정·반복 규칙 (설계 v2):
--        meetings.type(one_time/recurring) + meeting_schedules + meeting_recurrence_rules.
--        날짜의 정본은 meeting_schedules — meetings.meeting_at 은 legacy 캐시(다음 회차).
--        맵 노출 = 파생: open && EXISTS(scheduled && visible_until>=now()) && 뷰어 not kicked.
--        visible_until = starts_at 의 KST 당일 23:59:59 (재활성화 = 방장이 새 일정 추가).
--        기존 DB 증분: db/migrations/v9_meeting_schedules.sql
-- v6 대비 변경:
--   [8] countries 참조 테이블 + users.nationality FK (국적 코드 검증).
--       enum 대신 테이블 — 국가명 변경(튀르키예·에스와티니)·영토 추가를
--       ALTER TYPE 마이그레이션 없이 데이터(INSERT/UPDATE)로 관리하기 위함.
--       국기·피커 목록은 프론트가 code/api/nationalities.csv 번들 소유(백엔드 미서빙).
--       시드 db/seed_countries.sql (199개국)
-- v5 대비 변경:
--   [1] embedding 차원 1536 → 768 (현재 모델: gemini-embedding-2)
--   [2] AI 답변 표현: answers.is_ai 추가, author_id NULL 허용
--       (AI 답변 = is_ai=TRUE & author_id IS NULL — CHECK로 강제.
--        AI 시스템 유저 시딩 방식은 채택하지 않음: 사유는 db/DESIGN.md)
--   [3] 사용자 등급: users.accepted_count + users.grade (user_grade enum)
--       채택 수 집계를 매 조회마다 하지 않도록 증분 저장
--   [4] meetings.image_file_id(원본/배경사진) 추가 — 썸네일과 별도 보관
--   [5] meetings.deleted_at 추가 (모임 삭제 = soft-delete, 명세 반영)
--   [6] chat_rooms.direct_key → room_key 로 일반화:
--       direct 방("d:{minUid}:{maxUid}")뿐 아니라 질문(꼬리질문) 방
--       ("q:{questionId}:{minUid}:{maxUid}")의 중복 생성도 DB가 차단
--   [7] friendships.blocked_by 추가 — 차단 주체 기록 (누가 차단했는지 식별)
-- (v5: 신고 파이프라인 초안, files 청소 배치 2종. 신고 책임은 v10에서 재정의)
-- (v4: 삭제 정책 — 소프트 삭제 기본, 하드는 3년 배치/관리자 전용. files — S3 비노출)
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE gender_type AS ENUM ('male', 'female', 'other');
CREATE TYPE auth_provider AS ENUM ('email', 'google', 'apple', 'kakao');
CREATE TYPE pin_type AS ENUM ('question', 'meeting');
CREATE TYPE meeting_status AS ENUM ('open', 'closed', 'cancelled');
CREATE TYPE meeting_type AS ENUM ('one_time', 'recurring');                       -- [신규 v9]
CREATE TYPE meeting_schedule_status AS ENUM ('scheduled', 'completed', 'cancelled'); -- [신규 v9]
CREATE TYPE recurrence_frequency AS ENUM ('daily', 'weekly', 'monthly');          -- [신규 v9]
CREATE TYPE participant_status AS ENUM ('joined', 'left', 'kicked');              -- kicked = 영구 밴 (노출 차단)
CREATE TYPE friendship_status AS ENUM ('pending', 'accepted', 'blocked');
CREATE TYPE room_type AS ENUM ('direct', 'group', 'question');
CREATE TYPE report_reason AS ENUM ('spam', 'ad', 'abuse', 'obscene', 'harassment', 'etc');
CREATE TYPE report_status AS ENUM ('pending', 'ai_reviewed', 'confirmed', 'dismissed');
CREATE TYPE report_target_type AS ENUM ('message', 'answer', 'schedule');
CREATE TYPE ai_recommendation AS ENUM ('temporary_suspend', 'hold', 'dismiss'); -- AI 권고(명령 아님)
CREATE TYPE ai_report_decision AS ENUM ('suspend', 'hold', 'normal');
CREATE TYPE sanction_type AS ENUM ('temporary', 'permanent');
CREATE TYPE sanction_decision_source AS ENUM ('ai_recommendation', 'admin'); -- 집행은 항상 app-main
CREATE TYPE sanction_review_status AS ENUM ('pending_review', 'confirmed', 'dismissed', 'not_required');
CREATE TYPE ai_job_status AS ENUM ('pending', 'processing', 'retry', 'completed', 'cancelled', 'dead');
CREATE TYPE ai_job_stage AS ENUM ('discovered', 'retrieving', 'generating', 'validating', 'persisting', 'analyzing', 'embedding', 'web_grounding');
CREATE TYPE knowledge_source_type AS ENUM ('curated', 'accepted_human_answer', 'verified_external');
CREATE TYPE notification_type AS ENUM ('meeting', 'question', 'chat', 'friend', 'location', 'system');
CREATE TYPE inquiry_status AS ENUM ('pending', 'answered');
CREATE TYPE user_role AS ENUM ('user', 'admin');
CREATE TYPE user_status AS ENUM ('active', 'suspended');
CREATE TYPE user_grade AS ENUM ('bronze', 'silver', 'gold', 'platinum', 'diamond');  -- [신규 v6]

-- ============================================================
-- users
-- ============================================================
CREATE TABLE users (
    user_id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255),
    provider auth_provider NOT NULL DEFAULT 'email',
    provider_uid VARCHAR(255),
    password_hash VARCHAR(255),
    password_reset_required BOOLEAN NOT NULL DEFAULT FALSE,
    nickname VARCHAR(50) NOT NULL,
    birth_date DATE,
    gender gender_type,
    nationality VARCHAR(2),
    profile_file_id UUID,                        -- files FK (files 정의 뒤에 추가)
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    last_location GEOGRAPHY(Point, 4326),        -- 저장: ST_SetSRID(ST_MakePoint(lng, lat), 4326) ← 경도 먼저!
    last_active_at TIMESTAMPTZ,
    role user_role NOT NULL DEFAULT 'user',
    status user_status NOT NULL DEFAULT 'active',
    auth_version BIGINT NOT NULL DEFAULT 0,
    accepted_count INTEGER NOT NULL DEFAULT 0 CHECK (accepted_count >= 0),  -- [신규 v6] 채택된 답변 수 (증분 유지)
    grade user_grade NOT NULL DEFAULT 'bronze',                             -- [신규 v6] 등급 (임계 돌파 시 갱신)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_users_auth_version_nonnegative CHECK (auth_version >= 0),
    CHECK (provider = 'email' OR provider_uid IS NOT NULL),
    CHECK (provider <> 'email' OR email IS NOT NULL)
);

CREATE UNIQUE INDEX uidx_users_email_provider ON users(email, provider) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uidx_users_provider_uid   ON users(provider, provider_uid) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uidx_users_nickname       ON users(nickname) WHERE deleted_at IS NULL;
CREATE INDEX idx_users_location ON users USING gist (last_location) WHERE deleted_at IS NULL;
CREATE INDEX idx_users_deleted  ON users(deleted_at) WHERE deleted_at IS NOT NULL;  -- 3년 경과 배치용

-- ============================================================
-- [삭제 정책] ★ 하드 DELETE는 아래 두 경로 외 금지 ★
--   1) 배치(3년 경과):  DELETE FROM users WHERE deleted_at < now() - interval '3 years';
--      (Spring @Scheduled 일 1회. 삭제 전 해당 유저 files의 s3_key 수집 → S3 오브젝트도 함께 삭제)
--   2) 관리자 영구삭제: 관리자 대시보드에서 role='admin' 검증 후에만 실행 (2단계 확인)
--   일반 탈퇴 = UPDATE users SET deleted_at = now()  (소프트 삭제)
--   ※ 소프트 삭제 유지 기간 동안 신고/제재/메시지 이력은 전부 보존됨
-- ============================================================

-- ============================================================
-- countries — 국적 코드 검증 참조 테이블 (ISO 3166-1 alpha-2)
--   용도: users.nationality FK 검증 + 서버측 국가명(관리자/AI/알림/디버깅).
--   enum 대신 테이블: 국가명 변경(튀르키예·에스와티니)·영토 추가를
--   ALTER TYPE 마이그레이션 없이 데이터(INSERT/UPDATE)로 관리한다.
--   ★ 국기·피커·국가 목록은 프론트가 정적 목록(code/api/nationalities.csv)을
--     번들로 소유한다 — 백엔드는 목록/국기를 서빙하지 않고 코드 검증·저장·반환만.
--   시드 db/seed_countries.sql (199개국)
-- ============================================================
CREATE TABLE countries (
    code       VARCHAR(2) PRIMARY KEY CHECK (code ~ '^[A-Z]{2}$'),  -- ISO 3166-1 alpha-2 (대문자)
    name_ko    VARCHAR(60) NOT NULL,               -- 서버측 표기용(피커 표시는 프론트 소유)
    name_en    VARCHAR(80) NOT NULL,
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,       -- 유효 코드 토글(삭제 대신 비활성)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- users.nationality(VARCHAR(2)) → countries.code FK.
-- NULL 허용(소셜 가입 사후 입력). 유저 INSERT 전 countries 시드가 선행돼야 한다.
ALTER TABLE users ADD CONSTRAINT fk_users_nationality
    FOREIGN KEY (nationality) REFERENCES countries(code);

-- ============================================================
-- files — 모든 업로드 파일의 단일 관리 지점
--   업로드: 프론트 → 백엔드(presign 요청) → files INSERT + presigned PUT URL 발급
--           → 프론트가 S3에 직접 PUT → 완료 통지 → uploaded_at 기록
--   조회:   클라이언트는 GET /files/{file_id} 만 호출 →
--           백엔드가 s3_key로 S3에서 읽어 스트리밍 응답 (S3 주소 완전 비노출)
-- ============================================================
CREATE TABLE files (
    file_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),  -- 외부 노출용 불투명 ID
    uploader_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    s3_key TEXT NOT NULL UNIQUE,                 -- 실제 S3 오브젝트 키 (외부 비노출)
    content_type VARCHAR(100),
    size_bytes BIGINT,
    uploaded_at TIMESTAMPTZ,                     -- NULL = presign만 발급되고 업로드 미완료 (주기 청소 대상)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_files_uploader ON files(uploader_id);
CREATE INDEX idx_files_pending ON files(created_at) WHERE uploaded_at IS NULL;  -- 미완료 업로드 청소용

ALTER TABLE users ADD CONSTRAINT fk_users_profile_file
    FOREIGN KEY (profile_file_id) REFERENCES files(file_id) ON DELETE SET NULL;

-- ============================================================
-- user_settings (1:1은 회원가입 트랜잭션에서 INSERT로 보장)
-- ============================================================
CREATE TABLE user_settings (
    user_id BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    language VARCHAR(5) NOT NULL DEFAULT 'ko',
    camera_permission BOOLEAN NOT NULL DEFAULT FALSE,
    push_permission BOOLEAN NOT NULL DEFAULT TRUE,
    notify_all BOOLEAN NOT NULL DEFAULT TRUE,
    notify_meeting BOOLEAN NOT NULL DEFAULT TRUE,
    notify_question BOOLEAN NOT NULL DEFAULT TRUE,
    notify_radius_km SMALLINT NOT NULL DEFAULT 5 CHECK (notify_radius_km IN (3, 5, 10)),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- pins
-- ============================================================
CREATE TABLE pins (
    pin_id BIGSERIAL PRIMARY KEY,
    author_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    pin_type pin_type NOT NULL,
    location GEOGRAPHY(Point, 4326) NOT NULL,                            -- 공간 계산 정본: ST_MakePoint(lng, lat)
    address VARCHAR(255) NOT NULL CHECK (btrim(address) <> ''),          -- 도로명 우선, 없으면 지번 주소
    detail_address VARCHAR(200) NOT NULL DEFAULT '',                     -- optional: 미입력은 빈 문자열
    label VARCHAR(100) NOT NULL DEFAULT '',                              -- optional: 장소명/짧은 표시명
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX idx_pins_location ON pins USING gist (location) WHERE deleted_at IS NULL;
CREATE INDEX idx_pins_author ON pins(author_id);

-- ============================================================
-- questions / answers
-- ============================================================
CREATE TABLE questions (
    question_id BIGSERIAL PRIMARY KEY,
    pin_id BIGINT NOT NULL UNIQUE REFERENCES pins(pin_id) ON DELETE CASCADE,
    author_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    is_resolved BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ                       -- [신규 v10] app-main soft-delete, AI 취소 정본
);
CREATE INDEX idx_questions_author ON questions(author_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_questions_deleted ON questions(deleted_at) WHERE deleted_at IS NOT NULL;

CREATE TABLE question_images (
    image_id BIGSERIAL PRIMARY KEY,
    question_id BIGINT NOT NULL REFERENCES questions(question_id) ON DELETE CASCADE,
    file_id UUID NOT NULL REFERENCES files(file_id) ON DELETE CASCADE,
    sort_order SMALLINT NOT NULL DEFAULT 0,
    UNIQUE (question_id, sort_order)
);
CREATE INDEX idx_qimages_file ON question_images(file_id);

-- AI 답변 = is_ai TRUE & author_id NULL (CHECK로 상호 강제)
-- 사람 답변 = is_ai FALSE & author_id NOT NULL
-- ※ AI 시스템 유저를 users에 시딩하는 방식은 쓰지 않는다 (db/DESIGN.md 참고)
CREATE TABLE answers (
    answer_id BIGSERIAL PRIMARY KEY,
    question_id BIGINT NOT NULL REFERENCES questions(question_id) ON DELETE CASCADE,
    author_id BIGINT REFERENCES users(user_id) ON DELETE CASCADE,   -- [변경 v6] NULL 허용 (AI 답변)
    is_ai BOOLEAN NOT NULL DEFAULT FALSE,                           -- [신규 v6]
    content TEXT NOT NULL,
    is_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (is_ai = (author_id IS NULL))                             -- [신규 v6] AI ⟺ 작성자 없음
);
CREATE INDEX idx_answers_question ON answers(question_id);
CREATE INDEX idx_answers_author ON answers(author_id);
CREATE INDEX idx_answers_accepted_question ON answers(question_id) WHERE is_accepted;
CREATE UNIQUE INDEX uidx_one_ai_answer_per_question ON answers(question_id) WHERE is_ai;

-- accepted-answer knowledge final persist TX에서 호출한다.
-- app-main의 채택/삭제 흐름과 같은 question → pin → answer 순서로 잠근다.
CREATE FUNCTION public.ai_lock_eligible_accepted_answer(p_answer_id BIGINT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_question_id BIGINT;
    v_pin_id BIGINT;
BEGIN
    SELECT q.question_id, q.pin_id
      INTO v_question_id, v_pin_id
      FROM public.questions q
     WHERE q.question_id = (
               SELECT candidate.question_id
                 FROM public.answers candidate
                WHERE candidate.answer_id = p_answer_id
           )
       AND q.deleted_at IS NULL
       FOR SHARE OF q;
    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;

    PERFORM 1
      FROM public.pins p
     WHERE p.pin_id = v_pin_id
       AND p.deleted_at IS NULL
       AND p.pin_type = 'question'::public.pin_type
       FOR SHARE OF p;
    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;

    PERFORM 1
      FROM public.answers a
     WHERE a.answer_id = p_answer_id
       AND a.question_id = v_question_id
       AND a.is_accepted
       AND NOT a.is_ai
       AND a.author_id IS NOT NULL
       FOR SHARE OF a;

    RETURN FOUND;
END;
$$;

REVOKE ALL ON FUNCTION public.ai_lock_eligible_accepted_answer(BIGINT) FROM PUBLIC;

-- ieum_ai role에는 domain table row lock 대신 이 함수 EXECUTE만 허용한다.
-- checkpoint/final persist TX 안에서 호출하며, question과 pin이 모두 active일 때만 잠근다.
CREATE OR REPLACE FUNCTION public.ai_lock_active_question(p_question_id BIGINT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    PERFORM 1
      FROM public.questions q
      JOIN public.pins p ON p.pin_id = q.pin_id
     WHERE q.question_id = p_question_id
       AND q.deleted_at IS NULL
       AND p.deleted_at IS NULL
       FOR SHARE OF q, p;

    RETURN FOUND;
END;
$$;

REVOKE ALL ON FUNCTION public.ai_lock_active_question(BIGINT) FROM PUBLIC;

-- ieum_ai role에는 answers 직접 INSERT 대신 이 함수 EXECUTE만 허용한다.
-- final AI persist TX 안에서 호출하며, 삭제 질문에는 답변을 만들지 않는다.
CREATE OR REPLACE FUNCTION public.insert_ai_answer_if_active(p_question_id BIGINT, p_content TEXT)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_answer_id BIGINT;
BEGIN
    IF p_content IS NULL OR btrim(p_content) = '' THEN
        RAISE EXCEPTION 'AI answer content must not be blank' USING ERRCODE = '22023';
    END IF;

    PERFORM 1
      FROM public.questions q
      JOIN public.pins p ON p.pin_id = q.pin_id
     WHERE q.question_id = p_question_id
       AND q.deleted_at IS NULL
       AND p.deleted_at IS NULL
       FOR SHARE OF q, p;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Active question not found' USING ERRCODE = 'P0002';
    END IF;

    SELECT answer_id
      INTO v_answer_id
      FROM public.answers
     WHERE question_id = p_question_id
       AND is_ai;
    IF v_answer_id IS NOT NULL THEN
        RETURN v_answer_id;
    END IF;

    INSERT INTO public.answers(question_id, author_id, is_ai, content)
    VALUES (p_question_id, NULL, TRUE, p_content)
    RETURNING answer_id INTO v_answer_id;
    RETURN v_answer_id;
EXCEPTION
    WHEN unique_violation THEN
        SELECT answer_id
          INTO v_answer_id
          FROM public.answers
         WHERE question_id = p_question_id
           AND is_ai;
        RETURN v_answer_id;
END;
$$;

REVOKE ALL ON FUNCTION public.insert_ai_answer_if_active(BIGINT, TEXT) FROM PUBLIC;

CREATE TABLE answer_images (
    image_id BIGSERIAL PRIMARY KEY,
    answer_id BIGINT NOT NULL REFERENCES answers(answer_id) ON DELETE CASCADE,
    file_id UUID NOT NULL REFERENCES files(file_id) ON DELETE CASCADE,
    sort_order SMALLINT NOT NULL DEFAULT 0,
    UNIQUE (answer_id, sort_order)
);
CREATE INDEX idx_aimages_file ON answer_images(file_id);

-- ============================================================
-- shared integration ticket: compact question processing / embedding / answer provenance
--   app-main은 question_id 티켓 생성, cancel_requested_at 명령,
--   answer_notification_processed_at ACK만 소유한다.
--   app-ai는 status/lease/embedding/evidence/AI answer 결과 전이를 소유한다.
--   질문당 하나의 row가 요청 티켓·작업 상태·embedding·답변 metadata·완료 신호를 함께 소유한다.
-- ============================================================
CREATE TABLE ai_question_tasks (
    question_id BIGINT PRIMARY KEY REFERENCES questions(question_id) ON DELETE CASCADE,
    status ai_job_status NOT NULL DEFAULT 'pending',
    stage ai_job_stage NOT NULL DEFAULT 'discovered',
    attempts SMALLINT NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until TIMESTAMPTZ,
    locked_by VARCHAR(100),
    lease_token UUID,
    analysis_version VARCHAR(80),
    geo_scope VARCHAR(24),
    geo_scope_confidence NUMERIC(5,4),
    region_context JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding vector(768),
    embedding_model VARCHAR(100),
    legacy_embedding_model_migrated BOOLEAN NOT NULL DEFAULT FALSE,
    answer_id BIGINT UNIQUE REFERENCES answers(answer_id) ON DELETE SET NULL,
    answer_outcome VARCHAR(32),
    generation_provider VARCHAR(40),
    generation_model VARCHAR(120),
    retrieval_config_version VARCHAR(80),
    fallback_reason VARCHAR(100),
    prompt_version VARCHAR(80),
    grounding_status VARCHAR(30) CHECK (grounding_status IN ('grounded', 'insufficient_evidence', 'ungrounded')),
    grounding_score NUMERIC(5,4) CHECK (grounding_score BETWEEN 0 AND 1),
    evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    last_error_code VARCHAR(100),
    last_error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancel_requested_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    answer_notification_processed_at TIMESTAMPTZ,
    CONSTRAINT ck_ai_question_tasks_processing_lease
        CHECK ((status = 'processing') = (lease_until IS NOT NULL AND locked_by IS NOT NULL AND lease_token IS NOT NULL)),
    CHECK ((embedding IS NULL) = (embedding_model IS NULL)),
    CONSTRAINT ck_ai_question_tasks_embedding_model
        CHECK (
            (
                NOT legacy_embedding_model_migrated
                AND (embedding_model IS NULL OR embedding_model = 'gemini-embedding-2')
            )
            OR (
                legacy_embedding_model_migrated
                AND status = 'completed'
                AND embedding_model = 'gemini-embedding-001'
            )
        ),
    CHECK (jsonb_typeof(evidence) = 'array' AND jsonb_array_length(evidence) <= 8),
    CONSTRAINT ck_ai_question_tasks_geo_scope
        CHECK (geo_scope IS NULL OR geo_scope IN ('general','regional','local','place_specific')),
    CONSTRAINT ck_ai_question_tasks_analysis_version
        CHECK (analysis_version IS NULL OR btrim(analysis_version) <> ''),
    CONSTRAINT ck_ai_question_tasks_geo_confidence
        CHECK (geo_scope_confidence IS NULL OR geo_scope_confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_ai_question_tasks_region_context
        CHECK (jsonb_typeof(region_context) = 'object'),
    CONSTRAINT ck_ai_question_tasks_answer_outcome
        CHECK (answer_outcome IS NULL OR answer_outcome IN ('local_grounded','web_grounded','insufficient_evidence','ungrounded')),
    CONSTRAINT ck_ai_question_tasks_completed CHECK (status <> 'completed' OR (
        completed_at IS NOT NULL
        AND embedding IS NOT NULL
        AND embedding_model IS NOT NULL
        AND answer_outcome IS NOT NULL
        AND grounding_status IS NOT NULL
        AND (
            (answer_outcome = 'insufficient_evidence' AND answer_id IS NULL)
            OR
            (answer_outcome IN ('local_grounded','web_grounded')
                AND answer_id IS NOT NULL
                AND generation_provider IS NOT NULL
                AND generation_model IS NOT NULL
                AND jsonb_array_length(evidence) > 0)
            OR
            (answer_outcome = 'ungrounded'
                AND answer_id IS NOT NULL
                AND generation_provider IS NOT NULL
                AND generation_model IS NOT NULL
                AND prompt_version IS NOT NULL
                AND grounding_status = 'ungrounded'
                AND jsonb_array_length(evidence) = 0)
        )
    )),
    CHECK (status <> 'cancelled' OR cancelled_at IS NOT NULL),
    CONSTRAINT ck_ai_question_tasks_answer_notification
        CHECK (answer_notification_processed_at IS NULL OR (status = 'completed' AND answer_id IS NOT NULL))
);
CREATE INDEX idx_ai_question_tasks_claim
    ON ai_question_tasks(status, next_attempt_at, created_at)
    WHERE status IN ('pending', 'retry');
CREATE INDEX idx_ai_question_tasks_expired_lease
    ON ai_question_tasks(lease_until)
    WHERE status = 'processing';
CREATE INDEX idx_ai_question_tasks_pending_notification
    ON ai_question_tasks(completed_at, question_id)
    INCLUDE (answer_id)
    WHERE status = 'completed'
      AND answer_id IS NOT NULL
      AND answer_notification_processed_at IS NULL;

-- ============================================================
-- app-ai owned: hybrid RAG knowledge store (same PostgreSQL)
--   AI 답변은 knowledge source로 적재하지 않는다.
-- ============================================================
CREATE TABLE knowledge_sources (
    source_id BIGSERIAL PRIMARY KEY,
    source_type knowledge_source_type NOT NULL,
    question_id BIGINT REFERENCES questions(question_id) ON DELETE CASCADE,
    answer_id BIGINT REFERENCES answers(answer_id) ON DELETE CASCADE,
    external_ref VARCHAR(500),
    content_hash CHAR(64) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    status VARCHAR(24) NOT NULL,
    last_error_code VARCHAR(100),
    last_error_message TEXT,
    deactivation_reason VARCHAR(200),
    ingestion_token UUID,
    ingestion_lease_until TIMESTAMPTZ,
    ingestion_attempts SMALLINT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    geo_scope VARCHAR(24),
    region_context JSONB NOT NULL DEFAULT '{}'::jsonb,
    anchor_location GEOGRAPHY(Point,4326),
    valid_until TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deactivated_at TIMESTAMPTZ,
    CHECK (source_type <> 'accepted_human_answer'
        OR (question_id IS NOT NULL AND answer_id IS NOT NULL)),
    CONSTRAINT ck_knowledge_sources_status
        CHECK (status IN ('pending','ready','failed','inactive','admin_suppressed')),
    CONSTRAINT ck_knowledge_sources_geo_scope
        CHECK (geo_scope IS NULL OR geo_scope IN ('general','regional','local','place_specific')),
    CONSTRAINT ck_knowledge_sources_region_context
        CHECK (jsonb_typeof(region_context) = 'object'),
    CONSTRAINT ck_knowledge_sources_metadata
        CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT ck_knowledge_sources_content_hash
        CHECK (btrim(content_hash) ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_knowledge_sources_ingestion_lease
        CHECK ((status = 'pending') = (ingestion_token IS NOT NULL AND ingestion_lease_until IS NOT NULL)),
    CONSTRAINT ck_knowledge_sources_ingestion_attempts
        CHECK (ingestion_attempts >= 0)
);
CREATE UNIQUE INDEX uidx_knowledge_source_answer
    ON knowledge_sources(answer_id) WHERE answer_id IS NOT NULL;
CREATE INDEX idx_knowledge_sources_active
    ON knowledge_sources(source_type, source_id) WHERE active;
CREATE INDEX idx_knowledge_sources_expired_ingestion
    ON knowledge_sources(ingestion_lease_until, source_id)
    WHERE status = 'pending';
CREATE UNIQUE INDEX uidx_knowledge_source_active_external_ref
    ON knowledge_sources(source_type, external_ref)
    WHERE active AND external_ref IS NOT NULL;
CREATE INDEX idx_knowledge_sources_active_anchor_location
    ON knowledge_sources USING gist(anchor_location)
    WHERE anchor_location IS NOT NULL AND active;

CREATE TABLE knowledge_chunks (
    chunk_id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES knowledge_sources(source_id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    chunk_order SMALLINT NOT NULL DEFAULT 0,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding vector(768) NOT NULL,
    embedding_model VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_id, chunk_order),
    CONSTRAINT uq_knowledge_chunks_source_chunk UNIQUE (source_id, chunk_id),
    CONSTRAINT ck_knowledge_chunks_embedding_model CHECK (embedding_model = 'gemini-embedding-2'),
    CHECK (jsonb_typeof(metadata) = 'object')
);
CREATE INDEX idx_knowledge_chunks_embedding_hnsw
    ON knowledge_chunks USING hnsw (embedding vector_cosine_ops);

CREATE TABLE knowledge_relation_extraction_tasks (
    task_id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES knowledge_sources(source_id) ON DELETE CASCADE,
    status VARCHAR(24) NOT NULL DEFAULT 'pending',
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    attempts SMALLINT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error_code VARCHAR(100),
    last_error_message TEXT,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_knowledge_relation_extraction_tasks_source UNIQUE (source_id),
    CONSTRAINT ck_knowledge_relation_extraction_tasks_status
        CHECK (status IN ('pending','processing','retry','completed','dead','invalidated')),
    CONSTRAINT ck_knowledge_relation_extraction_tasks_lease
        CHECK ((status = 'processing') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CONSTRAINT ck_knowledge_relation_extraction_tasks_attempts
        CHECK (attempts >= 0),
    CONSTRAINT ck_knowledge_relation_extraction_tasks_completed
        CHECK (status <> 'completed' OR completed_at IS NOT NULL)
);
CREATE INDEX idx_knowledge_relation_extraction_tasks_claim
    ON knowledge_relation_extraction_tasks(status, next_attempt_at, created_at, task_id)
    WHERE status IN ('pending','retry');
CREATE INDEX idx_knowledge_relation_extraction_tasks_expired_lease
    ON knowledge_relation_extraction_tasks(lease_until, task_id)
    WHERE status = 'processing';

CREATE TABLE knowledge_relation_candidates (
    candidate_id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES knowledge_sources(source_id) ON DELETE CASCADE,
    evidence_chunk_id BIGINT NOT NULL,
    candidate_fingerprint CHAR(64) NOT NULL,
    subject_text VARCHAR(200) NOT NULL,
    predicate VARCHAR(32) NOT NULL,
    object_text VARCHAR(200) NOT NULL,
    confidence NUMERIC(5,4) NOT NULL,
    evidence_excerpt TEXT NOT NULL,
    extraction_provider VARCHAR(80) NOT NULL,
    extraction_model VARCHAR(120) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'pending',
    version INTEGER NOT NULL DEFAULT 1,
    reviewer_user_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ,
    review_note TEXT,
    promotion_relation_id BIGINT,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_knowledge_relation_candidates_source_fingerprint
        UNIQUE (source_id, candidate_fingerprint),
    CONSTRAINT fk_knowledge_relation_candidates_same_source_evidence
        FOREIGN KEY (source_id, evidence_chunk_id)
        REFERENCES knowledge_chunks(source_id, chunk_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_relation_candidates_fingerprint
        CHECK (candidate_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_knowledge_relation_candidates_predicate
        CHECK (predicate IN (
            'requires','applies_to','located_in','exception_of','prevents',
            'supports','has_deadline','depends_on','reported_to','used_for'
        )),
    CONSTRAINT ck_knowledge_relation_candidates_status
        CHECK (status IN ('pending','approved','rejected','promoted')),
    CONSTRAINT ck_knowledge_relation_candidates_terms
        CHECK (btrim(subject_text) <> '' AND btrim(object_text) <> ''),
    CONSTRAINT ck_knowledge_relation_candidates_confidence
        CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_knowledge_relation_candidates_evidence
        CHECK (btrim(evidence_excerpt) <> ''),
    CONSTRAINT ck_knowledge_relation_candidates_version
        CHECK (version >= 1)
);
CREATE INDEX idx_knowledge_relation_candidates_review
    ON knowledge_relation_candidates(status, created_at, candidate_id)
    WHERE status = 'pending';
CREATE INDEX idx_knowledge_relation_candidates_source
    ON knowledge_relation_candidates(source_id, candidate_id);

CREATE TABLE knowledge_relations (
    relation_id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES knowledge_sources(source_id) ON DELETE CASCADE,
    subject VARCHAR(200) NOT NULL CHECK (btrim(subject) <> ''),
    predicate VARCHAR(120) NOT NULL CHECK (btrim(predicate) <> ''),
    object VARCHAR(200) NOT NULL CHECK (btrim(object) <> ''),
    confidence NUMERIC(5,4) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    evidence_chunk_id BIGINT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_id, subject, predicate, object),
    CONSTRAINT fk_knowledge_relations_same_source_evidence
        FOREIGN KEY (source_id, evidence_chunk_id)
        REFERENCES knowledge_chunks(source_id, chunk_id)
        ON DELETE CASCADE,
    CHECK (jsonb_typeof(metadata) = 'object')
);
CREATE INDEX idx_knowledge_relations_subject
    ON knowledge_relations(subject, predicate);
CREATE INDEX idx_knowledge_relations_object
    ON knowledge_relations(object, predicate);

ALTER TABLE knowledge_relation_candidates
    ADD CONSTRAINT fk_knowledge_relation_candidates_promotion_relation
    FOREIGN KEY (promotion_relation_id)
    REFERENCES knowledge_relations(relation_id)
    ON DELETE SET NULL;

CREATE TABLE ai_report_policy_rules (
    rule_id BIGSERIAL PRIMARY KEY,
    rule_code VARCHAR(100) NOT NULL UNIQUE CHECK (rule_code ~ '^[A-Z0-9][A-Z0-9_-]{2,99}$'),
    title VARCHAR(200) NOT NULL CHECK (btrim(title) <> ''),
    category VARCHAR(100) NOT NULL CHECK (btrim(category) <> ''),
    criteria TEXT NOT NULL CHECK (btrim(criteria) <> ''),
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('suspend','hold','normal')),
    severity VARCHAR(16) NOT NULL CHECK (severity IN ('low','medium','high','critical')),
    automatic_sanction_days SMALLINT,
    min_confidence NUMERIC(5,4) NOT NULL CHECK (min_confidence BETWEEN 0 AND 1),
    evidence_types VARCHAR(10) NOT NULL CHECK (evidence_types IN ('text','image','both')),
    priority INTEGER NOT NULL DEFAULT 0 CHECK (priority BETWEEN -1000 AND 1000),
    positive_examples JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(positive_examples)='array'),
    negative_examples JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(negative_examples)='array'),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1),
    content_hash CHAR(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    created_by BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    updated_by BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_report_policy_rules_automatic_sanction_days
        CHECK (automatic_sanction_days IS NULL OR (
            decision = 'suspend' AND automatic_sanction_days BETWEEN 1 AND 365
        )),
    CHECK (decision <> 'suspend' OR severity IN ('high','critical'))
);
CREATE INDEX idx_ai_report_policy_rules_snapshot
    ON ai_report_policy_rules(active, priority DESC, rule_code);

-- ============================================================
-- meetings
-- ============================================================
CREATE TABLE meetings (
    meeting_id BIGSERIAL PRIMARY KEY,
    pin_id BIGINT NOT NULL UNIQUE REFERENCES pins(pin_id) ON DELETE CASCADE,
    host_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    type meeting_type NOT NULL DEFAULT 'one_time',                        -- [신규 v9] 일회성/정기
    meeting_at TIMESTAMPTZ,                                               -- [v25] nullable legacy 캐시(다음 회차 시각) — 정본은 meeting_schedules
    max_members SMALLINT NOT NULL DEFAULT 2,
    image_file_id UUID REFERENCES files(file_id) ON DELETE SET NULL,      -- [신규 v6] 원본(배경사진)
    thumbnail_file_id UUID REFERENCES files(file_id) ON DELETE SET NULL,  -- 300x300 썸네일 (원본에서 생성)
    status meeting_status NOT NULL DEFAULT 'open',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,                                               -- [신규 v6] 모임 삭제 = soft-delete
    CHECK (max_members >= 2)
);
CREATE INDEX idx_meetings_host ON meetings(host_id);
CREATE INDEX idx_meetings_status_at ON meetings(status, meeting_at) WHERE deleted_at IS NULL;  -- [변경 v6] partial

CREATE TABLE meeting_participants (
    meeting_id BIGINT NOT NULL REFERENCES meetings(meeting_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    status participant_status NOT NULL DEFAULT 'joined',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (meeting_id, user_id)
);
CREATE INDEX idx_mparticipants_user ON meeting_participants(user_id);

-- ============================================================
-- meeting_schedules — 캘린더에 노출되는 회차 [신규 v9]
--   맵 노출 파생 규칙: meetings.status='open' AND deleted_at IS NULL
--     AND EXISTS(scheduled AND visible_until >= now()) AND 뷰어 not kicked.
--   visible_until = starts_at 의 KST 당일 23:59:59 (서버 계산·저장).
--   outdated(당일 경과) = EXISTS 거짓 — 상태 전이 없음. 재활성화 = 새 일정 추가.
-- ============================================================
CREATE TABLE meeting_schedules (
    schedule_id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT NOT NULL REFERENCES meetings(meeting_id) ON DELETE CASCADE,
    created_by BIGINT REFERENCES users(user_id) ON DELETE SET NULL,       -- [v25] 최초 작성자. 하드 삭제 시 일정은 보존
    title VARCHAR(100),                                                    -- [v29] 채팅에서 작성한 일정별 제목
    location_name VARCHAR(200),                                           -- [v29] 채팅에서 작성한 일정별 장소명
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ,
    visible_until TIMESTAMPTZ NOT NULL,
    status meeting_schedule_status NOT NULL DEFAULT 'scheduled',
    sequence_no INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (meeting_id, sequence_no),
    CHECK (ends_at IS NULL OR ends_at > starts_at),
    CHECK (visible_until >= starts_at)
);
CREATE INDEX idx_meeting_schedules_visible
    ON meeting_schedules(meeting_id, visible_until)
    WHERE deleted_at IS NULL AND status = 'scheduled';
CREATE INDEX idx_meeting_schedules_calendar
    ON meeting_schedules(starts_at, schedule_id)
    WHERE deleted_at IS NULL;

-- 정기 모임 반복 규칙 [신규 v9] — recurring 모임에만 1개
CREATE TABLE meeting_recurrence_rules (
    recurrence_rule_id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT NOT NULL UNIQUE REFERENCES meetings(meeting_id) ON DELETE CASCADE,
    frequency recurrence_frequency NOT NULL,
    interval_value SMALLINT NOT NULL DEFAULT 1,
    days_of_week SMALLINT[],                      -- weekly 필수. 1=MON..7=SUN
    day_of_month SMALLINT,                        -- monthly 필수. 없는 날짜인 달은 회차 미생성
    starts_on DATE NOT NULL,
    ends_on DATE,
    max_occurrences INT,
    timezone VARCHAR(40) NOT NULL DEFAULT 'Asia/Seoul',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (interval_value BETWEEN 1 AND 12),
    CHECK (max_occurrences IS NULL OR max_occurrences BETWEEN 1 AND 366),
    CHECK (ends_on IS NULL OR ends_on >= starts_on)
);

-- ============================================================
-- friendships
-- ============================================================
CREATE TABLE friendships (
    friendship_id BIGSERIAL PRIMARY KEY,
    requester_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    addressee_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    status friendship_status NOT NULL DEFAULT 'pending',
    blocked_by BIGINT REFERENCES users(user_id),                          -- [신규 v6] 차단 주체 (status='blocked'일 때)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (requester_id <> addressee_id),
    CHECK (status <> 'blocked' OR blocked_by IS NOT NULL),                -- [신규 v6] 차단이면 주체 필수
    CHECK (blocked_by IS NULL OR blocked_by IN (requester_id, addressee_id))
);
CREATE UNIQUE INDEX uidx_friend_pair
    ON friendships (LEAST(requester_id, addressee_id), GREATEST(requester_id, addressee_id));
CREATE INDEX idx_friend_requester ON friendships(requester_id);
CREATE INDEX idx_friend_addressee ON friendships(addressee_id);

-- ============================================================
-- 채팅
--   room_key 포맷 ([변경 v6] direct_key를 일반화, 중복 방 생성을 DB가 차단):
--     direct   방: 'd:{minUserId}:{maxUserId}'
--     question 방: 'q:{questionId}:{minUserId}:{maxUserId}'   (꼬리질문 1:1)
--     group    방: NULL (meeting_id UNIQUE가 중복을 이미 차단)
--   생성 패턴: INSERT ... ON CONFLICT (room_key) DO NOTHING 후 SELECT — 레이스 안전
-- ============================================================
CREATE TABLE chat_rooms (
    room_id BIGSERIAL PRIMARY KEY,
    room_type room_type NOT NULL,
    meeting_id BIGINT UNIQUE REFERENCES meetings(meeting_id) ON DELETE CASCADE,
    question_id BIGINT REFERENCES questions(question_id) ON DELETE CASCADE,
    room_key VARCHAR(80) UNIQUE,                 -- [변경 v6] direct_key → room_key
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (room_type <> 'group'    OR meeting_id IS NOT NULL),
    CHECK (room_type <> 'question' OR (question_id IS NOT NULL AND room_key IS NOT NULL)),  -- [변경 v6]
    CHECK (room_type <> 'direct'   OR room_key IS NOT NULL),
    CHECK (room_type = 'group'     OR meeting_id IS NULL),
    CHECK (room_type = 'question'  OR question_id IS NULL)                                  -- [신규 v6] 대칭 보강
);
CREATE INDEX idx_chatrooms_question ON chat_rooms(question_id);

CREATE TABLE chat_members (
    room_id BIGINT NOT NULL REFERENCES chat_rooms(room_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at TIMESTAMPTZ,
    last_read_at TIMESTAMPTZ,
    visible_after_message_id BIGINT NOT NULL DEFAULT 0,
    pinned_at TIMESTAMPTZ,
    notify_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_chat_members_visible_after_message_id CHECK (visible_after_message_id >= 0),
    PRIMARY KEY (room_id, user_id)
);
CREATE INDEX idx_chatmembers_user ON chat_members(user_id);

CREATE TABLE messages (
    message_id BIGSERIAL PRIMARY KEY,
    room_id BIGINT NOT NULL REFERENCES chat_rooms(room_id) ON DELETE CASCADE,
    sender_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    content TEXT,
    image_file_id UUID REFERENCES files(file_id) ON DELETE SET NULL,
    reply_to_message_id BIGINT,
    message_type VARCHAR(16) NOT NULL DEFAULT 'user',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_messages_reply_to_message
        FOREIGN KEY (reply_to_message_id) REFERENCES messages(message_id) ON DELETE SET NULL,
    CHECK (content IS NOT NULL OR image_file_id IS NOT NULL),
    CONSTRAINT ck_messages_message_type CHECK (message_type IN ('user', 'system')),
    CONSTRAINT ck_messages_system_text_only CHECK (
        message_type <> 'system'
        OR (content IS NOT NULL AND image_file_id IS NULL)
    )
);
CREATE INDEX idx_messages_room ON messages(room_id, created_at DESC);

-- ============================================================
-- 신고 / 제재
-- ============================================================
CREATE TABLE reports (
    report_id BIGSERIAL PRIMARY KEY,
    reporter_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    target_type report_target_type NOT NULL DEFAULT 'message',
    message_id BIGINT REFERENCES messages(message_id) ON DELETE SET NULL,
    answer_id BIGINT,
    schedule_id BIGINT,
    reported_user_id BIGINT REFERENCES users(user_id) ON DELETE CASCADE,
    reason report_reason NOT NULL,
    detail TEXT,
    context_snapshot JSONB,
    context_hash CHAR(64) NOT NULL,
    ai_recommendation ai_recommendation,
    ai_reason TEXT,
    ai_confidence NUMERIC(5,4) CHECK (ai_confidence BETWEEN 0 AND 1),
    ai_model_version VARCHAR(120),
    ai_policy_version VARCHAR(80),
    ai_reviewed_at TIMESTAMPTZ,
    ai_review_state ai_job_status NOT NULL DEFAULT 'pending',
    ai_review_attempt_id UUID,
    ai_attempts SMALLINT NOT NULL DEFAULT 0,
    ai_next_attempt_at TIMESTAMPTZ DEFAULT now(),
    ai_lease_until TIMESTAMPTZ,
    ai_locked_by VARCHAR(120),
    ai_last_error_code VARCHAR(80),
    ai_last_error_message VARCHAR(500),
    ai_decision ai_report_decision,
    ai_policy_set_hash CHAR(64),
    ai_review_result JSONB,
    status report_status NOT NULL DEFAULT 'pending',
    resolved_by BIGINT REFERENCES users(user_id),
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (status NOT IN ('confirmed', 'dismissed') OR resolved_by IS NOT NULL),
    CONSTRAINT fk_reports_answer
        FOREIGN KEY (answer_id) REFERENCES answers(answer_id) ON DELETE SET NULL,
    CONSTRAINT fk_reports_schedule
        FOREIGN KEY (schedule_id) REFERENCES meeting_schedules(schedule_id) ON DELETE SET NULL,
    CONSTRAINT ck_reports_target_xor CHECK (
        (target_type = 'message' AND answer_id IS NULL AND schedule_id IS NULL)
        OR (target_type = 'answer' AND message_id IS NULL AND schedule_id IS NULL)
        OR (target_type = 'schedule' AND message_id IS NULL AND answer_id IS NULL)
    ),
    CONSTRAINT ck_reports_message_reported_user CHECK (
        target_type <> 'message' OR reported_user_id IS NOT NULL
    ),
    CONSTRAINT ck_reports_answer_manual_only CHECK (
        target_type <> 'answer' OR ai_review_state = 'cancelled'
    ),
    CONSTRAINT ck_reports_schedule_manual_only CHECK (
        target_type <> 'schedule' OR ai_review_state = 'cancelled'
    ),
    CONSTRAINT ck_reports_schedule_reported_user CHECK (
        target_type <> 'schedule' OR reported_user_id IS NOT NULL
    ),
    CONSTRAINT ck_reports_context_hash CHECK (context_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_reports_ai_attempts CHECK (ai_attempts BETWEEN 0 AND 5),
    CONSTRAINT ck_reports_ai_processing_lease CHECK (ai_review_state <> 'processing' OR (
        ai_review_attempt_id IS NOT NULL
        AND ai_lease_until IS NOT NULL
        AND ai_locked_by IS NOT NULL
    )),
    CONSTRAINT ck_reports_ai_due_work CHECK (ai_review_state NOT IN ('pending', 'retry') OR ai_next_attempt_at IS NOT NULL),
    CONSTRAINT ck_reports_ai_completed CHECK (ai_review_state <> 'completed' OR (
        ai_decision IS NOT NULL
        AND ai_review_result IS NOT NULL
        AND ai_reviewed_at IS NOT NULL
    )),
    CONSTRAINT ck_reports_ai_completed_projection CHECK (ai_review_state <> 'completed' OR (
        ai_decision IS NOT NULL
        AND ai_confidence IS NOT NULL
        AND ai_reason IS NOT NULL
        AND ai_model_version IS NOT NULL
        AND ai_policy_set_hash IS NOT NULL
        AND ai_reviewed_at IS NOT NULL
        AND ai_review_result IS NOT NULL
    )),
    CONSTRAINT ck_reports_ai_dead CHECK (ai_review_state <> 'dead' OR ai_last_error_code IS NOT NULL)
);

CREATE OR REPLACE FUNCTION enforce_report_target_integrity()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    v_answer_is_ai BOOLEAN;
    v_answer_author_id BIGINT;
    v_schedule_creator_id BIGINT;
    v_allowed_target_delete BOOLEAN;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.target_type IS DISTINCT FROM OLD.target_type THEN
            RAISE EXCEPTION 'report target type is immutable'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_target_xor';
        END IF;

        IF NEW.message_id IS DISTINCT FROM OLD.message_id THEN
            v_allowed_target_delete :=
                OLD.target_type = 'message'
                AND OLD.message_id IS NOT NULL
                AND NEW.message_id IS NULL
                AND NOT EXISTS (
                    SELECT 1 FROM messages WHERE message_id = OLD.message_id
                );
            IF NOT v_allowed_target_delete THEN
                RAISE EXCEPTION 'report message target may only be cleared by target deletion'
                    USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_target_xor';
            END IF;
        END IF;

        IF NEW.answer_id IS DISTINCT FROM OLD.answer_id THEN
            v_allowed_target_delete :=
                OLD.target_type = 'answer'
                AND OLD.answer_id IS NOT NULL
                AND NEW.answer_id IS NULL
                AND NOT EXISTS (
                    SELECT 1 FROM answers WHERE answer_id = OLD.answer_id
                );
            IF NOT v_allowed_target_delete THEN
                RAISE EXCEPTION 'report answer target may only be cleared by target deletion'
                    USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_target_xor';
            END IF;
        END IF;

        IF NEW.schedule_id IS DISTINCT FROM OLD.schedule_id THEN
            v_allowed_target_delete :=
                OLD.target_type = 'schedule'
                AND OLD.schedule_id IS NOT NULL
                AND NEW.schedule_id IS NULL
                AND NOT EXISTS (
                    SELECT 1 FROM meeting_schedules WHERE schedule_id = OLD.schedule_id
                );
            IF NOT v_allowed_target_delete THEN
                RAISE EXCEPTION 'report schedule target may only be cleared by target deletion'
                    USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_target_xor';
            END IF;
        END IF;
    END IF;

    IF TG_OP = 'INSERT' AND (
        (NEW.target_type = 'message' AND NEW.message_id IS NULL)
        OR (NEW.target_type = 'answer' AND NEW.answer_id IS NULL)
        OR (NEW.target_type = 'schedule' AND NEW.schedule_id IS NULL)
    ) THEN
        RAISE EXCEPTION 'report selected target is required at creation'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_target_xor';
    END IF;

    IF NEW.target_type = 'answer' AND NEW.answer_id IS NOT NULL THEN
        SELECT is_ai, author_id
          INTO v_answer_is_ai, v_answer_author_id
          FROM answers
         WHERE answer_id = NEW.answer_id;

        IF FOUND AND (
            (v_answer_is_ai AND (v_answer_author_id IS NOT NULL OR NEW.reported_user_id IS NOT NULL))
            OR (
                NOT v_answer_is_ai
                AND (v_answer_author_id IS NULL OR NEW.reported_user_id IS DISTINCT FROM v_answer_author_id)
            )
        ) THEN
            RAISE EXCEPTION 'reported user must match the answer author semantics'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_answer_reported_user';
        END IF;
    END IF;

    IF NEW.target_type = 'schedule' AND NEW.schedule_id IS NOT NULL THEN
        SELECT created_by
          INTO v_schedule_creator_id
          FROM meeting_schedules
         WHERE schedule_id = NEW.schedule_id;

        IF FOUND AND (
            v_schedule_creator_id IS NULL
            OR NEW.reported_user_id IS DISTINCT FROM v_schedule_creator_id
        ) THEN
            RAISE EXCEPTION 'reported user must match the schedule creator'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_reports_schedule_reported_user';
        END IF;
    END IF;

    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_reports_target_integrity
BEFORE INSERT OR UPDATE OF target_type, message_id, answer_id, schedule_id, reported_user_id
ON reports
FOR EACH ROW
EXECUTE FUNCTION enforce_report_target_integrity();

CREATE INDEX idx_reports_status ON reports(status, created_at DESC);
CREATE INDEX idx_reports_reported_user ON reports(reported_user_id);
CREATE INDEX idx_reports_answer ON reports(answer_id) WHERE answer_id IS NOT NULL;
CREATE INDEX idx_reports_schedule ON reports(schedule_id) WHERE schedule_id IS NOT NULL;
CREATE INDEX idx_reports_ai_due ON reports(ai_next_attempt_at, created_at, report_id)
    WHERE ai_review_state IN ('pending', 'retry');
CREATE INDEX idx_reports_ai_expired_lease ON reports(ai_lease_until, report_id)
    WHERE ai_review_state = 'processing';

CREATE TABLE user_sanctions (
    sanction_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    report_id BIGINT REFERENCES reports(report_id) ON DELETE SET NULL,
    decision_source sanction_decision_source NOT NULL DEFAULT 'admin', -- 집행은 항상 app-main
    admin_id BIGINT REFERENCES users(user_id),           -- 관리자 직접 결정일 때 필수
    sanction_type sanction_type NOT NULL,
    reason TEXT NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at TIMESTAMPTZ,
    duration_minutes INTEGER,
    review_status sanction_review_status NOT NULL DEFAULT 'not_required',
    revoked_at TIMESTAMPTZ,
    revoked_by BIGINT CONSTRAINT fk_user_sanctions_revoked_by REFERENCES users(user_id),
    released_at TIMESTAMPTZ,                             -- 관리자 번복(해제) 시각
    released_by BIGINT REFERENCES users(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (sanction_type <> 'temporary' OR ends_at IS NOT NULL),
    CHECK (decision_source <> 'admin' OR admin_id IS NOT NULL),
    CHECK (decision_source <> 'ai_recommendation' OR sanction_type = 'temporary'),
    CONSTRAINT ck_user_sanctions_duration CHECK (
        (sanction_type = 'temporary' AND duration_minutes IS NOT NULL AND duration_minutes > 0 AND ends_at IS NOT NULL)
        OR (sanction_type = 'permanent' AND duration_minutes IS NULL)
    ),
    CONSTRAINT ck_user_sanctions_review_status CHECK (
        (
            decision_source = 'ai_recommendation'
            AND (report_id IS NOT NULL OR revoked_at IS NOT NULL)
            AND review_status IN ('pending_review', 'confirmed', 'dismissed')
        )
        OR (decision_source = 'admin' AND review_status = 'not_required')
    )
);
CREATE INDEX idx_sanctions_user ON user_sanctions(user_id, created_at DESC);
CREATE INDEX idx_sanctions_pending_review ON user_sanctions(created_at)
    WHERE decision_source = 'ai_recommendation' AND released_at IS NULL;
CREATE UNIQUE INDEX uq_user_sanctions_ai_report ON user_sanctions(report_id)
    WHERE decision_source = 'ai_recommendation' AND report_id IS NOT NULL;
CREATE INDEX idx_user_sanctions_effective ON user_sanctions(user_id, ends_at, sanction_id)
    WHERE revoked_at IS NULL AND review_status <> 'dismissed';

-- ============================================================
-- 문의 / 알림 / 로그인 로그
-- ============================================================
CREATE TABLE inquiries (
    inquiry_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    status inquiry_status NOT NULL DEFAULT 'pending',
    answer TEXT,
    answered_by BIGINT REFERENCES users(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    answered_at TIMESTAMPTZ
);
CREATE INDEX idx_inquiries_user ON inquiries(user_id, created_at DESC);
CREATE INDEX idx_inquiries_status ON inquiries(status, created_at DESC);

-- ============================================================
-- 관리자 변경 감사 로그 (append-only)
-- ============================================================
CREATE TABLE admin_audit_logs (
    audit_id BIGSERIAL PRIMARY KEY,
    actor_user_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    action TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id BIGINT NOT NULL,
    details JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_admin_audit_logs_action CHECK (
        action IN (
            'USER_SANCTION_CREATED',
            'USER_ACTIVATED',
            'USER_ROLE_CHANGED',
            'REPORT_CONFIRMED',
            'REPORT_DISMISSED',
            'INQUIRY_ANSWERED',
            'KNOWLEDGE_RELATION_APPROVED',
            'KNOWLEDGE_RELATION_REJECTED'
        )
    ),
    CONSTRAINT ck_admin_audit_logs_target_type CHECK (
        target_type IN ('user', 'report', 'inquiry', 'knowledge_relation_candidate')
    ),
    CONSTRAINT ck_admin_audit_logs_details_object CHECK (
        jsonb_typeof(details) = 'object'
    )
);
CREATE INDEX idx_admin_audit_logs_actor_created
    ON admin_audit_logs(actor_user_id, created_at DESC, audit_id DESC);
CREATE INDEX idx_admin_audit_logs_target_created
    ON admin_audit_logs(target_type, target_id, created_at DESC, audit_id DESC);
CREATE INDEX idx_admin_audit_logs_created_desc
    ON admin_audit_logs(created_at DESC, audit_id DESC);

CREATE TABLE notifications (
    notification_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    type notification_type NOT NULL,
    title VARCHAR(200) NOT NULL,
    body TEXT,
    ref_id BIGINT,
    answer_is_ai BOOLEAN,
    event_key VARCHAR(120),
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_notifications_answer_is_ai
        CHECK (answer_is_ai IS NULL OR type = 'question'::notification_type)
);
CREATE INDEX idx_notif_user ON notifications(user_id, created_at DESC);
CREATE UNIQUE INDEX uidx_notifications_user_event_key
    ON notifications(user_id, event_key)
    WHERE event_key IS NOT NULL;

CREATE TABLE web_push_subscriptions (
    subscription_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    session_id VARCHAR(64) NOT NULL,
    endpoint TEXT NOT NULL,
    endpoint_hash CHAR(64) NOT NULL UNIQUE,
    p256dh VARCHAR(512) NOT NULL,
    auth_secret VARCHAR(256) NOT NULL,
    binding_version BIGINT NOT NULL DEFAULT 1 CHECK (binding_version > 0),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_web_push_subscriptions_user ON web_push_subscriptions(user_id);
CREATE UNIQUE INDEX uidx_web_push_subscriptions_session ON web_push_subscriptions(session_id);

CREATE TABLE login_logs (
    log_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    provider auth_provider NOT NULL,
    logged_in_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_loginlogs_user ON login_logs(user_id, logged_in_at DESC);
CREATE INDEX idx_loginlogs_time ON login_logs(logged_in_at);

-- ============================================================
-- updated_at 트리거
-- ============================================================
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION protect_ai_question_task_legacy_embedding_marker()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF (TG_OP = 'INSERT' AND NEW.legacy_embedding_model_migrated)
        OR (TG_OP = 'UPDATE'
            AND NEW.legacy_embedding_model_migrated IS DISTINCT FROM OLD.legacy_embedding_model_migrated) THEN
        RAISE EXCEPTION
            'ck_ai_question_tasks_embedding_model: legacy migration marker is immutable'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_ai_question_tasks_embedding_model';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION sync_knowledge_source_active_compat()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.active := NEW.status = 'ready';
    IF NEW.status IN ('inactive','admin_suppressed') THEN
        NEW.deactivated_at := COALESCE(NEW.deactivated_at, now());
    ELSIF NEW.status IN ('pending','ready','failed') THEN
        NEW.deactivated_at := NULL;
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION notify_ai_report_policy_rules_changed()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM pg_notify('ai_report_policy_rules_changed', '');
    RETURN NULL;
END
$$;

CREATE TRIGGER trg_users_updated     BEFORE UPDATE ON users         FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_questions_updated BEFORE UPDATE ON questions     FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_answers_updated   BEFORE UPDATE ON answers       FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_ai_question_tasks_updated BEFORE UPDATE ON ai_question_tasks FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_ai_question_tasks_legacy_embedding_marker
    BEFORE INSERT OR UPDATE OF legacy_embedding_model_migrated ON ai_question_tasks
    FOR EACH ROW EXECUTE FUNCTION protect_ai_question_task_legacy_embedding_marker();
CREATE TRIGGER trg_knowledge_sources_updated BEFORE UPDATE ON knowledge_sources FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_knowledge_source_active_compat
    BEFORE INSERT OR UPDATE OF status ON knowledge_sources
    FOR EACH ROW EXECUTE FUNCTION sync_knowledge_source_active_compat();
CREATE TRIGGER trg_ai_report_policy_rules_updated BEFORE UPDATE ON ai_report_policy_rules FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_ai_report_policy_rules_notify
    AFTER INSERT OR UPDATE OR DELETE ON ai_report_policy_rules
    FOR EACH STATEMENT EXECUTE FUNCTION notify_ai_report_policy_rules_changed();
CREATE TRIGGER trg_web_push_subscriptions_updated BEFORE UPDATE ON web_push_subscriptions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_meetings_updated  BEFORE UPDATE ON meetings      FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_meeting_schedules_updated BEFORE UPDATE ON meeting_schedules FOR EACH ROW EXECUTE FUNCTION set_updated_at();          -- [신규 v9]
CREATE TRIGGER trg_meeting_recurrence_rules_updated BEFORE UPDATE ON meeting_recurrence_rules FOR EACH ROW EXECUTE FUNCTION set_updated_at(); -- [신규 v9]
CREATE TRIGGER trg_friend_updated    BEFORE UPDATE ON friendships   FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_settings_updated  BEFORE UPDATE ON user_settings FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ============================================================
-- [참고 1] 이미지 업로드/조회 전체 흐름 (S3 주소 완전 비노출)
-- ------------------------------------------------------------
-- 업로드:
--   1) 프론트 → 백엔드: POST /files/presign {contentType, purpose}
--   2) 백엔드: s3_key 생성 (예: q/{uuid}.jpg) → files INSERT(uploaded_at NULL)
--              → presigned PUT URL(3~5분 만료) + file_id 응답
--   3) 프론트 → S3: presigned PUT으로 직접 업로드
--   4) 프론트 → 백엔드: POST /files/{file_id}/complete → uploaded_at 기록
--      (모임 사진이면 이 시점에 300x300 썸네일 생성 → 원본은 image_file_id,
--       썸네일은 thumbnail_file_id 로 각각 연결. 교체 시 기존 두 파일 모두 삭제)
--   5) 이후 도메인 API 호출 시 file_id만 전달 (질문 등록, 메시지 전송 등)
-- 조회:
--   프론트가 받는 이미지 주소는 항상  GET /files/{file_id}
--   백엔드: file_id → s3_key 조회 → S3 GetObject → 바이트 스트리밍 응답
--   (Cache-Control 헤더 설정 권장. 302 리다이렉트는 네트워크 탭에 S3 주소가
--    노출되므로 사용하지 않음)
-- 청소 배치 2종 (사진 선택 즉시 업로드 UX 전제):
--   ① presign만 받고 PUT 미완료: uploaded_at IS NULL AND created_at < now()-'1 hour'
--   ② 업로드됐지만 미참조(작성 중 창 닫음): uploaded_at IS NOT NULL AND created_at < now()-'24 hours'
--      AND file_id가 users.profile_file_id / meetings.image_file_id /
--          meetings.thumbnail_file_id / messages.image_file_id /
--          question_images / answer_images 어디에도 없음
--   → 두 경우 모두 DB 행 삭제 + s3_key로 S3 오브젝트 삭제
--
-- [참고 2] 하드 딜리트 배치 (일 1회) — ★ 순서 중요 ★
--   1) 대상: SELECT user_id FROM users WHERE deleted_at < now() - interval '3 years';
--   2) 해당 유저 files의 (file_id, s3_key) 목록 수집 (아직 삭제하지 않음)
--   3) DELETE FROM users WHERE user_id IN (...);   -- 메시지/핀 등 FK 캐스케이드 정리
--   4) DELETE FROM files WHERE file_id IN (2에서 수집한 목록);  -- 고아 파일 행 제거
--      (※ 3보다 먼저 실행하면 이미지-only 메시지의 CHECK 제약에 걸려 실패함 — 의도된 보호)
--   5) 수집한 s3_key로 S3 오브젝트 삭제
--   ※ 관리자 영구삭제도 동일 절차, role='admin' 검증 + 2단계 확인 필수
--
-- [참고 3] PostGIS 반경 검색 (GiST 인덱스 자동 활용, 거리 단위 = 미터)
--   저장:  ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography   ← 경도 먼저!
--   주변 핀:
--     SELECT *, ST_Distance(location, :me) AS dist_m FROM pins
--     WHERE deleted_at IS NULL AND ST_DWithin(location, :me, 5000) ORDER BY dist_m;
--   알림 대상자:
--     SELECT u.user_id FROM users u JOIN user_settings s USING (user_id)
--     WHERE u.deleted_at IS NULL AND u.status='active'
--       AND s.notify_all AND s.notify_question
--       AND ST_DWithin(u.last_location, :pin, s.notify_radius_km * 1000);
--
-- [참고 4] 신고 파이프라인 — app-ai 권고 + app-main 정책 집행
--   1) 접수: reports INSERT (pending) + 전후 20개 context_snapshot 저장
--   2) app-main worker → app-ai review HTTP: immutable context snapshot 전달
--      → app-ai는 recommendation/confidence/reason/modelVersion만 반환
--      → app-ai는 reports/users/user_sanctions/Redis/SSE를 변경하지 않는다
--   3) app-main ReportDecisionPolicy:
--      → 권고를 수용하면 같은 TX에서 reports AI 필드 + user_sanctions INSERT
--        (decision_source='ai_recommendation') + users.status='suspended'
--      → 커밋 후에만 Redis 세션 revoke + 열린 SSE close
--      → hold/dismiss 권고는 정지 없이 관리자 검수 큐로
--   4) 관리자 사후 검수 (idx_sanctions_pending_review 큐):
--      - 확정: reports.status='confirmed' + resolved_by 기록.
--        기간 조정이나 영구 전환이 필요하면 decision_source='admin' 제재를 추가 발급
--      - 번복/기각: reports.status='dismissed' + AI 권고 기반 제재 released_at/by 기록
--        + users.status='active' 복구
--   DB 제약 요약: 최종 확정/기각은 resolved_by(관리자) 필수,
--                 AI 권고 기반 자동 집행은 temporary만, 영구정지는 관리자 전용.
--   ★ 집행 주체는 모든 경우 app-main이며 app-ai는 권고만 생성한다.
-- ============================================================
