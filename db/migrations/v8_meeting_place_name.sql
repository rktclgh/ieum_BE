-- v8: meetings.place_name 추가 (모임 장소 텍스트 — 사용자 직접 입력, 목업 "동선역 2번 출구")
-- 적용: 기존 운영 DB에 순서대로. 신규 설치는 schema.sql v8이 이미 포함.
-- 안전: 기존 행이 있어도 동작(빈 문자열 백필 후 NOT NULL 승격).

BEGIN;

ALTER TABLE meetings ADD COLUMN IF NOT EXISTS place_name VARCHAR(100);
UPDATE meetings SET place_name = '' WHERE place_name IS NULL;
ALTER TABLE meetings ALTER COLUMN place_name SET NOT NULL;

COMMIT;
