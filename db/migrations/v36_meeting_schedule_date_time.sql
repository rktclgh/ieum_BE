-- ============================================================
-- v36: meeting_schedules 날짜·시간 분리 (시간 미정 지원)
--
-- 배경: 모임 생성 시 "날짜 미정"과 "시간 미정"은 서로 독립이어야 한다.
--       기존 starts_at TIMESTAMPTZ 단독 구조는 "날짜는 정했는데 시간은 미정"을 표현할 수 없었다.
--
-- 결정: starts_on(DATE) + start_time/end_time(TIME)을 저장 정본으로 두고,
--       starts_at/ends_at은 서버가 계산해 저장하는 파생 캐시로 남긴다.
--       (정렬·범위조회·인덱스·스케줄러·핀 노출 쿼리를 전부 무변경으로 보존하기 위함)
--
-- 불변식:
--   - starts_on NOT NULL — 일정 row는 항상 날짜가 있다. 날짜 미정 = row 부재.
--   - start_time NULL = 시간 미정. (recurring 회차는 서비스 레벨에서 NOT NULL 강제 — cross-table이라 CHECK 불가)
--   - end_time은 start_time 없이 쓸 수 없고, 같은 날 안에서 start_time보다 뒤여야 한다.
--   - starts_at = starts_on + COALESCE(start_time, '00:00') @ Asia/Seoul
--   - visible_until 규칙 불변: starts_on 의 KST 당일 23:59:59.999999999
--
-- 적용 순서: 이 파일 단독. 앱 배포보다 먼저 적용해야 한다(ddl-auto=validate).
-- ============================================================

BEGIN;

-- 자정을 넘기는 기존 종료 시각은 새 모델로 무손실 표현할 수 없다.
-- 그런 행은 절대 자동 변환하지 않고, 운영자가 별도 보존·정책 결정을 한 뒤 재시도한다.
DO $v36_preflight$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM meeting_schedules
         WHERE ends_at IS NOT NULL
           AND (ends_at AT TIME ZONE 'Asia/Seoul')::date
               <> (starts_at AT TIME ZONE 'Asia/Seoul')::date
    ) THEN
        RAISE EXCEPTION
            'v36 cannot migrate cross-midnight meeting_schedules without an explicit data-preservation plan';
    END IF;
END;
$v36_preflight$;

ALTER TABLE meeting_schedules
    ADD COLUMN starts_on  DATE,
    ADD COLUMN start_time TIME,
    ADD COLUMN end_time   TIME;

-- 기존 데이터 백필: 모두 "시간 확정" 일정으로 간주하고 KST 기준으로 분해한다.
UPDATE meeting_schedules
   SET starts_on  = (starts_at AT TIME ZONE 'Asia/Seoul')::date,
       start_time = (starts_at AT TIME ZONE 'Asia/Seoul')::time,
       end_time   = (ends_at   AT TIME ZONE 'Asia/Seoul')::time;

ALTER TABLE meeting_schedules
    ALTER COLUMN starts_on SET NOT NULL,
    ADD CONSTRAINT ck_msched_time_pair  CHECK (start_time IS NOT NULL OR end_time IS NULL),
    ADD CONSTRAINT ck_msched_time_order CHECK (end_time IS NULL OR end_time > start_time);

COMMIT;

-- 적용 후 확인용 (수동 실행):
--   SELECT count(*) FILTER (WHERE start_time IS NULL) AS time_undecided,
--          count(*) FILTER (WHERE ends_at IS NOT NULL AND end_time IS NULL) AS invalid_end_cache,
--          count(*) AS total
--     FROM meeting_schedules WHERE deleted_at IS NULL;
--   -- 이 마이그레이션 직후엔 time_undecided = 0 이어야 한다(전부 시간 확정으로 백필).
