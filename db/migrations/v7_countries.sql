-- ============================================================
-- Migration v6 → v7 : countries 참조 테이블 + users.nationality FK
--   기존 ieum DB(v6: users 존재, countries 없음)에 적용하는 "증분" 스크립트.
--   전체 신규 설치는 db/schema.sql 사용. 이 파일은 운영 DB 증분 전용.
--   적용 순서: (1) 이 파일  (2) db/seed_countries.sql
--   FK 추가 전 users.nationality 에 countries 미존재 값이 있으면 실패하지만,
--   v6에선 nationality 미수집이라 전부 NULL → 안전. 재실행 안전(멱등).
-- ============================================================
BEGIN;

CREATE TABLE IF NOT EXISTS countries (
    code       VARCHAR(2) PRIMARY KEY CHECK (code ~ '^[A-Z]{2}$'),  -- ISO 3166-1 alpha-2 (대문자)
    name_ko    VARCHAR(60) NOT NULL,               -- 서버측 표기용(피커 표시는 프론트 소유)
    name_en    VARCHAR(80) NOT NULL,
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,       -- 유효 코드 토글(삭제 대신 비활성)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'fk_users_nationality'
      AND conrelid = 'users'::regclass
  ) THEN
    ALTER TABLE users ADD CONSTRAINT fk_users_nationality
      FOREIGN KEY (nationality) REFERENCES countries(code);
  END IF;
END $$;

COMMIT;
