-- v9: 모임 타입·일정·반복 규칙 (설계 v2 — 2026-07-09)
--   meetings = 지속 그룹(모집상태·방장·정원·채팅). 날짜의 정본은 meeting_schedules.
--   맵 노출은 파생: open && EXISTS(scheduled && visible_until >= now()) && 뷰어 not kicked.
--   visible_until = starts_at의 KST 당일 23:59:59 (서버가 계산·저장) — "당일 23:59까지 유효" 규칙의 인덱스 가능한 정본.
--   meetings.meeting_at 은 v9부터 legacy 캐시(다음 회차 시각)로만 사용.
-- 적용: v8 위치 스냅샷 이후 순서대로. 신규 설치는 schema.sql에 이미 포함.

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'meeting_type') THEN
        CREATE TYPE meeting_type AS ENUM ('one_time', 'recurring');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'meeting_schedule_status') THEN
        CREATE TYPE meeting_schedule_status AS ENUM ('scheduled', 'completed', 'cancelled');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'recurrence_frequency') THEN
        CREATE TYPE recurrence_frequency AS ENUM ('daily', 'weekly', 'monthly');
    END IF;
END
$$;

ALTER TABLE meetings
    ADD COLUMN IF NOT EXISTS type meeting_type NOT NULL DEFAULT 'one_time';

CREATE TABLE IF NOT EXISTS meeting_schedules (
    schedule_id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT NOT NULL REFERENCES meetings(meeting_id) ON DELETE CASCADE,
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

CREATE INDEX IF NOT EXISTS idx_meeting_schedules_visible
    ON meeting_schedules(meeting_id, visible_until)
    WHERE deleted_at IS NULL AND status = 'scheduled';

CREATE INDEX IF NOT EXISTS idx_meeting_schedules_calendar
    ON meeting_schedules(starts_at, schedule_id)
    WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS meeting_recurrence_rules (
    recurrence_rule_id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT NOT NULL UNIQUE REFERENCES meetings(meeting_id) ON DELETE CASCADE,
    frequency recurrence_frequency NOT NULL,
    interval_value SMALLINT NOT NULL DEFAULT 1,
    days_of_week SMALLINT[],
    day_of_month SMALLINT,
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

-- updated_at 트리거 (schema.sql 의 set_updated_at 함수 재사용)
DROP TRIGGER IF EXISTS trg_meeting_schedules_updated ON meeting_schedules;
CREATE TRIGGER trg_meeting_schedules_updated BEFORE UPDATE ON meeting_schedules
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
DROP TRIGGER IF EXISTS trg_meeting_recurrence_rules_updated ON meeting_recurrence_rules;
CREATE TRIGGER trg_meeting_recurrence_rules_updated BEFORE UPDATE ON meeting_recurrence_rules
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- 기존 meetings 행(있다면)에 대한 백필: meeting_at 기준 one_time 일정 1개 생성
INSERT INTO meeting_schedules (meeting_id, starts_at, visible_until, status, sequence_no)
SELECT m.meeting_id,
       m.meeting_at,
       (date_trunc('day', m.meeting_at AT TIME ZONE 'Asia/Seoul') + interval '23 hours 59 minutes 59 seconds')
           AT TIME ZONE 'Asia/Seoul',
       'scheduled',
       1
FROM meetings m
WHERE m.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM meeting_schedules s WHERE s.meeting_id = m.meeting_id);

COMMIT;
